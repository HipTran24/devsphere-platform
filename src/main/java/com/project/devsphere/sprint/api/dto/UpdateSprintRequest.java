package com.project.devsphere.sprint.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateSprintRequest(
        @Size(max = 120) String name,
        String goal,
        LocalDate startDate,
        LocalDate endDate,
        String reviewNotes,
        String retrospectiveNotes,
        @NotNull @Min(0) Long expectedVersion
) {
}
