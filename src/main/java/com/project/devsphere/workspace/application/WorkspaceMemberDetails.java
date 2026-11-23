package com.project.devsphere.workspace.application;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberDetails(
        UUID userId,
        String email,
        String displayName,
        String role,
        String status,
        Instant joinedAt
) {
}
