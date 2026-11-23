package com.project.devsphere.project.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateProjectRequest(
        @NotBlank(message = "Project key is required")
        @Pattern(regexp = "^[A-Z0-9]{2,16}$", message = "Project key must consist of 2 to 16 uppercase alphanumeric characters")
        String projectKey,

        @NotBlank(message = "Project name is required")
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

        Boolean selfAssignPm
) {
    public CreateProjectRequest {
        if (timezone == null || timezone.isBlank()) {
            timezone = "Asia/Ho_Chi_Minh";
        }
        if (sprintLengthDays == null) {
            sprintLengthDays = 14;
        }
        if (selfAssignPm == null) {
            selfAssignPm = true;
        }
    }
}
