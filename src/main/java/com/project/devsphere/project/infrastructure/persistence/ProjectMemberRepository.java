package com.project.devsphere.project.infrastructure.persistence;

import com.project.devsphere.project.domain.ProjectMember;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends ProjectScopedRepository<ProjectMember> {

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    Optional<ProjectMember> findByProjectIdAndUserIdAndStatus(UUID projectId, UUID userId, ProjectMember.Status status);

    List<ProjectMember> findByProjectIdAndStatus(UUID projectId, ProjectMember.Status status);

    boolean existsByProjectIdAndUserIdAndStatus(UUID projectId, UUID userId, ProjectMember.Status status);

    boolean existsByWorkspaceIdAndProjectIdAndUserIdAndStatus(UUID workspaceId, UUID projectId, UUID userId, ProjectMember.Status status);
}
