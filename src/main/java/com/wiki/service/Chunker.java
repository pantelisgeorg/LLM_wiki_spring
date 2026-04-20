package com.wiki.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long source text into ingest-sized chunks.
 * Prefers `##` / `#` heading boundaries; falls back to blank-line paragraphs for oversized sections.
 */
public final class Chunker {
    private Chunker() {}

    public static final int DEFAULT_TARGET = 8_000;
    public static final int AUTO_THRESHOLD = 10_000;

    public static boolean shouldChunk(String text) {
        return text != null && text.length() > AUTO_THRESHOLD;
    }

    public static List<String> chunk(String text, int target) {
        if (text == null || text.isEmpty()) return List.of();
        if (text.length() <= target) return List.of(text);

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
