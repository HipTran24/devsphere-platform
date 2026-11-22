package com.project.devsphere.workspace.api.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberDetailsResponse(
        UUID id,
        UUID workspaceId,
        UUID userId,
        String email,
        String displayName,
        String role,
        String status,
        Instant joinedAt
) {
}
