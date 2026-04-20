package com.wiki.dto;

import java.util.List;

public record QueryAnswer(String markdown, List<String> citations) {
}
