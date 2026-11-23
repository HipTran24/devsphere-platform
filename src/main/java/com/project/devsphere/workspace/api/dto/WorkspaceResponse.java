package com.project.devsphere.workspace.api.dto;

import com.project.devsphere.workspace.domain.Workspace;
import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(
        UUID id,
        String slug,
        String name,
        String description,
        Workspace.Status status,
        UUID ownerId,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
}
