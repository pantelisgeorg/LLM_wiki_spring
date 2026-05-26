package com.wiki.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long source text into ingest-sized chunks.
 * Prefers `##` / `#` heading boundaries; falls back to blank-line paragraphs for oversized sections.
 *
 * <p>Pre-chunked markdown: if the text contains `## 📎 Chunk` markers, each such section
 * is treated as an <b>atomic unit</b> and never combined with adjacent sections. This
 * prevents the Spring chunker from re-bundling already-curated chunks and starving
 * late content of LLM attention / output budget.
 */
public final class Chunker {
    private Chunker() {}

    public static final int DEFAULT_TARGET = 8_000;
    public static final int AUTO_THRESHOLD = 10_000;

    /** Pattern that identifies a pre-chunked section header produced by the pipeline. */
    private static final String PRE_CHUNK_MARKER = "## 📎 Chunk";

    public static boolean shouldChunk(String text) {
        return text != null && text.length() > AUTO_THRESHOLD;
    }

    /**
     * Returns true if the text was already split into semantic chunks by an upstream
     * pipeline (detected by the presence of {@value #PRE_CHUNK_MARKER} lines).
     */
    public static boolean isPreChunked(String text) {
        return text != null && text.contains(PRE_CHUNK_MARKER);
    }

    public static List<String> chunk(String text, int target) {
        if (text == null || text.isEmpty()) return List.of();
        if (text.length() <= target) return List.of(text);

        // If the file was already chunked by the pipeline, respect those boundaries.
        if (isPreChunked(text)) {
            return chunkByPreChunkMarkers(text, target);
        }

        List<String> sections = splitByHeadings(text);
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        for (String s : sections) {
            if (s.length() > target * 1.5) {
                if (cur.length() > 0) {
                    chunks.add(cur.toString());
                    cur = new StringBuilder();
                }
                chunks.addAll(splitByParagraphs(s, target));
                continue;
            }
            if (cur.length() + s.length() + 2 > target && cur.length() > 0) {
                chunks.add(cur.toString());
                cur = new StringBuilder();
            }
            if (cur.length() > 0) cur.append("\n\n");
            cur.append(s);
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    /**
     * When the text contains `## 📎 Chunk N` markers, split on those boundaries
     * and emit each section as its own chunk. If a single pre-chunk is longer than
     * the target, fall back to paragraph splitting inside that pre-chunk only.
     */
    private static List<String> chunkByPreChunkMarkers(String text, int target) {
        List<String> sections = splitByPreChunkHeadings(text);
        List<String> chunks = new ArrayList<>();

        for (String s : sections) {
            if (s.length() > target * 1.5) {
                // An individual pre-chunk is huge — split it by paragraphs, but keep
                // the pre-chunk header on the first fragment so downstream knows
                // which chunk it came from.
                chunks.addAll(splitByParagraphs(s, target));
            } else {
                chunks.add(s);
            }
        }
        return chunks;
    }

    /**
     * Split text into sections, using ANY `## ` or `# ` as a boundary.
     */
    private static List<String> splitByHeadings(String text) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            boolean isHeading = line.startsWith("# ") || line.startsWith("## ");
            if (isHeading && cur.length() > 0) {
                out.add(cur.toString().stripTrailing());
                cur = new StringBuilder();
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) out.add(cur.toString().stripTrailing());
        return out;
    }

    /**
     * Split pre-chunked text on `## 📎 Chunk` boundaries. The first section (before
     * the first marker, e.g. the system instruction block) is kept as-is.
     */
    private static List<String> splitByPreChunkHeadings(String text) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        StringBuilder cur = new StringBuilder();

        for (String line : lines) {
            boolean isPreChunk = line.trim().startsWith(PRE_CHUNK_MARKER);
            if (isPreChunk && cur.length() > 0) {
                out.add(cur.toString().stripTrailing());
                cur = new StringBuilder();
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) out.add(cur.toString().stripTrailing());
        return out;
    }

    private static List<String> splitByParagraphs(String text, int target) {
        List<String> out = new ArrayList<>();
        String[] paras = text.split("\\n{2,}");
        StringBuilder cur = new StringBuilder();
        for (String p : paras) {
            if (cur.length() + p.length() + 2 > target && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            if (cur.length() > 0) cur.append("\n\n");
            cur.append(p);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
