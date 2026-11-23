package com.project.devsphere.project.api.dto;

import com.project.devsphere.project.domain.Project;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String projectKey,
        String name,
        String description,
        String objective,
        Project.Status status,
        String timezone,
        LocalDate plannedStart,
        LocalDate committedDeadline,
        Integer sprintLengthDays,
        UUID deadlineApproverId,
        Long nextItemNumber,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public ProjectResponse(
            UUID id,
            UUID workspaceId,
            String projectKey,
            String name,
            String description,
            String objective,
            Project.Status status,
            String timezone,
            LocalDate plannedStart,
            LocalDate committedDeadline,
            Integer sprintLengthDays,
            Long nextItemNumber,
            Instant createdAt,
            Instant updatedAt,
            Long version
    ) {
        this(id, workspaceId, projectKey, name, description, objective, status, timezone, plannedStart, committedDeadline, sprintLengthDays, null, nextItemNumber, createdAt, updatedAt, version);
    }
}
