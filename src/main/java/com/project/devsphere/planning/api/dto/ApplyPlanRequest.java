package com.project.devsphere.planning.api.dto;

import jakarta.validation.constraints.NotNull;

public record ApplyPlanRequest(
        @NotNull(message = "Expected project version is required")
        Long expectedProjectVersion,

        @NotNull(message = "Expected proposal version is required")
        Long expectedProposalVersion
) {
}
