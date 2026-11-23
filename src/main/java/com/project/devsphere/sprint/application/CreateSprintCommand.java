package com.project.devsphere.sprint.application;

import java.time.LocalDate;

public record CreateSprintCommand(
        String name,
        String goal,
        LocalDate startDate,
        LocalDate endDate
) {
}
