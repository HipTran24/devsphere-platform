package com.project.devsphere.project.application;

import java.time.Instant;
import java.util.UUID;

public record PmAssignmentDetails(
        UUID id,
        UUID candidateUserId,
        UUID proposedBy,
        Instant proposedAt,
        Instant expiresAt,
        String status
) {
}
