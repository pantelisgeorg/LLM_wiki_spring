package com.wiki.dto;

import java.util.List;

public record ConsolidateReport(
        boolean previewMode,
        int pagesProcessed,
        int pagesSkipped,
        List<WikiEdit> proposedEdits,
        List<PerPage> perPage,
        boolean applied
) {
    public record PerPage(
            String path,
            List<String> neighbors,
            List<WikiEdit> edits,
            String rationale
    ) {}
}
