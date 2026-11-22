package com.project.devsphere.sprint.api.dto;

import java.util.List;
import java.util.UUID;

public record SprintItemsRequest(
        String action,
        List<UUID> itemIds,
        List<UUID> workItemIds
) {
    public List<UUID> resolvedItemIds() {
        if (itemIds != null && !itemIds.isEmpty()) {
            return itemIds;
        }
        if (workItemIds != null && !workItemIds.isEmpty()) {
            return workItemIds;
        }
        return List.of();
    }
}
