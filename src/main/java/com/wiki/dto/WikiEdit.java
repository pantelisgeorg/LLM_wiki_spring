package com.wiki.dto;

public record WikiEdit(String path, String action, String body) {
    public static final String UPSERT = "upsert";
    public static final String APPEND = "append";
}
