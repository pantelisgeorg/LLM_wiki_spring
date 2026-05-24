package com.wiki;

import com.wiki.service.EmbeddingService;
import com.wiki.service.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backs the QMD / Fetch buttons. The path stayed `/api/qmd/*` to avoid touching the frontend,
 * but qmd the daemon is no longer involved — search uses OpenAI embeddings via EmbeddingService
 * and Fetch reads files directly off disk.
 */
@RestController
@RequestMapping("/api/qmd")
public class QmdController {
    private static final Logger log = LoggerFactory.getLogger(QmdController.class);

    private final EmbeddingService embeddings;
    private final WikiStore store;

    public QmdController(EmbeddingService embeddings, WikiStore store) {
        this.embeddings = embeddings;
        this.store = store;
    }

    public record QueryRequest(String query, String intent, Integer limit) {}
    public record MultiGetRequest(String pattern) {}

    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody QueryRequest req) {
        if (req == null || req.query() == null || req.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query is required"));
        }
        try {
            int limit = req.limit() == null ? 8 : Math.max(1, Math.min(20, req.limit()));
            List<EmbeddingService.Result> hits = embeddings.search(req.query(), limit);
            List<Map<String, Object>> results = new ArrayList<>(hits.size());
            for (EmbeddingService.Result h : hits) {
                Map<String, Object> row = new HashMap<>();
                row.put("file", h.path());
                row.put("score", h.score());
                row.put("title", titleFor(h.path()));
                row.put("snippet", snippetFor(h.path()));
                results.add(row);
            }
            return ResponseEntity.ok(Map.of("results", results));
        } catch (Exception e) {
            return fail("search failed", e);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        try {
            int total = embeddings.size();
            Map<String, Object> collection = new HashMap<>();
            collection.put("name", "wiki");
            collection.put("documents", total);
            collection.put("path", store.root().toString());
            return ResponseEntity.ok(Map.of(
                    "totalDocuments", total,
                    "collections", List.of(collection)
            ));
        } catch (Exception e) {
            return fail("status failed", e);
        }
    }

    @GetMapping("/get")
    public ResponseEntity<?> get(@RequestParam String file) {
        if (file == null || file.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        try {
            String path = stripScheme(file).trim();
            if (path.startsWith("#")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "docid lookup (#abc123) is no longer supported — use a path like wiki/entities/foo.md"));
            }
            if (!path.startsWith("wiki/") && !path.startsWith("raw/")) {
                return ResponseEntity.badRequest().body(Map.of("error", "path must start with wiki/ or raw/: " + path));
            }
            Path p = store.resolveSafe(path);
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                return ResponseEntity.status(404).body(Map.of("error", "no such file: " + path));
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of(
                    "uri", "qmd://" + path,
                    "mimeType", "text/markdown",
                    "text", text
            ));
        } catch (Exception e) {
            return fail("get failed", e);
        }
    }

    @PostMapping("/multi-get")
    public ResponseEntity<?> multiGet(@RequestBody MultiGetRequest req) {
        if (req == null || req.pattern() == null || req.pattern().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "pattern is required"));
        }
        try {
            List<String> patterns = new ArrayList<>();
            for (String part : req.pattern().split(",")) {
                String s = part.trim();
                if (!s.isEmpty()) patterns.add(stripScheme(s));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (String pat : patterns) {
                collectMatches(pat, out, 200 - out.size());
                if (out.size() >= 200) break;
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return fail("multi-get failed", e);
        }
    }

    /** Kept for frontend compatibility — used to start the qmd daemon; now a no-op. */
    @PostMapping("/start")
    public ResponseEntity<?> start() {
        return ResponseEntity.ok(Map.of("started", true, "note", "embeddings service is in-process; no daemon needed"));
    }

    private void collectMatches(String pattern, List<Map<String, Object>> out, int cap) throws IOException {
        if (cap <= 0) return;
        FileSystem fs = FileSystems.getDefault();
        PathMatcher matcher = fs.getPathMatcher("glob:" + pattern);
        Path root = store.root();
        try (var stream = Files.walk(root)) {
            var iter = stream.iterator();
            int taken = 0;
            while (iter.hasNext() && taken < cap) {
                Path p = iter.next();
                if (!Files.isRegularFile(p)) continue;
                Path rel = root.relativize(p);
                String relStr = rel.toString().replace('\\', '/');
                if (!matcher.matches(fs.getPath(relStr))) continue;
                String text;
                try {
                    text = Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue; // binary or unreadable — skip
                }
                Map<String, Object> row = new HashMap<>();
                row.put("uri", "qmd://" + relStr);
                row.put("mimeType", "text/markdown");
                row.put("text", text);
                out.add(row);
                taken++;
            }
        }
    }

    private static String stripScheme(String s) {
        return s.startsWith("qmd://") ? s.substring("qmd://".length()) : s;
    }

    private static final Pattern H1 = Pattern.compile("^#\\s+(.+?)\\s*$", Pattern.MULTILINE);

    private String titleFor(String path) {
        try {
            String body = store.readPage(path);
            if (body == null) return basename(path);
            Matcher m = H1.matcher(body);
            if (m.find()) return m.group(1).trim();
            return basename(path);
        } catch (Exception e) {
            return basename(path);
        }
    }

    private String snippetFor(String path) {
        try {
            String body = store.readPage(path);
            if (body == null) return "";
            String collapsed = body.replaceAll("\\s+", " ").trim();
            return collapsed.length() > 220 ? collapsed.substring(0, 220) + "…" : collapsed;
        } catch (Exception e) {
            return "";
        }
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        String base = slash < 0 ? path : path.substring(slash + 1);
        return base.endsWith(".md") ? base.substring(0, base.length() - 3) : base;
    }

    private ResponseEntity<?> fail(String where, Exception e) {
        log.error(where, e);
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        return ResponseEntity.internalServerError().body(Map.of("error", msg));
    }
}
