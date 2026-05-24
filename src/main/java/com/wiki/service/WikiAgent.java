package com.wiki.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.wiki.dto.ConsolidateResult;
import com.wiki.dto.IngestResult;
import com.wiki.dto.LintReport;
import com.wiki.dto.LoadedSource;
import com.wiki.dto.QueryAnswer;
import com.wiki.dto.WikiEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The brain. Three structured-output prompts: ingest / query / lint.
 * The LLM decides *what* to write; WikiStore applies the edits deterministically.
 */
@Service
public class WikiAgent {
    private static final Logger log = LoggerFactory.getLogger(WikiAgent.class);

    /** Lenient mapper: accepts raw newlines inside JSON strings, which local models often emit. */
    static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ChatClient chat;
    private final WikiStore store;
    private final PromptLoader prompts;
    private final IngestProperties ingestProps;
    private final EmbeddingService embeddings;
    private final ConsolidateProperties consolidateProps;

    /** One composed (preamble-stripped) system prompt per type, cached for process lifetime. */
    private final Map<String, String> composedSystemPrompts = new java.util.concurrent.ConcurrentHashMap<>();

    public WikiAgent(ChatClient.Builder chatBuilder, WikiStore store, PromptLoader prompts,
                     IngestProperties ingestProps,
                     EmbeddingService embeddings, ConsolidateProperties consolidateProps) {
        this.chat = chatBuilder.build();
        this.store = store;
        this.prompts = prompts;
        this.ingestProps = ingestProps;
        this.embeddings = embeddings;
        this.consolidateProps = consolidateProps;
    }

    /** One semantically-near candidate for a concept page, as returned by qmd. */
    public record Neighbor(String path, double score, String snippet) {}

    /**
     * When the LLM emits a connecting paragraph without the required `[[wiki/concepts/X.md]]`
     * link, try to recover by finding which neighbor's basename appears as a Greek/English word
     * in the body. Returns the matched neighbor's full path, or null if none found.
     * Prefer the neighbor that scored highest in the embedding ranking, then the longest match
     * (more specific terms win over generic ones).
     */
    private static String recoverNeighborFromBody(String body, List<Neighbor> neighbors) {
        if (body == null || neighbors == null || neighbors.isEmpty()) return null;
        String lowerBody = body.toLowerCase();
        Neighbor best = null;
        int bestLen = 0;
        for (Neighbor n : neighbors) {
            String base = basenameNoExt(n.path()).toLowerCase();   // e.g. "σοφία" or "ηλιακή-έκλειψη"
            if (base.isEmpty()) continue;
            // The slug uses '-' between words; the prose uses spaces. Try both.
            String slugForm = base;
            String proseForm = base.replace('-', ' ');
            if (lowerBody.contains(slugForm) || lowerBody.contains(proseForm)) {
                int len = base.length();
                if (len > bestLen) { best = n; bestLen = len; }
            }
        }
        return best == null ? null : best.path();
    }

    private static String basenameNoExt(String path) {
        int slash = path.lastIndexOf('/');
        String base = slash < 0 ? path : path.substring(slash + 1);
        return base.endsWith(".md") ? base.substring(0, base.length() - 3) : base;
    }

    public ConsolidateProperties consolidateProps() {
        return consolidateProps;
    }

    public IngestResult ingest(LoadedSource source) throws IOException {
        return ingest(source, null, "generic");
    }

    public IngestResult ingest(LoadedSource source, String canonicalSourcePath) throws IOException {
        return ingest(source, canonicalSourcePath, "generic");
    }

    /**
     * Classifies the source into one of the types configured in {@code wiki.ingest.classifier.known-types}.
     * Returns `"generic"` when classification is disabled, when the LLM's response fails to parse,
     * or when it names a type not in the whitelist.
     */
    public String classify(LoadedSource source) {
        if (!ingestProps.getClassifier().isEnabled()) return "generic";
        List<String> knownTypes = ingestProps.getClassifier().getKnownTypes();
        if (knownTypes == null || knownTypes.isEmpty()) return "generic";

        String preview = truncate(source.text(), 1500);
        StringBuilder typeList = new StringBuilder();
        for (String t : knownTypes) typeList.append("- `").append(t).append("`\n");

        String template = prompts.load("classify.txt");
        String rendered = prompts.render(template, Map.of(
                "title", safe(source.title()),
                "sourcePath", safe(source.sourcePath()),
                "preview", preview,
                "knownTypes", typeList.toString().trim()
        ));

        try {
            long t0 = System.currentTimeMillis();
            String raw = chat.prompt().user(rendered).call().content();
            log.info("Classifier LLM returned in {} ms: {}", System.currentTimeMillis() - t0,
                    raw == null ? "" : raw.strip());
            String cleaned = stripFences(raw);
            if (cleaned == null || cleaned.isBlank()) return "generic";
            var node = LENIENT.readTree(cleaned);
            String type = node.has("type") ? node.get("type").asText("") : "";
            if (type.isBlank() || !knownTypes.contains(type)) {
                log.warn("Classifier returned unknown type '{}', falling back to generic", type);
                return "generic";
            }
            return type;
        } catch (Exception e) {
            log.warn("Classifier failed ({}); falling back to generic", e.getMessage());
            return "generic";
        }
    }

    /**
     * If {@code canonicalSourcePath} is non-null, the LLM's chosen source-summary path is overridden
     * with it, and any `[[wiki/sources/<llm-chosen>]]` cross-links inside edit bodies are rewritten
     * to match. Used by chunked ingests to guarantee every chunk's source lands at
     * `wiki/sources/{base}-part{N}.md` regardless of slug drift.
     *
     * <p>{@code type} selects which `system_<type>.md` + extractors/*.md get composed into the
     * system prompt. Unknown or missing types fall back to `system_generic.md`.
     */
    public IngestResult ingest(LoadedSource source, String canonicalSourcePath, String type) throws IOException {
        String index = store.readIndex();
        String logPrefix = store.todayLogPrefix("ingest", safe(source.title()));
        String safeType = (type == null || type.isBlank()) ? "generic" : type;

        BeanOutputConverter<IngestResult> converter = new BeanOutputConverter<>(IngestResult.class, LENIENT);
        String preamble = prompts.load("ingest.txt");
        String systemBody = composeSystemPrompt(safeType);
        String template = preamble + "\n\n---\n\n" + systemBody;
        String rendered = prompts.render(template, Map.of(
                "index", index,
                "title", safe(source.title()),
                "sourcePath", safe(source.sourcePath()),
                "text", truncate(source.text(), 40000),
                "logPrefix", logPrefix,
                "type", safeType
        )).replace("{format}", converter.getFormat());

        log.info("Ingest prompt: {} chars, calling LLM…", rendered.length());
        long t0 = System.currentTimeMillis();
        String raw = chat.prompt().user(rendered).call().content();
        log.info("LLM returned {} chars in {} ms", raw == null ? 0 : raw.length(), System.currentTimeMillis() - t0);
        String cleaned = stripFences(raw);
        IngestResult result;
        try {
            result = converter.convert(cleaned);
        } catch (Exception parseError) {
            log.warn("Primary parse failed ({}), attempting salvage of partial JSON", parseError.getMessage());
            result = salvageIngestResult(cleaned);
            if (result == null || result.edits() == null || result.edits().isEmpty()) {
                throw new IOException("LLM returned unparseable ingest result (salvage failed): " + raw, parseError);
            }
            log.warn("Salvaged {} edits from truncated response", result.edits().size());
        }
        if (result == null) {
            throw new IOException("LLM returned unparseable ingest result: " + raw);
        }

        if (canonicalSourcePath != null && !canonicalSourcePath.isBlank()) {
            result = normalizeSourcePath(result, canonicalSourcePath);
        }

        result = qualityGate(result, safeType);

        applyIngest(result, logPrefix);
        ensureSourceIndexed(result, source);
        return result;
    }

    /**
     * Builds the per-type system prompt: strips YAML frontmatter from `system_<type>.md`,
     * appends each `extractors/<name>.md` listed in the `includes:` block, joined by `---` separators.
     * Result is cached per type for process lifetime.
     */
    private String composeSystemPrompt(String type) {
        return composedSystemPrompts.computeIfAbsent(type, t -> {
            String raw = prompts.loadOrDefault("system_" + t + ".md", "system_generic.md");
            List<String> includes = new ArrayList<>();
            String body = parseFrontmatter(raw, includes);
            StringBuilder sb = new StringBuilder(body);
            for (String name : includes) {
                try {
                    String snippet = prompts.load("extractors/" + name + ".md");
                    sb.append("\n\n---\n\n").append(snippet);
                } catch (Exception e) {
                    log.warn("Extractor snippet missing: extractors/{}.md — skipping ({})", name, e.getMessage());
                }
            }
            return sb.toString();
        });
    }

    /**
     * Lightweight YAML-frontmatter parser: expects the file to start with `---`, an `includes:` key
     * with a block-style list of names, then a closing `---`. Returns the body after the closing `---`
     * and fills {@code includesOut} with the names. If no recognizable frontmatter, returns {@code raw}
     * unchanged and leaves {@code includesOut} empty.
     */
    static String parseFrontmatter(String raw, List<String> includesOut) {
        if (raw == null) return "";
        String t = raw.stripLeading();
        if (!t.startsWith("---")) return raw;
        int afterOpen = t.indexOf('\n', 3);
        if (afterOpen < 0) return raw;
        int closeIdx = t.indexOf("\n---", afterOpen);
        if (closeIdx < 0) return raw;

        String fm = t.substring(afterOpen + 1, closeIdx);
        boolean inIncludes = false;
        for (String line : fm.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("includes:")) {
                inIncludes = true;
                String rest = trimmed.substring("includes:".length()).trim();
                if (rest.startsWith("[") && rest.endsWith("]")) {
                    String inner = rest.substring(1, rest.length() - 1);
                    for (String part : inner.split(",")) {
                        String name = part.trim().replaceAll("^[\"']|[\"']$", "");
                        if (!name.isEmpty()) includesOut.add(name);
                    }
                    inIncludes = false;
                }
                continue;
            }
            if (inIncludes) {
                if (trimmed.startsWith("- ")) {
                    String name = trimmed.substring(2).trim().replaceAll("^[\"']|[\"']$", "");
                    if (!name.isEmpty()) includesOut.add(name);
                } else if (!trimmed.startsWith("-")) {
                    inIncludes = false;
                }
            }
        }

        int afterClose = t.indexOf('\n', closeIdx + 4);
        return afterClose < 0 ? "" : t.substring(afterClose + 1);
    }

    /**
     * Optional quality-gate pass over a candidate IngestResult. Returns the revised result, or the
     * original on any failure (parse error, LLM error, disabled config, empty gate list).
     * Never returns null and never returns worse quality than pre-gate.
     */
    public IngestResult qualityGate(IngestResult aggregate, String type) {
        if (aggregate == null) return null;
        if (!ingestProps.getQualityGate().isEnabled()) return aggregate;
        List<String> gateNames = ingestProps.getQualityGate().getTypeGates()
                .getOrDefault(type, List.of());
        if (gateNames.isEmpty()) return aggregate;

        StringBuilder gatesSection = new StringBuilder();
        int loaded = 0;
        for (String name : gateNames) {
            try {
                gatesSection.append(prompts.load("gates/" + name + ".md")).append("\n\n");
                loaded++;
            } catch (Exception e) {
                log.warn("Gate snippet missing: gates/{}.md — skipping", name);
            }
        }
        if (loaded == 0) return aggregate;

        String aggregateJson;
        try {
            aggregateJson = LENIENT.writerWithDefaultPrettyPrinter().writeValueAsString(aggregate);
        } catch (Exception e) {
            log.warn("Quality-gate: failed to serialize candidate ({}), skipping", e.getMessage());
            return aggregate;
        }

        BeanOutputConverter<IngestResult> converter = new BeanOutputConverter<>(IngestResult.class, LENIENT);
        String template = prompts.load("gate.txt");
        String rendered = prompts.render(template, Map.of(
                "type", type,
                "aggregate", aggregateJson,
                "gates", gatesSection.toString().trim()
        )).replace("{format}", converter.getFormat());

        try {
            long t0 = System.currentTimeMillis();
            String raw = chat.prompt().user(rendered).call().content();
            log.info("Quality-gate LLM returned {} chars in {} ms",
                    raw == null ? 0 : raw.length(), System.currentTimeMillis() - t0);
            String cleaned = stripFences(raw);
            IngestResult revised = converter.convert(cleaned);
            if (revised == null) {
                log.warn("Quality-gate parse returned null; keeping original aggregate");
                return aggregate;
            }
            int before = aggregate.edits() == null ? 0 : aggregate.edits().size();
            int after = revised.edits() == null ? 0 : revised.edits().size();
            if (after == 0 && before > 0) {
                log.warn("Quality-gate dropped ALL edits ({} → 0); refusing, keeping original", before);
                return aggregate;
            }
            log.info("Quality-gate: {} edits → {} edits after review", before, after);
            return revised;
        } catch (Exception e) {
            log.warn("Quality-gate call failed ({}); keeping original aggregate", e.getMessage());
            return aggregate;
        }
    }

    /**
     * For a concept page, ask qmd for its top semantically-near concept neighbors. Applies the
     * cosine-floor cut, drops self/non-concept results, drops pairs already linked in either
     * direction, and returns at most {@code neighborsPerPage} candidates.
     */
    public List<Neighbor> findNeighbors(String anchorPath, LinkGraph.Graph graph) {
        try {
            Set<String> alreadyLinked = new java.util.HashSet<>();
            if (graph != null) {
                alreadyLinked.addAll(graph.outgoing().getOrDefault(anchorPath, Set.of()));
                alreadyLinked.addAll(graph.incoming().getOrDefault(anchorPath, Set.of()));
            }
            double floor = consolidateProps.getCosineFloor();
            int cap = consolidateProps.getNeighborsPerPage();

            // Probe top-10 raw cosines (no floor, no filter) for diagnostic visibility into
            // how text-embedding-3-small is clustering these pages, then apply the real filter.
            List<EmbeddingService.Result> raw = embeddings.findNeighbors(
                    anchorPath, -1.0, 10, p -> !p.equals(anchorPath));
            if (log.isInfoEnabled()) {
                StringBuilder sb = new StringBuilder("findNeighbors top-10 for ").append(anchorPath).append(": ");
                for (EmbeddingService.Result r : raw) {
                    sb.append(String.format(java.util.Locale.ROOT, "%.3f", r.score())).append("=").append(r.path()).append(" ");
                }
                log.info(sb.toString().trim());
            }

            List<EmbeddingService.Result> hits = embeddings.findNeighbors(
                    anchorPath,
                    floor,
                    cap + alreadyLinked.size() + 4,  // headroom for the already-linked filter below
                    path -> (path.startsWith("wiki/concepts/") || path.startsWith("wiki/entities/"))
                            && path.endsWith(".md")
                            && !alreadyLinked.contains(path));

            List<Neighbor> out = new ArrayList<>();
            for (EmbeddingService.Result r : hits) {
                if (out.size() >= cap) break;
                String snippet = snippetOf(r.path());
                out.add(new Neighbor(r.path(), r.score(), snippet));
            }
            return out;
        } catch (Exception e) {
            log.warn("findNeighbors for {} failed: {}", anchorPath, e.getMessage());
            return List.of();
        }
    }

    private String snippetOf(String path) {
        try {
            String body = store.readPage(path);
            if (body == null) return "";
            String trimmed = body.replaceAll("\\s+", " ").trim();
            return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Builds a short "aboutness" string from a page body: first heading + first ~500 chars.
     * Sanitizes qmd query-operator chars ({@code -}, {@code +}, {@code ":`) into spaces because
     * qmd's vec query parser rejects them (negation/required/phrase syntax).
     */
    static String seedTextFor(String body) {
        if (body == null) return "";
        String[] lines = body.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            String t = l.strip();
            if (t.startsWith("# ")) {
                sb.append(t.substring(2)).append(". ");
                break;
            }
        }
        String rest = body.replaceAll("(?m)^#+\\s.*$", " ").replaceAll("\\s+", " ").trim();
        sb.append(rest);
        String seed = sb.length() > 500 ? sb.substring(0, 500) : sb.toString();
        seed = seed.replaceAll("[\\-+\":()\\[\\]\\\\]", " ").replaceAll("\\s+", " ").trim();
        return seed;
    }

    /**
     * For a concept page + its qmd-vouched neighbors, ask the LLM which pairs warrant a cross-link
     * and produce append-style edits. Enforces conservative post-parse guards — ghost-link cut,
     * action=append only, body must contain a `[[wiki/concepts/…]]` reference, cap at neighborsPerPage.
     * Never throws; on any failure returns an empty edits list with a rationale explaining why.
     */
    public ConsolidateResult consolidatePage(String anchorPath, List<Neighbor> neighbors) {
        if (neighbors == null || neighbors.isEmpty()) {
            return new ConsolidateResult(List.of(), "no eligible neighbors");
        }
        String body;
        try {
            body = store.readPage(anchorPath);
        } catch (Exception e) {
            return new ConsolidateResult(List.of(), "could not read anchor: " + e.getMessage());
        }
        if (body == null || body.isBlank()) {
            return new ConsolidateResult(List.of(), "anchor body empty");
        }

        StringBuilder nbrBlock = new StringBuilder();
        Set<String> validTargets = new java.util.HashSet<>();
        int idx = 1;
        for (Neighbor n : neighbors) {
            String nbody;
            try {
                nbody = store.readPage(n.path());
            } catch (Exception e) {
                nbody = "";
            }
            nbrBlock.append("### Neighbor ").append(idx++).append(": ").append(n.path())
                    .append(" (cosine ").append(String.format(java.util.Locale.ROOT, "%.2f", n.score())).append(")\n\n")
                    .append("```\n").append(truncate(nbody, 1500)).append("\n```\n\n");
            validTargets.add(n.path());
        }

        BeanOutputConverter<ConsolidateResult> converter = new BeanOutputConverter<>(ConsolidateResult.class, LENIENT);
        String template = prompts.load("consolidate.txt");
        String rendered = prompts.render(template, Map.of(
                "pagePath", anchorPath,
                "pageBody", truncate(body, 2000),
                "neighbors", nbrBlock.toString().trim(),
                "maxEdits", String.valueOf(consolidateProps.getNeighborsPerPage())
        )).replace("{format}", converter.getFormat());

        try {
            long t0 = System.currentTimeMillis();
            String raw = chat.prompt().user(rendered).call().content();
            log.info("Consolidate LLM returned {} chars in {} ms for {}",
                    raw == null ? 0 : raw.length(), System.currentTimeMillis() - t0, anchorPath);
            String cleaned = stripFences(raw);
            ConsolidateResult parsed = converter.convert(cleaned);
            if (parsed == null) {
                return new ConsolidateResult(List.of(), "parse returned null");
            }

            List<WikiEdit> kept = new ArrayList<>();
            // Unicode-aware: \w is ASCII-only in Java by default; without this Greek paths are
            // invisible to the matcher. Also accept entity targets — Consolidate now bridges
            // both concept↔concept and entity↔concept (or entity↔entity) relations.
            java.util.regex.Pattern conceptLink = java.util.regex.Pattern.compile("\\[\\[wiki/(?:concepts|entities)/[\\p{L}\\p{N}_\\-./]+\\.md]]");
            List<WikiEdit> raws = parsed.edits() == null ? List.of() : parsed.edits();
            for (WikiEdit e : raws) {
                if (kept.size() >= consolidateProps.getNeighborsPerPage()) break;
                if (e == null || e.path() == null || e.body() == null) continue;
                if (!anchorPath.equals(e.path().trim())) {
                    log.warn("Consolidate: dropping edit with path={} (expected anchor {})", e.path(), anchorPath);
                    continue;
                }
                String action = e.action() == null ? "" : e.action().trim().toLowerCase();
                if (!"append".equals(action)) {
                    log.warn("Consolidate: dropping non-append edit (action={}) on {}", e.action(), anchorPath);
                    continue;
                }
                var m = conceptLink.matcher(e.body());
                List<String> bodyLinks = new ArrayList<>();
                String matchedTarget = null;
                while (m.find()) {
                    String target = m.group().substring(2, m.group().length() - 2);
                    bodyLinks.add(target);
                    if (validTargets.contains(target)) {
                        matchedTarget = target;
                        break;
                    }
                }
                if (matchedTarget == null) {
                    // Safety net: gpt-4o-mini often writes a perfect connecting paragraph but omits
                    // the required `[[wiki/concepts/X.md]]` link. Instead of dropping the edit, try
                    // to recover by scanning the body for a neighbor's basename (e.g. "σοφία") and
                    // appending the corresponding link.
                    String recovered = recoverNeighborFromBody(e.body(), neighbors);
                    if (recovered != null) {
                        String newBody = e.body().trim() + " [[" + recovered + "]]";
                        e = new WikiEdit(e.path(), e.action(), newBody);
                        log.info("Consolidate recover [auto-link] on {}: appended [[{}]] (LLM omitted link)",
                                anchorPath, recovered);
                        kept.add(e);
                        continue;
                    }
                    String preview = e.body().replaceAll("\\s+", " ").trim();
                    if (preview.length() > 300) preview = preview.substring(0, 300) + "…";
                    log.warn("Consolidate drop [no valid link, no recovery] on {} — body links: {} — valid targets: {} — body: {}",
                            anchorPath, bodyLinks, validTargets, preview);
                    continue;
                }
                kept.add(e);
            }

            String rationale = parsed.rationale() == null ? "" : parsed.rationale();
            if (kept.size() != raws.size()) {
                rationale = rationale + " [post-filter: " + raws.size() + " → " + kept.size() + "]";
            }
            if (!kept.isEmpty()) {
                String preview = kept.get(0).body().replaceAll("\\s+", " ").trim();
                if (preview.length() > 240) preview = preview.substring(0, 240) + "…";
                log.info("Consolidate edit preview for {}: {}", anchorPath, preview);
            }
            return new ConsolidateResult(kept, rationale);
        } catch (Exception e) {
            log.warn("Consolidate call failed for {}: {}", anchorPath, e.getMessage());
            return new ConsolidateResult(List.of(), "parse failed: " + e.getMessage());
        }
    }

    /**
     * Force the source summary to live at {@code canonicalPath} and rewrite any `[[wiki/sources/<old>]]`
     * references inside edit bodies + index entries to match. Prevents slug drift across chunks.
     */
    static IngestResult normalizeSourcePath(IngestResult r, String canonicalPath) {
        if (r == null) return null;
        WikiEdit orig = r.sourceSummary();
        String oldPath = (orig == null) ? null : (orig.path() == null ? null : orig.path().trim());
        if (oldPath != null && oldPath.equals(canonicalPath)) return r;

        WikiEdit newSummary = (orig == null) ? null
                : new WikiEdit(canonicalPath, orig.action(),
                        rewriteSourceRef(orig.body(), oldPath, canonicalPath));

        List<WikiEdit> newEdits = new ArrayList<>();
        if (r.edits() != null) {
            for (WikiEdit e : r.edits()) {
                if (e == null) continue;
                newEdits.add(new WikiEdit(e.path(), e.action(),
                        rewriteSourceRef(e.body(), oldPath, canonicalPath)));
            }
        }

        String newIndex = (r.indexEntry() == null || oldPath == null) ? r.indexEntry()
                : r.indexEntry().replace(oldPath, canonicalPath);

        return new IngestResult(newSummary, newEdits, newIndex, r.logLine());
    }

    private static final Pattern PART_DENOMINATOR =
            Pattern.compile("(wiki/sources/[a-z0-9-]+?-part\\d+)-\\d+(\\.md)");

    private static String rewriteSourceRef(String body, String oldPath, String canonicalPath) {
        if (body == null) return null;
        String out = body;
        if (oldPath != null && !oldPath.equals(canonicalPath)) {
            out = out.replace(oldPath, canonicalPath);
        }
        return PART_DENOMINATOR.matcher(out).replaceAll("$1$2");
    }

    /** LLM often omits the source's own index line. Inject it deterministically. */
    private void ensureSourceIndexed(IngestResult r, LoadedSource source) throws IOException {
        WikiEdit src = r.sourceSummary();
        if (src == null || src.path() == null || src.body() == null) return;
        String title = extractFirstHeading(src.body());
        if (title.isBlank()) title = safe(source.title());
        String hook = extractFirstHook(src.body());
        String line = "- [" + title + "](" + src.path().trim() + ")"
                + (hook.isBlank() ? "" : " — " + hook);
        store.appendIndexEntry(line);
    }

    private static String extractFirstHeading(String body) {
        if (body == null) return "";
        for (String l : body.split("\\R")) {
            String t = l.strip();
            if (t.startsWith("# ")) return t.substring(2).trim();
        }
        return "";
    }

    private static String extractFirstHook(String body) {
        if (body == null) return "";
        for (String l : body.split("\\R")) {
            String t = l.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("-") || t.startsWith("*") || t.startsWith("[[")) continue;
            String plain = t.replaceAll("^\\*+", "").replaceAll("\\*+", "")
                    .replaceAll("^\\*\\*[^*]+\\*\\*:?\\s*", "")
                    .replaceAll("`+", "")
                    .trim();
            if (plain.length() > 100) plain = plain.substring(0, 100).trim();
            return plain;
        }
        return "";
    }

    private void applyIngest(IngestResult r, String fallbackLogPrefix) throws IOException {
        java.util.List<WikiEdit> all = new java.util.ArrayList<>();
        if (r.sourceSummary() != null) all.add(r.sourceSummary());
        if (r.edits() != null) all.addAll(r.edits());
        java.util.List<WikiEdit> applied = store.applyEdits(all);
        log.info("Applied {} / {} edits (after reserved-path + dedupe filtering)", applied.size(), all.size());

        java.util.Set<String> validPaths = new java.util.HashSet<>();
        for (WikiEdit e : applied) validPaths.add(e.path().trim());
        for (String existing : store.listPages()) validPaths.add(existing);

        if (r.indexEntry() != null) {
            int kept = 0, dropped = 0;
            for (String line : r.indexEntry().split("\\R")) {
                if (line.isBlank()) continue;
                String referenced = extractPath(line);
                if (referenced == null || validPaths.contains(referenced)) {
                    store.appendIndexEntry(line);
                    kept++;
                } else {
                    log.warn("Dropping ghost index entry pointing at non-existent {}: {}", referenced, line);
                    dropped++;
                }
            }
            log.info("Index entries: {} kept, {} dropped as ghosts", kept, dropped);
        }
        String logLine = r.logLine() == null || r.logLine().isBlank() ? fallbackLogPrefix : r.logLine();
        store.appendLog(logLine);
    }

    /** Extract the first `wiki/...md` reference from an index entry line. */
    private static String extractPath(String line) {
        var m = java.util.regex.Pattern.compile("(wiki/[\\p{L}\\p{N}_\\-./]+\\.md)").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    public QueryAnswer query(String question, List<String> pagePaths) throws IOException {
        BeanOutputConverter<QueryAnswer> converter = new BeanOutputConverter<>(QueryAnswer.class, LENIENT);

        StringBuilder pagesBlock = new StringBuilder();
        for (String path : pagePaths) {
            String body = store.readPage(path);
            if (body.isBlank()) continue;
            pagesBlock.append("### ").append(path).append("\n\n")
                    .append("```\n").append(truncate(body, 12000)).append("\n```\n\n");
        }

        String template = prompts.load("query.txt");
        String rendered = prompts.render(template, Map.of(
                "question", safe(question),
                "pages", pagesBlock.toString()
        )).replace("{format}", converter.getFormat());

        String raw = chat.prompt().user(rendered).call().content();
        QueryAnswer answer = converter.convert(stripFences(raw));
        if (answer == null) {
            throw new IOException("LLM returned unparseable query answer: " + raw);
        }

        store.appendLog(store.todayLogPrefix("query", truncate(question, 120)));
        return answer;
    }

    public LintReport lint() throws IOException {
        BeanOutputConverter<LintReport> converter = new BeanOutputConverter<>(LintReport.class, LENIENT);

        List<String> pages = store.listPages();

        // Strip prior lint entries so the model doesn't flag its own past runs as stale.
        String cleanLog = store.readLog().lines()
                .filter(l -> !l.contains("] lint |"))
                .reduce("", (a, b) -> a + b + "\n");

        String template = prompts.load("lint.txt");
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("pages", String.join("\n", pages));
        vars.put("index", store.readIndex());
        vars.put("log", cleanLog);
        String rendered = prompts.render(template, vars).replace("{format}", converter.getFormat());

        String raw = chat.prompt().user(rendered).call().content();
        String cleaned = stripFences(raw);
        // Local models often drop the wrapper and return a bare issues array; re-wrap it.
        if (cleaned != null && cleaned.stripLeading().startsWith("[")) {
            cleaned = "{\"issues\": " + cleaned + "}";
        }
        LintReport report;
        try {
            report = converter.convert(cleaned);
        } catch (Exception e) {
            log.warn("Lint parse failed ({}), attempting salvage", e.getMessage());
            List<LintReport.Issue> salvaged = extractArrayOfObjects(cleaned, "issues", LintReport.Issue.class);
            if (salvaged.isEmpty() && cleaned != null) {
                // Bare array case: try scanning from the first `[`
                salvaged = extractArrayOfObjectsFromBareArray(cleaned, LintReport.Issue.class);
            }
            if (!salvaged.isEmpty()) {
                log.warn("Salvaged {} lint issues from truncated response", salvaged.size());
                report = new LintReport(salvaged);
            } else {
                log.warn("Lint salvage yielded 0 issues; raw output was: {}", raw);
                report = new LintReport(List.of());
            }
        }
        if (report == null) report = new LintReport(List.of());

        report = filterHallucinatedIssues(report);

        store.appendLog(store.todayLogPrefix("lint",
                report.issues() == null ? "0 issues" : report.issues().size() + " issues"));
        return report;
    }

    /**
     * Small local models (observed on qwen3.5-4b) sometimes emit `gap`/`orphan` issues where the
     * description names two paths that are literally identical. That's a pattern-match hallucination,
     * not a real finding. Drop any issue whose description references the same `wiki/…\.md` path
     * two or more times with no distinct alternative.
     */
    static LintReport filterHallucinatedIssues(LintReport r) {
        if (r == null || r.issues() == null) return r;
        List<LintReport.Issue> kept = new ArrayList<>();
        int dropped = 0;
        java.util.regex.Pattern pathPat = java.util.regex.Pattern.compile("wiki/[\\p{L}\\p{N}_\\-./]+\\.md");
        for (LintReport.Issue i : r.issues()) {
            String desc = i == null ? null : i.description();
            if (desc != null) {
                java.util.Set<String> paths = new java.util.LinkedHashSet<>();
                var m = pathPat.matcher(desc);
                int hits = 0;
                while (m.find()) { paths.add(m.group()); hits++; }
                if (hits >= 2 && paths.size() == 1) {
                    dropped++;
                    continue;
                }
            }
            kept.add(i);
        }
        if (dropped > 0) log.warn("Dropped {} hallucinated lint issues (identical paths)", dropped);
        return new LintReport(kept);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n\n[truncated]";
    }

    /**
     * Strips markdown code fences (```json … ```) and isolates the outermost JSON object/array.
     * Local models frequently wrap their JSON response in a fence; Jackson can't parse through it.
     */
    static String stripFences(String s) {
        if (s == null) return null;
        String t = s.strip();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl >= 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.strip();
        }
        int start = indexOfAny(t, '{', '[');
        if (start > 0) t = t.substring(start);
        int end = Math.max(t.lastIndexOf('}'), t.lastIndexOf(']'));
        if (end >= 0 && end < t.length() - 1) t = t.substring(0, end + 1);
        return t;
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a), ib = s.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }

    /**
     * Best-effort recovery when the LLM's JSON is truncated mid-output.
     * Walks the "edits" array and keeps every complete {…} object; extracts sourceSummary if complete.
     */
    static IngestResult salvageIngestResult(String json) {
        if (json == null) return null;
        WikiEdit sourceSummary = extractObject(json, "sourceSummary", WikiEdit.class);
        List<WikiEdit> edits = extractArrayOfObjects(json, "edits", WikiEdit.class);
        String indexEntry = extractString(json, "indexEntry");
        String logLine = extractString(json, "logLine");
        return new IngestResult(sourceSummary, edits, indexEntry, logLine);
    }

    private static <T> T extractObject(String json, String key, Class<T> type) {
        int keyIdx = findKey(json, key);
        if (keyIdx < 0) return null;
        int colon = json.indexOf(':', keyIdx);
        if (colon < 0) return null;
        int brace = skipWhitespace(json, colon + 1);
        if (brace >= json.length() || json.charAt(brace) != '{') return null;
        int end = findMatchingClose(json, brace, '{', '}');
        if (end < 0) return null;
        try {
            return LENIENT.readValue(json.substring(brace, end + 1), type);
        } catch (Exception e) {
            return null;
        }
    }

    /** Walks a bare `[{…},{…},…]` payload (no wrapping key) and returns every parseable element. */
    static <T> List<T> extractArrayOfObjectsFromBareArray(String json, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (json == null) return out;
        int bracket = json.indexOf('[');
        if (bracket < 0) return out;
        int i = bracket + 1;
        while (i < json.length()) {
            i = skipWhitespace(json, i);
            if (i >= json.length()) break;
            char c = json.charAt(i);
            if (c == ']') break;
            if (c == ',') { i++; continue; }
            if (c != '{') break;
            int end = findMatchingClose(json, i, '{', '}');
            if (end < 0) break;
            try {
                out.add(LENIENT.readValue(json.substring(i, end + 1), type));
            } catch (Exception ignored) {}
            i = end + 1;
        }
        return out;
    }

    private static <T> List<T> extractArrayOfObjects(String json, String key, Class<T> type) {
        List<T> out = new ArrayList<>();
        int keyIdx = findKey(json, key);
        if (keyIdx < 0) return out;
        int colon = json.indexOf(':', keyIdx);
        if (colon < 0) return out;
        int bracket = skipWhitespace(json, colon + 1);
        if (bracket >= json.length() || json.charAt(bracket) != '[') return out;
        int i = bracket + 1;
        while (i < json.length()) {
            i = skipWhitespace(json, i);
            if (i >= json.length()) break;
            char c = json.charAt(i);
            if (c == ']') break;
            if (c == ',') { i++; continue; }
            if (c != '{') break;
            int end = findMatchingClose(json, i, '{', '}');
            if (end < 0) break; // truncated mid-object; stop salvaging
            try {
                out.add(LENIENT.readValue(json.substring(i, end + 1), type));
            } catch (Exception ignored) {}
            i = end + 1;
        }
        return out;
    }

    private static String extractString(String json, String key) {
        int keyIdx = findKey(json, key);
        if (keyIdx < 0) return null;
        int colon = json.indexOf(':', keyIdx);
        if (colon < 0) return null;
        int quote = skipWhitespace(json, colon + 1);
        if (quote >= json.length() || json.charAt(quote) != '"') return null;
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = quote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                escape = false;
                continue;
            }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') return sb.toString();
            sb.append(c);
        }
        return null; // unterminated
    }

    private static int findKey(String json, String key) {
        String needle = "\"" + key + "\"";
        return json.indexOf(needle);
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Returns index of matching close, or -1 if the bracket is unterminated (truncated input). */
    private static int findMatchingClose(String s, int openIdx, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') { escape = true; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
