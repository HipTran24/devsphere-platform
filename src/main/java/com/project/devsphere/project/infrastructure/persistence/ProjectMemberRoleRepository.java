package com.project.devsphere.project.infrastructure.persistence;

import com.project.devsphere.project.domain.ProjectMemberRole;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectMemberRoleRepository extends ProjectScopedRepository<ProjectMemberRole> {

    List<ProjectMemberRole> findByProjectIdAndProjectMemberIdAndRevokedAtIsNull(UUID projectId, UUID projectMemberId);

    List<ProjectMemberRole> findByProjectIdAndRoleAndRevokedAtIsNull(UUID projectId, ProjectMemberRole.Role role);

    boolean existsByProjectIdAndRoleAndRevokedAtIsNull(UUID projectId, ProjectMemberRole.Role role);
}
