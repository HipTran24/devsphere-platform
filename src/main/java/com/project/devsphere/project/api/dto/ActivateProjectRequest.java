package com.project.devsphere.project.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ActivateProjectRequest(
        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be non-negative")
        Long expectedVersion
) {
}
