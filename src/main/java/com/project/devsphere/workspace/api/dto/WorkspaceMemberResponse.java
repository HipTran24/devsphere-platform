package com.project.devsphere.workspace.api.dto;

import com.project.devsphere.workspace.domain.WorkspaceMember;
import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberResponse(
        UUID id,
        UUID workspaceId,
        UUID userId,
        WorkspaceMember.Role role,
        WorkspaceMember.Status status,
        Instant joinedAt
) {
}
