package com.wiki.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
public class PromptLoader {
    private final Map<String, String> cache = new HashMap<>();

    public String load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    public String loadOrDefault(String name, String fallbackName) {
        if (cache.containsKey(name)) return cache.get(name);
        var resource = new ClassPathResource("prompts/" + name);
        if (!resource.exists()) return load(fallbackName);
        return load(name);
    }

    private String read(String name) {
        try {
            var resource = new ClassPathResource("prompts/" + name);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt: " + name, e);
        }
    }

    public String render(String template, Map<String, String> vars) {
        String out = template;
        for (var e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }
}
