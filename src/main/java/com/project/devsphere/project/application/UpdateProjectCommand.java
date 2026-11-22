package com.project.devsphere.project.application;

import java.time.LocalDate;

public record UpdateProjectCommand(
        String name,
        String description,
        String objective,
        String timezone,
        LocalDate plannedStart,
        LocalDate committedDeadline,
        Integer sprintLengthDays,
        Long expectedVersion
) {}
