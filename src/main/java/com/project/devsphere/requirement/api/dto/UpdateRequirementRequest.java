package com.project.devsphere.requirement.api.dto;

import jakarta.validation.constraints.Size;

public record UpdateRequirementRequest(
        @Size(max = 220, message = "Title must not exceed 220 characters")
        String title,

        String description,

        String acceptanceSummary,

        String changeReason,

        Long expectedVersion
) {
}
