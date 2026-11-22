package com.project.devsphere.requirement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateRequirementRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 220, message = "Title must not exceed 220 characters")
        String title,

        String description,

        String acceptanceSummary,

        UUID parentRequirementId
) {
}
