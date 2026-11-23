package com.project.devsphere.sprint.application;

public record CloseSprintCommand(
        String reviewNotes,
        String retrospectiveNotes,
        Long expectedVersion
) {
    public CloseSprintCommand(Long expectedVersion) {
        this(null, null, expectedVersion);
    }
}
