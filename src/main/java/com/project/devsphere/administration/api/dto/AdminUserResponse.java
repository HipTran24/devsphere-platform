package com.project.devsphere.administration.api.dto;

import com.project.devsphere.identity.domain.User;
import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String email,
        String displayName,
        User.Status status,
        User.SystemRole systemRole,
        Instant emailVerifiedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
}
