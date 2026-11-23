package com.project.devsphere.sprint.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CloseSprintRequest(
        String reviewNotes,
        String retrospectiveNotes,
        @NotNull @Min(0) Long expectedVersion
) {
}
