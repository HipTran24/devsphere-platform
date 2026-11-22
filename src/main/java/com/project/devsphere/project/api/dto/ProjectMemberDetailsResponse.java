package com.project.devsphere.project.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectMemberDetailsResponse(
        UUID id,
        UUID projectId,
        UUID workspaceId,
        UUID userId,
        String status,
        Instant joinedAt,
        List<String> roles
) {
}
