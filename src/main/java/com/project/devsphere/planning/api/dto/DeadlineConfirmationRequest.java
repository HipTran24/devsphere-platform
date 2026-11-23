package com.project.devsphere.planning.api.dto;

import jakarta.validation.constraints.NotNull;

public record DeadlineConfirmationRequest(
        @NotNull(message = "Expected proposal version is required")
        Long expectedProposalVersion,

        @NotNull(message = "Expected project version is required")
        Long expectedProjectVersion,

        boolean confirmation
) {
}
