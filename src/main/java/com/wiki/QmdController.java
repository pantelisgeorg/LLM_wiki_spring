package com.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.wiki.service.QmdClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/qmd")
public class QmdController {
    private static final Logger log = LoggerFactory.getLogger(QmdController.class);

    private final QmdClient qmd;

    public QmdController(QmdClient qmd) {
        this.qmd = qmd;
    }

    public record QueryRequest(String query, String intent, Integer limit) {}
    public record MultiGetRequest(String pattern) {}

    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody QueryRequest req) {
        if (req == null || req.query() == null || req.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query is required"));
        }
        try {
            List<Map<String, String>> searches = List.of(
                    Map.of("type", "lex", "query", req.query()),
                    Map.of("type", "vec", "query", req.query())
            );
            String intent = (req.intent() == null || req.intent().isBlank()) ? req.query() : req.intent();
            int limit = req.limit() == null ? 8 : Math.max(1, Math.min(20, req.limit()));
            JsonNode structured = qmd.query(searches, intent, limit);
            return ResponseEntity.ok(structured);
        } catch (Exception e) {
            return fail("qmd query failed", e);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        try {
            return ResponseEntity.ok(qmd.status());
        } catch (Exception e) {
            return fail("qmd status failed", e);
        }
    }

    @GetMapping("/get")
    public ResponseEntity<?> get(@RequestParam String file) {
        if (file == null || file.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        try {
            return ResponseEntity.ok(qmd.getDoc(file));
        } catch (Exception e) {
            return fail("qmd get failed", e);
        }
    }

    @PostMapping("/multi-get")
    public ResponseEntity<?> multiGet(@RequestBody MultiGetRequest req) {
        if (req == null || req.pattern() == null || req.pattern().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "pattern is required"));
        }
        try {
            return ResponseEntity.ok(qmd.multiGet(req.pattern()));
        } catch (Exception e) {
            return fail("qmd multi-get failed", e);
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> start() {
        boolean ok = qmd.tryStart();
        if (ok) return ResponseEntity.ok(Map.of("started", true));
        return ResponseEntity.status(503).body(Map.of(
                "started", false,
                "error", "qmd daemon did not come up — check that `qmd` is installed and on PATH (check the server log for details)"
        ));
    }

    private ResponseEntity<?> fail(String where, Exception e) {
        log.error(where, e);
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        return ResponseEntity.internalServerError().body(Map.of("error", msg));
    }
}
