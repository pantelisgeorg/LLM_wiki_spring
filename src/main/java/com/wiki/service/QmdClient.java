package com.wiki.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin client for the qmd HTTP MCP server (`qmd mcp --http --daemon`).
 * Holds one long-lived MCP session and retries once if the server invalidates it.
 */
@Component
public class QmdClient {
    private static final Logger log = LoggerFactory.getLogger(QmdClient.class);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final String url;
    private final String startCommand;
    private final String reembedCommand;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile String sessionId;

    public QmdClient(
            @Value("${qmd.url:http://localhost:8181/mcp}") String url,
            @Value("${qmd.start-command:QMD_LLAMA_GPU=off qmd mcp --http --daemon}") String startCommand,
            @Value("${qmd.reembed-command:QMD_LLAMA_GPU=off qmd embed}") String reembedCommand) {
        this.url = url;
        this.startCommand = startCommand;
        this.reembedCommand = reembedCommand;
    }

    /**
     * Shell out the configured `qmd embed` command to rebuild the on-disk vector index.
     * Used after wiki resets so qmd stops returning stale hits. Returns a human-readable status
     * string; never throws — callers should treat failure as non-fatal (qmd is optional).
     */
    public String tryReembed() {
        if (reembedCommand == null || reembedCommand.isBlank()) {
            return "skipped (qmd.reembed-command is empty)";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", reembedCommand);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("qmd reembed timed out after 180s");
                return "timed out after 180s";
            }
            if (p.exitValue() != 0) {
                String tail = new String(p.getInputStream().readAllBytes()).lines()
                        .reduce((a, b) -> b).orElse("");
                log.warn("qmd reembed failed (exit {}): {}", p.exitValue(), tail);
                return "failed (exit " + p.exitValue() + "): " + tail;
            }
            log.info("qmd reembed OK");
            return "ok";
        } catch (Exception e) {
            log.warn("qmd reembed threw: {}", e.getMessage());
            return "failed: " + e.getMessage();
        }
    }

    /** Returns true if the daemon's `/health` endpoint answers 2xx within ~2s. */
    public boolean isHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(healthUrl()))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ensure the qmd daemon is up. If already healthy, no-op. Otherwise shell out the configured
     * start command via a login shell (so nvm's PATH loads) and poll `/health` until it answers.
     * Returns true if the daemon is healthy when we return, false otherwise.
     */
    public synchronized boolean tryStart() {
        if (isHealthy()) return true;
        log.info("qmd daemon not reachable at {}; starting via `{}`", url, startCommand);
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", startCommand);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("qmd start command timed out after 30s");
                return false;
            }
            if (p.exitValue() != 0) {
                String tail = new String(p.getInputStream().readAllBytes()).lines()
                        .reduce((a, b) -> b).orElse("");
                log.warn("qmd start command failed (exit {}): {}", p.exitValue(), tail);
                return false;
            }
        } catch (Exception e) {
            log.warn("qmd start failed: {}", e.getMessage());
            return false;
        }
        for (int i = 0; i < 15; i++) {
            if (isHealthy()) {
                log.info("qmd daemon is up");
                return true;
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        log.warn("qmd start command returned 0 but `/health` never answered");
        return false;
    }

    private String healthUrl() {
        // Strip the trailing /mcp (or anything past the host:port) and append /health.
        URI u = URI.create(url);
        String base = u.getScheme() + "://" + u.getAuthority();
        return base + "/health";
    }

    /** Call qmd's `query` tool. Returns the structuredContent node (has `.results[]`). */
    public JsonNode query(List<Map<String, String>> searches, String intent, Integer limit) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.set("searches", mapper.valueToTree(searches));
        if (intent != null && !intent.isBlank()) args.put("intent", intent);
        if (limit != null) args.put("limit", limit);
        JsonNode result = callTool("query", args);
        return result.path("structuredContent");
    }

    /** Call qmd's `status` tool. Returns the structuredContent node (totals + collections[]). */
    public JsonNode status() throws Exception {
        JsonNode result = callTool("status", mapper.createObjectNode());
        return result.path("structuredContent");
    }

    /** Call qmd's `get` tool. `file` may be a path like `wiki/foo.md` or a docid like `#abc123`. */
    public JsonNode getDoc(String file) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("file", file);
        JsonNode result = callTool("get", args);
        return firstResource(result);
    }

    /** Call qmd's `multi_get` tool with a glob pattern. Returns an array of `{uri, mimeType, text}`. */
    public JsonNode multiGet(String pattern) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("pattern", pattern);
        JsonNode result = callTool("multi_get", args);
        return allResources(result);
    }

    private JsonNode callTool(String name, JsonNode args) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", args);
        JsonNode resp = callWithRetry("tools/call", params);
        if (resp.has("error")) {
            throw new RuntimeException("qmd error: " + resp.get("error").toString());
        }
        JsonNode result = resp.path("result");
        if (result.path("isError").asBoolean(false)) {
            String text = result.path("content").path(0).path("text").asText("unknown tool error");
            throw new RuntimeException("qmd tool '" + name + "' error: " + text);
        }
        return result;
    }

    /** Unwrap the first resource from a tool result (used by `get`). */
    private static JsonNode firstResource(JsonNode result) {
        JsonNode c = result.path("content");
        if (!c.isArray() || c.isEmpty()) return result.path("structuredContent");
        JsonNode first = c.get(0);
        if ("resource".equals(first.path("type").asText())) return first.path("resource");
        return first;
    }

    /** Collect all resources from a tool result (used by `multi_get`). */
    private JsonNode allResources(JsonNode result) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
        JsonNode c = result.path("content");
        if (c.isArray()) {
            for (JsonNode item : c) {
                if ("resource".equals(item.path("type").asText())) arr.add(item.path("resource"));
            }
        }
        return arr;
    }

    private JsonNode callWithRetry(String method, JsonNode params) throws Exception {
        ensureSession();
        try {
            return call(method, params);
        } catch (SessionExpiredException first) {
            log.info("qmd session expired, reinitializing");
            sessionId = null;
            ensureSession();
            return call(method, params);
        }
    }

    private synchronized void ensureSession() throws Exception {
        if (sessionId != null) return;

        ObjectNode init = mapper.createObjectNode();
        init.put("jsonrpc", "2.0");
        init.put("id", nextId.getAndIncrement());
        init.put("method", "initialize");
        ObjectNode p = init.putObject("params");
        p.put("protocolVersion", PROTOCOL_VERSION);
        p.putObject("capabilities");
        ObjectNode ci = p.putObject("clientInfo");
        ci.put("name", "llm-wiki");
        ci.put("version", "0.1.0");

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(init.toString()))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("qmd initialize failed: " + res.statusCode() + " " + res.body());
        }
        String sid = res.headers().firstValue("mcp-session-id").orElseThrow(() ->
                new RuntimeException("qmd initialize returned no mcp-session-id header"));

        ObjectNode notify = mapper.createObjectNode();
        notify.put("jsonrpc", "2.0");
        notify.put("method", "notifications/initialized");
        HttpRequest nreq = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("mcp-session-id", sid)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(notify.toString()))
                .build();
        HttpResponse<String> nres = http.send(nreq, HttpResponse.BodyHandlers.ofString());
        if (nres.statusCode() / 100 != 2) {
            throw new RuntimeException("qmd initialized notification failed: " + nres.statusCode());
        }

        sessionId = sid;
        log.info("qmd MCP session initialized: {}", sid);
    }

    private JsonNode call(String method, JsonNode params) throws Exception {
        String sid = sessionId;
        if (sid == null) throw new IllegalStateException("qmd session not initialized");

        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", nextId.getAndIncrement());
        req.put("method", method);
        if (params != null) req.set("params", params);

        HttpRequest hreq = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("mcp-session-id", sid)
                // Reranker runs on CPU — give it room.
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(req.toString()))
                .build();
        HttpResponse<String> res = http.send(hreq, HttpResponse.BodyHandlers.ofString());

        int sc = res.statusCode();
        String body = res.body();
        if (sc == 404 || (sc == 400 && body != null && body.toLowerCase().contains("session"))) {
            throw new SessionExpiredException();
        }
        if (sc / 100 != 2) {
            throw new RuntimeException("qmd call failed: " + sc + " " + body);
        }
        return mapper.readTree(body);
    }

    private static class SessionExpiredException extends Exception {}
}
