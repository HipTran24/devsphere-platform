package com.project.devsphere.project.application;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateProjectCommand(
        String name,
        String description,
        String objective,
        String timezone,
        LocalDate plannedStart,
        LocalDate committedDeadline,
        Integer sprintLengthDays,
        UUID deadlineApproverId,
        Long expectedVersion
) {
    public UpdateProjectCommand(
            String name,
            String description,
            String objective,
            String timezone,
            LocalDate plannedStart,
            LocalDate committedDeadline,
            Integer sprintLengthDays,
            Long expectedVersion
    ) {
        this(name, description, objective, timezone, plannedStart, committedDeadline, sprintLengthDays, null, expectedVersion);
    }
}
