package com.project.devsphere.workspace.api.dto;

import com.project.devsphere.workspace.domain.Workspace;
import java.time.Instant;
import java.util.UUID;

public record WorkspaceSetupResponse(
        UUID workspaceId,
        String slug,
        String name,
        Workspace.Status status,
        boolean projectConfigured,
        boolean pmAssigned,
        long pendingInvitationsCount,
        Instant createdAt
) {
}
