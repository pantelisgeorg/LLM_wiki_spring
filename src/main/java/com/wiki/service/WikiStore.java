package com.wiki.service;

import com.wiki.dto.WikiEdit;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns all filesystem IO under wiki-root/. The LLM decides *what* to write;
 * this class decides *how* (atomically, idempotently, with consistent layout).
 */
@Service
public class WikiStore {
    private static final Logger log = LoggerFactory.getLogger(WikiStore.class);

    private final Path root;

    public WikiStore(@Value("${wiki.root}") String rootPath) {
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void ensureLayout() throws IOException {
        Files.createDirectories(root.resolve("raw/articles"));
        Files.createDirectories(root.resolve("raw/pdfs"));
        Files.createDirectories(root.resolve("raw/web"));
        Files.createDirectories(root.resolve("raw/assets"));
        Files.createDirectories(root.resolve("wiki/entities"));
        Files.createDirectories(root.resolve("wiki/concepts"));
        Files.createDirectories(root.resolve("wiki/sources"));

        Path index = root.resolve("wiki/index.md");
        if (!Files.exists(index)) {
            Files.writeString(index, """
                # LLM Wiki Index

                Catalog of all pages. Each line: `- [title](relative/path.md) — one-line hook`.

                ## Entities

                ## Concepts

                ## Sources
                """);
        }

        Path logFile = root.resolve("wiki/log.md");
        if (!Files.exists(logFile)) {
            Files.writeString(logFile, "# Wiki Log\n\nAppend-only record of ingest / query / lint events.\n\n");
        }

        Path claude = root.resolve("CLAUDE.md");
        if (!Files.exists(claude)) {
            try (var in = new ClassPathResource("seed/CLAUDE.md").getInputStream()) {
                Files.writeString(claude, StreamUtils.copyToString(in, StandardCharsets.UTF_8));
            }
        }

        log.info("Wiki root initialized at {}", root);
    }

    public Path root() {
        return root;
    }

    public Path resolveSafe(String relPath) {
        Path p = root.resolve(relPath).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes wiki root: " + relPath);
        }
        return p;
    }

    public String readPage(String relPath) throws IOException {
        Path p = resolveSafe(relPath);
        if (!Files.exists(p)) return "";
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public void writePage(String relPath, String body) throws IOException {
        Path p = resolveSafe(relPath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.UTF_8);
    }

    public void appendPage(String relPath, String body) throws IOException {
        Path p = resolveSafe(relPath);
        Files.createDirectories(p.getParent());
        String existing = Files.exists(p) ? Files.readString(p) : "";
        String separator = existing.isEmpty() || existing.endsWith("\n\n") ? "" :
                existing.endsWith("\n") ? "\n" : "\n\n";
        Files.writeString(p, existing + separator + body);
    }

    private static final Set<String> RESERVED = Set.of("wiki/index.md", "wiki/log.md");

    public static boolean isReservedPath(String path) {
        return path != null && RESERVED.contains(path.trim());
    }

    /** Applies a batch of edits: skips reserved paths and paths outside wiki/, then dedupes by path (last wins). */
    public List<WikiEdit> applyEdits(List<WikiEdit> edits) throws IOException {
        if (edits == null) return List.of();
        ConceptDupIndex dupIndex = null;
        Map<String, WikiEdit> dedup = new LinkedHashMap<>();
        for (WikiEdit e : edits) {
            if (e == null || e.path() == null) continue;
            String path = e.path().trim();
            if (isReservedPath(path)) {
                log.warn("Ignoring LLM edit to reserved path: {} — use indexEntry/logLine instead", path);
                continue;
            }
            if (!path.startsWith("wiki/")) {
                log.warn("Ignoring edit outside wiki/: {}", path);
                continue;
            }
            String normalizedPath = normalizeWikiPath(path);
            if (!normalizedPath.equals(path)) {
                log.info("Normalizing wiki path to match qmd slug rule: {} -> {}", path, normalizedPath);
                path = normalizedPath;
                e = new WikiEdit(path, e.action(), e.body());
            }
            String normalizedBody = normalizeWikiRefsInBody(e.body());
            if (normalizedBody != null && !normalizedBody.equals(e.body())) {
                e = new WikiEdit(path, e.action(), normalizedBody);
            }
            if (path.startsWith("wiki/concepts/") && path.endsWith(".md") && !Files.exists(resolveSafe(path))) {
                if (dupIndex == null) dupIndex = buildConceptDupIndex();
                String canonical = dupIndex.findCanonical(path, e.body());
                if (canonical != null && !canonical.equals(path)) {
                    log.info("Redirecting duplicate concept edit: {} -> {} (forced to append)", path, canonical);
                    e = new WikiEdit(canonical, WikiEdit.APPEND, e.body());
                    path = canonical;
                }
            }
            dedup.put(path, e);
        }
        for (WikiEdit e : dedup.values()) applyEdit(e);
        return List.copyOf(dedup.values());
    }

    /** Near-duplicate lookup for new concept pages: match either normalized slug or normalized H1 title. */
    private record ConceptDupIndex(Map<String, String> slugMap, Map<String, String> titleMap) {
        String findCanonical(String newPath, String body) {
            String slugKey = normalizeSlug(basenameNoExt(newPath));
            String byTitle = null;
            String h1 = extractH1(body);
            if (h1 != null) {
                String titleKey = normalizeAlnum(h1);
                if (!titleKey.isEmpty()) byTitle = titleMap.get(titleKey);
            }
            String bySlug = slugMap.get(slugKey);
            // Prefer agreement; if only one signal fires, trust it; if they disagree, skip redirect to avoid conflation.
            if (bySlug != null && byTitle != null) return bySlug.equals(byTitle) ? bySlug : null;
            return bySlug != null ? bySlug : byTitle;
        }
    }

    private ConceptDupIndex buildConceptDupIndex() throws IOException {
        Path concepts = root.resolve("wiki/concepts");
        Map<String, String> slugMap = new HashMap<>();
        Map<String, String> titleMap = new HashMap<>();
        if (!Files.isDirectory(concepts)) return new ConceptDupIndex(slugMap, titleMap);
        try (var stream = Files.list(concepts)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p) || !p.toString().endsWith(".md")) continue;
                String rel = "wiki/concepts/" + p.getFileName();
                slugMap.putIfAbsent(normalizeSlug(basenameNoExt(rel)), rel);
                String h1 = extractH1(Files.readString(p, StandardCharsets.UTF_8));
                if (h1 != null) {
                    String k = normalizeAlnum(h1);
                    if (!k.isEmpty()) titleMap.putIfAbsent(k, rel);
                }
            }
        }
        return new ConceptDupIndex(slugMap, titleMap);
    }

    private static String basenameNoExt(String path) {
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        return base.endsWith(".md") ? base.substring(0, base.length() - 3) : base;
    }

    private static String normalizeSlug(String s) {
        return normalizeAlnum(s);
    }

    private static String normalizeAlnum(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** Normalize the basename of a wiki path to match qmd's handelize rule: lowercase + [^a-z0-9]+ -> '-'. */
    static String normalizeWikiPath(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        if (slash < 0) return path;
        String dir = path.substring(0, slash + 1);
        String base = path.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        String name = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot).toLowerCase() : "";
        String normalized = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isEmpty()) return path;
        return dir + normalized + ext;
    }

    private static final Pattern WIKILINK_REF = Pattern.compile("\\[\\[(wiki/[^\\]]+?\\.md)]]");

    /** Rewrite every `[[wiki/...md]]` reference inside a body so the target basename matches the normalization rule. */
    static String normalizeWikiRefsInBody(String body) {
        if (body == null) return null;
        Matcher m = WIKILINK_REF.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            String norm = normalizeWikiPath(ref);
            m.appendReplacement(sb, Matcher.quoteReplacement("[[" + norm + "]]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String extractH1(String body) {
        if (body == null) return null;
        for (String line : body.split("\\R", 20)) {
            String t = line.trim();
            if (t.startsWith("# ")) return t.substring(2).trim();
            if (t.startsWith("#\t")) return t.substring(2).trim();
        }
        return null;
    }

    public void applyEdit(WikiEdit edit) throws IOException {
        if (edit == null || edit.path() == null || edit.body() == null) return;
        if (isReservedPath(edit.path())) {
            log.warn("Refusing direct write to reserved path: {}", edit.path());
            return;
        }
        String raw = edit.action() == null ? WikiEdit.UPSERT : edit.action().trim().toLowerCase();
        switch (raw) {
            case "append", "add" -> appendPage(edit.path(), edit.body());
            case "upsert", "create", "write", "update", "replace", "overwrite", "" ->
                    writePage(edit.path(), edit.body());
            default -> {
                log.warn("Unknown edit action '{}' — treating as upsert for {}", raw, edit.path());
                writePage(edit.path(), edit.body());
            }
        }
    }

    /**
     * Deletes a wiki page and removes any index line referencing it.
     * Returns a record describing what actually happened so callers can report it.
     */
    public DeleteResult deletePage(String relPath) throws IOException {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        String path = relPath.trim();
        if (isReservedPath(path)) {
            throw new IllegalArgumentException("Refusing to delete reserved path: " + path);
        }
        if (!path.startsWith("wiki/")) {
            throw new IllegalArgumentException("Only wiki/ paths can be deleted: " + path);
        }
        Path p = resolveSafe(path);
        boolean fileRemoved = Files.deleteIfExists(p);
        int indexLinesRemoved = pruneIndexLinesFor(path);
        log.info("deletePage {}: file={}, indexLinesRemoved={}", path, fileRemoved, indexLinesRemoved);
        return new DeleteResult(path, fileRemoved, indexLinesRemoved);
    }

    public record DeleteResult(String path, boolean fileRemoved, int indexLinesRemoved) {}

    /**
     * Wipes everything under wiki/ (LLM-owned pages, index, log) and re-seeds the empty skeleton.
     * raw/ and CLAUDE.md are preserved.
     */
    public ResetResult resetWiki() throws IOException {
        Path wiki = root.resolve("wiki");
        int filesDeleted = 0;
        if (Files.isDirectory(wiki)) {
            try (var stream = Files.walk(wiki)) {
                List<Path> entries = new ArrayList<>();
                stream.forEach(entries::add);
                entries.sort((a, b) -> b.getNameCount() - a.getNameCount());
                for (Path p : entries) {
                    if (p.equals(wiki)) continue;
                    if (Files.isRegularFile(p)) filesDeleted++;
                    Files.deleteIfExists(p);
                }
            }
        }
        ensureLayout();
        log.info("resetWiki: deleted {} files under {}", filesDeleted, wiki);
        return new ResetResult(filesDeleted);
    }

    public record ResetResult(int filesDeleted) {}

    private int pruneIndexLinesFor(String path) throws IOException {
        Path index = resolveSafe("wiki/index.md");
        if (!Files.exists(index)) return 0;
        String current = Files.readString(index);
        String[] lines = current.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int removed = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(path)) { removed++; continue; }
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }
        if (removed > 0) Files.writeString(index, out.toString());
        return removed;
    }

    public void appendIndexEntry(String entry) throws IOException {
        if (entry == null || entry.isBlank()) return;
        Path index = resolveSafe("wiki/index.md");
        String current = Files.readString(index);
        String line = entry.trim();

        // Normalize any wiki path referenced in the line so it matches what we actually write to disk.
        Matcher ref = Pattern.compile("(wiki/[\\w\\-./]+\\.md)").matcher(line);
        StringBuilder normLine = new StringBuilder();
        while (ref.find()) {
            String norm = normalizeWikiPath(ref.group(1));
            ref.appendReplacement(normLine, Matcher.quoteReplacement(norm));
        }
        ref.appendTail(normLine);
        line = normLine.toString();

        // Path-aware dedup: if any existing line mentions the same wiki/ path, skip.
        Matcher m = Pattern.compile("(wiki/[\\w\\-./]+\\.md)").matcher(line);
        if (m.find()) {
            String path = m.group(1);
            for (String existing : current.split("\n")) {
                if (existing.contains(path)) return;
            }
        } else if (current.contains(line)) return;

        String section = sectionForEntry(line);
        String[] lines = current.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inserted = false;
        for (int i = 0; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
            if (!inserted && lines[i].trim().equals("## " + section)) {
                while (i + 1 < lines.length && lines[i + 1].isBlank()) {
                    i++;
                    out.append(lines[i]).append('\n');
                }
                out.append(line).append('\n');
                inserted = true;
            }
        }
        if (!inserted) {
            if (!out.toString().endsWith("\n")) out.append('\n');
            out.append(line).append('\n');
        }
        Files.writeString(index, out.toString());
    }

    private static String sectionForEntry(String line) {
        if (line.contains("wiki/entities/")) return "Entities";
        if (line.contains("wiki/concepts/")) return "Concepts";
        if (line.contains("wiki/sources/"))  return "Sources";
        return "Sources";
    }

    public void appendLog(String line) throws IOException {
        if (line == null || line.isBlank()) return;
        Path logFile = resolveSafe("wiki/log.md");
        String current = Files.exists(logFile) ? Files.readString(logFile) : "";
        String trimmed = line.endsWith("\n") ? line : line + "\n";
        Files.writeString(logFile, current + trimmed);
    }

    public String readIndex() throws IOException {
        return readPage("wiki/index.md");
    }

    public String readLog() throws IOException {
        return readPage("wiki/log.md");
    }

    public List<String> listPages() throws IOException {
        Path wiki = root.resolve("wiki");
        if (!Files.exists(wiki)) return List.of();
        List<String> pages = new ArrayList<>();
        try (var stream = Files.walk(wiki)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> pages.add(root.relativize(p).toString().replace('\\', '/')));
        }
        return pages;
    }

    public String todayLogPrefix(String kind, String title) {
        return "## [" + LocalDate.now() + "] " + kind + " | " + title;
    }

    public Path saveRawText(String subdir, String slug, String ext, String content) throws IOException {
        Path dir = root.resolve("raw/" + subdir);
        Files.createDirectories(dir);
        Path out = dir.resolve(slug + ext);
        Files.writeString(out, content, StandardCharsets.UTF_8);
        return out;
    }

    public Path saveRawBytes(String subdir, String slug, String ext, byte[] bytes) throws IOException {
        Path dir = root.resolve("raw/" + subdir);
        Files.createDirectories(dir);
        Path out = dir.resolve(slug + ext);
        Files.write(out, bytes);
        return out;
    }
}
