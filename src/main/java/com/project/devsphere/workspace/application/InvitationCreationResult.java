package com.project.devsphere.workspace.application;

import java.time.Instant;
import java.util.UUID;

public record InvitationCreationResult(
        UUID id,
        UUID workspaceId,
        String email,
        String role,
        String status,
        Instant expiresAt,
        String token
) {
}
