package com.wiki.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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

    public WikiAgent(ChatClient.Builder chatBuilder, WikiStore store, PromptLoader prompts) {
        this.chat = chatBuilder.build();
        this.store = store;
        this.prompts = prompts;
    }

    public IngestResult ingest(LoadedSource source) throws IOException {
        return ingest(source, null);
    }

    /**
     * If {@code canonicalSourcePath} is non-null, the LLM's chosen source-summary path is overridden
     * with it, and any `[[wiki/sources/<llm-chosen>]]` cross-links inside edit bodies are rewritten
     * to match. Used by chunked ingests to guarantee every chunk's source lands at
     * `wiki/sources/{base}-part{N}.md` regardless of slug drift.
     */
    public IngestResult ingest(LoadedSource source, String canonicalSourcePath) throws IOException {
        String index = store.readIndex();
        String logPrefix = store.todayLogPrefix("ingest", safe(source.title()));

        BeanOutputConverter<IngestResult> converter = new BeanOutputConverter<>(IngestResult.class, LENIENT);
        String template = prompts.load("ingest.txt");
        String rendered = prompts.render(template, Map.of(
                "index", index,
                "title", safe(source.title()),
                "sourcePath", safe(source.sourcePath()),
                "text", truncate(source.text(), 40000),
                "logPrefix", logPrefix
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

        applyIngest(result, logPrefix);
        ensureSourceIndexed(result, source);
        return result;
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

    private static String rewriteSourceRef(String body, String oldPath, String canonicalPath) {
        if (body == null || oldPath == null || oldPath.equals(canonicalPath)) return body;
        return body.replace(oldPath, canonicalPath);
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
        var m = java.util.regex.Pattern.compile("(wiki/[\\w\\-./]+\\.md)").matcher(line);
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
        java.util.regex.Pattern pathPat = java.util.regex.Pattern.compile("wiki/[\\w\\-./]+\\.md");
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
