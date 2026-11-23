package com.project.devsphere.identity.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Minimal immutable representation of an active workspace membership scope.
 */
public record WorkspaceScope(UUID workspaceId, UUID userId, boolean owner) {

    public WorkspaceScope {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
