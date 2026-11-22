package com.project.devsphere.sprint.application;

import java.util.List;
import java.util.UUID;

public record SprintItemAssignmentCommand(
        String action,
        List<UUID> itemIds,
        Long expectedVersion
) {
    public SprintItemAssignmentCommand(List<UUID> itemIds, Long expectedVersion) {
        this("ADD", itemIds, expectedVersion);
    }

    public SprintItemAssignmentCommand(String action, List<UUID> itemIds) {
        this(action, itemIds, null);
    }

    public SprintItemAssignmentCommand(List<UUID> itemIds) {
        this("ADD", itemIds, null);
    }
}
