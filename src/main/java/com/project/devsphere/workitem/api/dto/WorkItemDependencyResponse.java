package com.project.devsphere.workitem.api.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkItemDependencyResponse(
        UUID id,
        UUID projectId,
        UUID predecessorId,
        UUID successorId,
        String linkType,
        Integer lagMinutes,
        Instant createdAt
) {
}
