package com.project.devsphere.workspace.application;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceInvitationSummary(
        UUID id,
        UUID workspaceId,
        String email,
        UUID invitedBy,
        String role,
        String status,
        Instant expiresAt,
        Instant acceptedAt,
        Instant createdAt
) {
}
