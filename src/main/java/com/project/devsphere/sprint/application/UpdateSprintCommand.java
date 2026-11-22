package com.project.devsphere.sprint.application;

import java.time.LocalDate;

public record UpdateSprintCommand(
        String name,
        String goal,
        LocalDate startDate,
        LocalDate endDate,
        String reviewNotes,
        String retrospectiveNotes,
        Long expectedVersion
) {
    public UpdateSprintCommand(String name, String goal, LocalDate startDate, LocalDate endDate, Long expectedVersion) {
        this(name, goal, startDate, endDate, null, null, expectedVersion);
    }
}
