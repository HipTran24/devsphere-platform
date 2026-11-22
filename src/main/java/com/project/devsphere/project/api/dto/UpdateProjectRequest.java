package com.project.devsphere.project.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record UpdateProjectRequest(
        @Size(min = 1, max = 160, message = "Project name must be between 1 and 160 characters")
        String name,

        String description,
        String objective,
        String timezone,
        LocalDate plannedStart,
        LocalDate committedDeadline,

        @Min(value = 1, message = "Sprint length must be at least 1 day")
        @Max(value = 90, message = "Sprint length must be at most 90 days")
        Integer sprintLengthDays,

        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be non-negative")
        Long expectedVersion
) {
}
