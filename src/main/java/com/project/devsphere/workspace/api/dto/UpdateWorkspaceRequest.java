package com.project.devsphere.workspace.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateWorkspaceRequest(
        @Size(min = 1, max = 160, message = "Workspace name must be between 1 and 160 characters")
        String name,

        String description,

        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be non-negative")
        Long expectedVersion
) {
}
