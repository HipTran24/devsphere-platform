package com.project.devsphere.sprint.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateSprintRequest(
        @NotBlank @Size(max = 120) String name,
        String goal,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate
) {
}
