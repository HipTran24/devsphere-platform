package com.project.devsphere.workspace.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WorkspacePageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    @JsonProperty("content")
    public List<T> getContent() {
        return items;
    }
}
