package com.wiki.dto;

import java.util.List;

public record IngestResult(
        WikiEdit sourceSummary,
        List<WikiEdit> edits,
        String indexEntry,
        String logLine
) {
}
