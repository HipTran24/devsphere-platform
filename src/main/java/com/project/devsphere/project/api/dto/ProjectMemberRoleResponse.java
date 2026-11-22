package com.project.devsphere.project.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectMemberRoleResponse(
        UUID id,
        UUID projectId,
        UUID projectMemberId,
        UUID userId,
        String role,
        UUID grantedBy,
        Instant grantedAt,
        Instant acceptedAt,
        Instant revokedAt
) {
}
