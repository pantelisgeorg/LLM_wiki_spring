package com.wiki.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses `[[wiki/...]]` cross-links across all pages and exposes incoming/outgoing neighbourhoods.
 * Rebuilt on each request — cheap for a few hundred pages, avoids cache-invalidation headaches.
 */
@Service
public class LinkGraph {
    // Unicode-aware: \w in Java is ASCII-only by default, which would silently drop Greek/
    // Cyrillic/CJK paths. \p{L}\p{N} covers letters and digits in any script.
    private static final Pattern LINK = Pattern.compile("\\[\\[(wiki/[\\p{L}\\p{N}_\\-./]+\\.md)]]");

    private final WikiStore store;

    public LinkGraph(WikiStore store) {
        this.store = store;
    }

    public Graph build() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Map<String, Set<String>> in = new LinkedHashMap<>();
        List<String> pages = store.listPages();

        for (String p : pages) {
            out.putIfAbsent(p, new LinkedHashSet<>());
            in.putIfAbsent(p, new LinkedHashSet<>());
        }

        for (String p : pages) {
            if (p.equals("wiki/index.md") || p.equals("wiki/log.md")) continue;
            String body = store.readPage(p);
            Matcher m = LINK.matcher(body);
            while (m.find()) {
                String target = m.group(1);
                if (!target.equals(p)) {
                    out.get(p).add(target);
                    in.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(p);
                }
            }
        }
        return new Graph(pages, out, in);
    }

    public List<String> backlinks(String path) throws IOException {
        return new ArrayList<>(build().incoming().getOrDefault(path, Set.of()));
    }

    public record Graph(List<String> pages,
                        Map<String, Set<String>> outgoing,
                        Map<String, Set<String>> incoming) {
    }
}
