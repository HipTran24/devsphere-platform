package com.project.devsphere.identity.application;

import com.project.devsphere.identity.domain.ProjectRole;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal immutable representation of an active project membership scope.
 */
public record ProjectScope(UUID workspaceId, UUID projectId, UUID userId, Set<ProjectRole> roles) {

    public ProjectScope {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        roles = roles == null ? Set.of() : Collections.unmodifiableSet(Set.copyOf(roles));
    }

    public boolean hasRole(ProjectRole role) {
        return role != null && roles.contains(role);
    }

    public boolean hasAnyRole(Set<ProjectRole> candidateRoles) {
        if (candidateRoles == null || candidateRoles.isEmpty()) {
            return false;
        }
        return candidateRoles.stream().anyMatch(roles::contains);
    }
}
