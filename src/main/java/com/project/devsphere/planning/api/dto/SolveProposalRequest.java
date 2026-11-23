package com.project.devsphere.planning.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record SolveProposalRequest(
        @NotNull(message = "expectedProjectVersion is required")
        @Min(value = 0, message = "expectedProjectVersion must be non-negative")
        Long expectedProjectVersion,

        @NotNull(message = "expectedProposalVersion is required")
        @Min(value = 0, message = "expectedProposalVersion must be non-negative")
        Long expectedProposalVersion,

        @Min(value = 1, message = "solverLimitSeconds must be at least 1")
        @Max(value = 30, message = "solverLimitSeconds must be at most 30")
        Integer solverLimitSeconds,

        LocalDate horizonStart,
        LocalDate horizonEnd
) {}
