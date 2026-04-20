package com.wiki;

import com.wiki.dto.IngestResult;
import com.wiki.dto.LintReport;
import com.wiki.dto.LoadedSource;
import com.wiki.dto.QueryAnswer;
import com.wiki.service.Chunker;
import com.wiki.service.LinkGraph;
import com.wiki.service.RawSourceLoader;
import com.wiki.service.WikiAgent;
import com.wiki.service.WikiSearch;
import com.wiki.service.WikiStore;
import org.commonmark.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class WikiController {
    private static final Logger log = LoggerFactory.getLogger(WikiController.class);

    private final WikiStore store;
    private final RawSourceLoader loader;
    private final WikiAgent agent;
    private final WikiSearch search;
    private final LinkGraph linkGraph;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @Value("${wiki.ingest.chunk-gap-ms:0}")
    private long chunkGapMs;
    @Value("${wiki.ingest.reset-every-n-chunks:0}")
    private int resetEveryN;
    @Value("${wiki.ingest.reset-command:}")
    private String resetCommand;

    public WikiController(WikiStore store, RawSourceLoader loader, WikiAgent agent,
                          WikiSearch search, LinkGraph linkGraph) {
        this.store = store;
        this.loader = loader;
        this.agent = agent;
        this.search = search;
        this.linkGraph = linkGraph;
    }

    public record IngestRequest(String path, String url, Integer startChunk) {}
    public record QueryRequest(String question) {}

    @PostMapping(value = "/ingest", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ingest(@RequestBody IngestRequest req) {
        SseEmitter emitter = new SseEmitter();
        int start = req.startChunk() == null ? 1 : Math.max(1, req.startChunk());
        pool.submit(() -> runIngest(emitter, req, null, null, start));
        return emitter;
    }

    @PostMapping(value = "/ingest/upload", produces = MediaType.TEXT_EVENT_STREAM_VALUE,
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SseEmitter ingestUpload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "startChunk", required = false) Integer startChunk) {
        SseEmitter emitter = new SseEmitter();
        try {
            byte[] bytes = file.getBytes();
            String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
            int start = startChunk == null ? 1 : Math.max(1, startChunk);
            pool.submit(() -> runIngest(emitter, null, bytes, name, start));
        } catch (Exception e) {
            fail(emitter, e);
        }
        return emitter;
    }

    private void runIngest(SseEmitter emitter, IngestRequest req, byte[] bytes, String name, int startChunk) {
        try {
            send(emitter, "status", "loading source");
            LoadedSource source;
            if (bytes != null) {
                source = loader.loadFromBytes(name, bytes);
            } else if (req != null && req.url() != null && !req.url().isBlank()) {
                source = loader.loadFromUrl(req.url());
            } else if (req != null && req.path() != null && !req.path().isBlank()) {
                source = loader.loadFromFile(Paths.get(req.path()));
            } else {
                throw new IllegalArgumentException("Provide path, url, or upload");
            }
            send(emitter, "status", "loaded: " + source.title() + " (" + source.text().length() + " chars)");

            if (!Chunker.shouldChunk(source.text())) {
                send(emitter, "status", "calling LLM (this can take 30–90s on local models)");
                IngestResult result = agent.ingest(source);
                int editCount = result.edits() == null ? 0 : result.edits().size();
                send(emitter, "status", "applied " + editCount + " edits + 1 source summary");
                send(emitter, "result", result);
                emitter.complete();
                return;
            }

            // Chunked ingest for long sources
            List<String> chunks = Chunker.chunk(source.text(), Chunker.DEFAULT_TARGET);
            send(emitter, "status", "chunked into " + chunks.size() + " parts (~"
                    + (source.text().length() / chunks.size()) + " chars each)");
            if (startChunk > chunks.size()) {
                throw new IllegalArgumentException("startChunk=" + startChunk
                        + " exceeds total chunks (" + chunks.size() + ")");
            }
            if (startChunk > 1) {
                send(emitter, "status", "resuming from chunk " + startChunk + "/" + chunks.size()
                        + " (skipping " + (startChunk - 1) + " earlier chunks)");
            }

            String baseSlug = slugifyTitle(source.title());
            int totalEdits = 0;
            IngestResult last = null;
            for (int i = startChunk - 1; i < chunks.size(); i++) {
                int n = i + 1;
                String chunkTitle = source.title() + " (part " + n + "/" + chunks.size() + ")";
                LoadedSource chunkSource = new LoadedSource(chunkTitle, chunks.get(i), source.sourcePath());
                String canonicalSourcePath = "wiki/sources/" + baseSlug + "-part" + n + ".md";
                send(emitter, "status", "chunk " + n + "/" + chunks.size() + ": calling LLM");
                IngestResult r = agent.ingest(chunkSource, canonicalSourcePath);
                int e = r.edits() == null ? 0 : r.edits().size();
                totalEdits += e;
                send(emitter, "status", "chunk " + n + "/" + chunks.size() + ": applied " + e + " edits");
                last = r;

                boolean isLast = (n == chunks.size());
                if (!isLast) {
                    if (resetEveryN > 0 && resetCommand != null && !resetCommand.isBlank()
                            && n % resetEveryN == 0) {
                        send(emitter, "status", "resetting LLM server after chunk " + n + "…");
                        resetModelServer(emitter);
                    } else if (chunkGapMs > 0) {
                        Thread.sleep(chunkGapMs);
                    }
                }
            }
            send(emitter, "status", "done: " + totalEdits + " edits across " + chunks.size() + " chunks");
            send(emitter, "result", last);
            emitter.complete();
        } catch (Exception e) {
            fail(emitter, e);
        }
    }

    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(@RequestBody QueryRequest req) {
        SseEmitter emitter = new SseEmitter();
        pool.submit(() -> {
            try {
                send(emitter, "status", "selecting pages from index");
                List<String> pages = search.selectPages(req.question());
                send(emitter, "status", "selected " + pages.size() + " pages: " + pages);
                if (pages.isEmpty()) {
                    send(emitter, "result", new QueryAnswer(
                            "The wiki index returned no relevant pages for that question.",
                            List.of()));
                    emitter.complete();
                    return;
                }
                send(emitter, "status", "composing answer");
                QueryAnswer answer = agent.query(req.question(), pages);
                send(emitter, "result", answer);
                emitter.complete();
            } catch (Exception e) {
                fail(emitter, e);
            }
        });
        return emitter;
    }

    @PostMapping(value = "/lint", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter lint() {
        SseEmitter emitter = new SseEmitter();
        pool.submit(() -> {
            try {
                send(emitter, "status", "scanning wiki");
                LintReport report = agent.lint();
                int n = report.issues() == null ? 0 : report.issues().size();
                send(emitter, "status", "found " + n + " issues");
                send(emitter, "result", report);
                emitter.complete();
            } catch (Exception e) {
                fail(emitter, e);
            }
        });
        return emitter;
    }

    @GetMapping("/wiki/tree")
    public List<String> tree() throws Exception {
        return store.listPages();
    }

    @GetMapping("/wiki/backlinks")
    public List<String> backlinks(@RequestParam String path) throws Exception {
        return linkGraph.backlinks(path);
    }

    @GetMapping("/wiki/graph")
    public Map<String, Object> graph() throws Exception {
        LinkGraph.Graph g = linkGraph.build();
        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        for (String p : g.pages()) {
            if (p.equals("wiki/index.md") || p.equals("wiki/log.md")) continue;
            String group = p.startsWith("wiki/entities/") ? "entity"
                    : p.startsWith("wiki/concepts/") ? "concept"
                    : p.startsWith("wiki/sources/")  ? "source" : "other";
            nodes.add(Map.of(
                    "id", p,
                    "label", labelOf(p),
                    "group", group,
                    "degree", g.incoming().getOrDefault(p, Set.of()).size()
                            + g.outgoing().getOrDefault(p, Set.of()).size()
            ));
            nodeIds.add(p);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (var e : g.outgoing().entrySet()) {
            if (!nodeIds.contains(e.getKey())) continue;
            for (String target : e.getValue()) {
                if (!nodeIds.contains(target)) continue;
                edges.add(Map.of("from", e.getKey(), "to", target));
            }
        }
        return Map.of("nodes", nodes, "edges", edges);
    }

    /** Derives a kebab-case base slug from a source title, stripping any `(part N/M)` marker. */
    static String slugifyTitle(String title) {
        if (title == null) return "source";
        String t = title.toLowerCase()
                .replaceAll("\\(\\s*part\\s*\\d+\\s*/\\s*\\d+\\s*\\)", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return t.isEmpty() ? "source" : t;
    }

    private static String labelOf(String path) {
        int slash = path.lastIndexOf('/');
        String file = slash < 0 ? path : path.substring(slash + 1);
        return file.endsWith(".md") ? file.substring(0, file.length() - 3) : file;
    }

    @GetMapping("/wiki/page")
    public ResponseEntity<Map<String, String>> page(@RequestParam String path) throws Exception {
        Path p = store.resolveSafe(path);
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        String md = Files.readString(p);
        String html = MarkdownRenderer.render(md);
        return ResponseEntity.ok(Map.of("markdown", md, "html", html, "path", path));
    }

    @DeleteMapping("/wiki/page")
    public ResponseEntity<?> deletePage(@RequestParam String path) {
        try {
            WikiStore.DeleteResult r = store.deletePage(path);
            if (!r.fileRemoved() && r.indexLinesRemoved() == 0) {
                return ResponseEntity.status(404).body(Map.of("error", "no such page or index entry: " + path));
            }
            return ResponseEntity.ok(Map.of(
                    "path", r.path(),
                    "fileRemoved", r.fileRemoved(),
                    "indexLinesRemoved", r.indexLinesRemoved()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("deletePage failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Shell out to `lms unload && lms load …` (or whatever the operator configured) between chunks
     * to drop LM Studio's per-slot scheduler state. Observed on 2026-04-19: after ~3 back-to-back
     * long generations, LM Studio accepts further POSTs but never dispatches them to a slot.
     */
    private void resetModelServer(SseEmitter emitter) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", resetCommand);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                send(emitter, "status", "reset command timed out after 120s — continuing");
                return;
            }
            if (p.exitValue() != 0) {
                String tail = new String(p.getInputStream().readAllBytes()).lines()
                        .reduce((a, b) -> b).orElse("");
                send(emitter, "status", "reset command failed (exit " + p.exitValue() + "): " + tail);
                return;
            }
            send(emitter, "status", "model server reset OK");
            // brief settle before next call so the freshly loaded server is ready
            Thread.sleep(2000);
        } catch (Exception e) {
            log.warn("Model server reset failed", e);
            send(emitter, "status", "reset failed: " + e.getMessage() + " — continuing");
        }
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("SSE send failed ({}): {}", event, e.getMessage());
        }
    }

    private static void fail(SseEmitter emitter, Exception e) {
        log.error("SSE task failed", e);
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        String json = "{\"message\":\"" + msg.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"}";
        try {
            emitter.send(SseEmitter.event().name("error").data(json, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {}
        emitter.complete();
    }

    /** Minimal CommonMark renderer; lives here to avoid an extra class file. */
    static final class MarkdownRenderer {
        private static final Parser PARSER = Parser.builder().build();
        private static final org.commonmark.renderer.html.HtmlRenderer RENDERER =
                org.commonmark.renderer.html.HtmlRenderer.builder().build();

        static String render(String md) {
            return RENDERER.render(PARSER.parse(md));
        }
    }
}
