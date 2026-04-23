package com.wiki.dto;

import java.util.List;

public record ConsolidateResult(List<WikiEdit> edits, String rationale) {}
