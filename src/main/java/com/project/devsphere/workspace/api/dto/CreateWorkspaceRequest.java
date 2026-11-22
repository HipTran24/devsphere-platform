package com.project.devsphere.workspace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
        @NotBlank(message = "Workspace name is required")
        @Size(min = 1, max = 160, message = "Workspace name must be between 1 and 160 characters")
        String name,

        @NotBlank(message = "Workspace slug is required")
        @Size(min = 3, max = 80, message = "Workspace slug must be between 3 and 80 characters")
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "Slug must contain only lowercase alphanumeric characters and single hyphens")
        String slug,

        String description
) {
}
