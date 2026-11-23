package com.project.devsphere.workspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkspaceInvitationResponse(
        UUID id,
        UUID workspaceId,
        String email,
        UUID invitedBy,
        String role,
        String status,
        Instant expiresAt,
        Instant acceptedAt,
        String token
) {
}
