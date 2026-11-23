package com.project.devsphere.monitoring.api.dto;

import java.util.List;
import java.util.UUID;

public record ControlTowerGraphResponse(
        UUID projectId,
        long projectMakespanMinutes,
        int totalTasks,
        int completedTasks,
        int criticalTasksCount,
        List<UUID> criticalPathItemIds,
        List<CpmNodeDto> nodes,
        List<CpmEdgeDto> edges
) {}
