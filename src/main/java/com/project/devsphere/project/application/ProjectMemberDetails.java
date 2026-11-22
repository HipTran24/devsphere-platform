package com.project.devsphere.project.application;

import com.project.devsphere.identity.domain.ProjectRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ProjectMemberDetails(
        UUID userId,
        String email,
        String displayName,
        String status,
        Instant joinedAt,
        Set<ProjectRole> roles
) {
}
