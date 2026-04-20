package com.wiki.dto;

import java.util.List;

public record LintReport(List<Issue> issues) {
    public record Issue(String type, String page, String description) {}
}
