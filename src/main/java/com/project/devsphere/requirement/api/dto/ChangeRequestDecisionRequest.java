package com.project.devsphere.requirement.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeRequestDecisionRequest(
        @NotBlank(message = "Decision is required")
        String decision,

        String decisionReason,

        Long expectedVersion
) {
}
