package com.project.devsphere.administration.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record AdminPageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Instant effectiveFrom,
        Instant effectiveTo
) {
    @JsonProperty("content")
    public List<T> getContent() {
        return items;
    }
}
