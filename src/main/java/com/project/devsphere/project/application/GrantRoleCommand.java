package com.project.devsphere.project.application;

import com.project.devsphere.identity.domain.ProjectRole;
import java.util.UUID;

public record GrantRoleCommand(UUID userId, ProjectRole role) {
}
