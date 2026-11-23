package com.project.devsphere.project.infrastructure.persistence;

import com.project.devsphere.project.domain.Project;
import com.project.devsphere.sharedkernel.persistence.WorkspaceScopedRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends WorkspaceScopedRepository<Project> {

    Optional<Project> findById(UUID id);

    Optional<Project> findByWorkspaceId(UUID workspaceId);

    boolean existsByWorkspaceId(UUID workspaceId);

    Optional<Project> findByWorkspaceIdAndId(UUID workspaceId, UUID id);
}
