package com.wiki.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Replaces qmd for Consolidate's neighbor-finding. Holds one embedding per entity/concept page
 * in-memory; refreshed after every ingest and on startup. Uses OpenAI text-embedding-3-small
 * via Spring AI's autoconfigured EmbeddingModel bean.
 */
@Service
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel model;
    private final WikiStore store;
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();

    public EmbeddingService(EmbeddingModel model, WikiStore store) {
        this.model = model;
        this.store = store;
    }

    @PostConstruct
    public void initialLoad() {
        try {
            String summary = refresh();
            log.info("Initial embedding load: {}", summary);
        } catch (Exception e) {
            log.warn("Initial embedding load failed: {} — neighbors will be empty until next refresh", e.getMessage());
        }
    }

    /**
     * Re-syncs the in-memory vector map against the filesystem: embeds any entity/concept page
     * that's missing a vector, drops vectors for pages that no longer exist. One batched OpenAI
     * call covers all new pages. Returns a one-line status string.
     */
    public synchronized String refresh() {
        try {
            // Embed every wiki/ page except the system-managed catalog (index.md) and log.
            List<String> diskPaths = store.listPages().stream()
                    .filter(p -> p.startsWith("wiki/"))
                    .filter(p -> !p.equals("wiki/index.md") && !p.equals("wiki/log.md"))
                    .toList();

            List<String> toEmbed = new ArrayList<>();
            for (String path : diskPaths) {
                if (!vectors.containsKey(path)) toEmbed.add(path);
            }
            int removed = 0;
            for (String existing : List.copyOf(vectors.keySet())) {
                if (!diskPaths.contains(existing)) { vectors.remove(existing); removed++; }
            }

            if (toEmbed.isEmpty()) {
                return "no changes (" + vectors.size() + " vectors, " + removed + " removed)";
            }

            List<String> seeds = new ArrayList<>(toEmbed.size());
            for (String path : toEmbed) {
                String body = store.readPage(path);
                seeds.add(WikiAgent.seedTextFor(body));
            }

            EmbeddingResponse resp = model.call(new EmbeddingRequest(seeds, null));
            var results = resp.getResults();
            if (results.size() != toEmbed.size()) {
                log.warn("Embedding response size {} != requested {}", results.size(), toEmbed.size());
            }
            int n = Math.min(results.size(), toEmbed.size());
            for (int i = 0; i < n; i++) {
                vectors.put(toEmbed.get(i), toFloats(results.get(i).getOutput()));
            }
            return "embedded " + n + " new, removed " + removed + ", total " + vectors.size();
        } catch (IOException e) {
            log.warn("Embedding refresh failed: {}", e.getMessage());
            return "failed: " + e.getMessage();
        } catch (Exception e) {
            log.warn("Embedding refresh threw: {}", e.getMessage());
            return "failed: " + e.getMessage();
        }
    }

    /** Drop all in-memory vectors. Called from /api/wiki/reset. */
    public synchronized void clear() {
        vectors.clear();
        log.info("Embedding cache cleared");
    }

    /**
     * Cosine-nearest pages to {@code anchorPath}, filtered to those matching {@code filter}
     * (e.g. only concepts) and scoring at least {@code floor}. Anchor itself is excluded.
     */
    public List<Result> findNeighbors(String anchorPath, double floor, int topN, Predicate<String> filter) {
        float[] anchor = vectors.get(anchorPath);
        if (anchor == null) {
            log.debug("No vector for anchor {} (cache may need refresh)", anchorPath);
            return List.of();
        }
        List<Result> all = new ArrayList<>();
        for (var entry : vectors.entrySet()) {
            String path = entry.getKey();
            if (path.equals(anchorPath)) continue;
            if (filter != null && !filter.test(path)) continue;
            double score = cosine(anchor, entry.getValue());
            if (score < floor) continue;
            all.add(new Result(path, score));
        }
        all.sort(Comparator.comparingDouble(Result::score).reversed());
        return all.size() > topN ? all.subList(0, topN) : all;
    }

    public int size() {
        return vectors.size();
    }

    /**
     * Embed a free-text query, return the top {@code topN} pages by cosine.
     * Used by the QMD button — no floor, returns the best matches even if scores are low.
     */
    public List<Result> search(String query, int topN) {
        if (query == null || query.isBlank() || vectors.isEmpty()) return List.of();
        try {
            EmbeddingResponse resp = model.call(new EmbeddingRequest(List.of(query), null));
            if (resp.getResults().isEmpty()) return List.of();
            float[] q = resp.getResults().get(0).getOutput();
            List<Result> all = new ArrayList<>(vectors.size());
            for (var entry : vectors.entrySet()) {
                all.add(new Result(entry.getKey(), cosine(q, entry.getValue())));
            }
            all.sort(Comparator.comparingDouble(Result::score).reversed());
            return all.size() > topN ? all.subList(0, topN) : all;
        } catch (Exception e) {
            log.warn("search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public record Result(String path, double score) {}

    private static float[] toFloats(float[] embedding) {
        return embedding;
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0 : dot / denom;
    }
}
