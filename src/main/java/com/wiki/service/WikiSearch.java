package com.wiki.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Retrieval in one LLM call: "given this index, which pages answer the question?"
 * Replaces vector search. Scales to a few hundred pages.
 */
@Service
public class WikiSearch {
    private static final Logger log = LoggerFactory.getLogger(WikiSearch.class);

    private final ChatClient chat;
    private final WikiStore store;
    private final PromptLoader prompts;
    private final int maxPages;

    public WikiSearch(ChatClient.Builder chatBuilder,
                      WikiStore store,
                      PromptLoader prompts,
                      @Value("${wiki.search.max-pages:8}") int maxPages) {
        this.chat = chatBuilder.build();
        this.store = store;
        this.prompts = prompts;
        this.maxPages = maxPages;
    }

    public List<String> selectPages(String question) throws IOException {
        String index = store.readIndex();

        BeanOutputConverter<PageList> converter = new BeanOutputConverter<>(
                new ParameterizedTypeReference<PageList>() {}, WikiAgent.LENIENT);

        String rendered = prompts.render(prompts.load("search.txt"), Map.of(
                "index", index,
                "question", question,
                "maxPages", String.valueOf(maxPages)
        )).replace("{format}", converter.getFormat());

        String raw = chat.prompt().user(rendered).call().content();
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.startsWith("[")) {
            normalized = "{\"paths\":" + normalized + "}";
        }
        PageList out = converter.convert(normalized);
        if (out == null || out.paths() == null) {
            log.warn("WikiSearch returned no usable output: {}", raw);
            return List.of();
        }
        return out.paths().stream()
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .limit(maxPages)
                .toList();
    }

    public record PageList(List<String> paths) {}
}
