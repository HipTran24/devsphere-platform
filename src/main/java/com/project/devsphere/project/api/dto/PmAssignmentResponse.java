package com.project.devsphere.project.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PmAssignmentResponse(
        UUID id,
        UUID projectId,
        UUID candidateUserId,
        UUID proposedBy,
        Instant proposedAt,
        Instant expiresAt,
        String status
) {
}
