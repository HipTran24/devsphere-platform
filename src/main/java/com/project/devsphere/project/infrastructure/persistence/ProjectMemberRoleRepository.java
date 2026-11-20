package com.project.devsphere.project.infrastructure.persistence;

import com.project.devsphere.project.domain.ProjectMemberRole;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface ProjectMemberRoleRepository extends ProjectScopedRepository<ProjectMemberRole> {

    java.util.List<ProjectMemberRole> findByProjectIdAndProjectMemberIdAndRevokedAtIsNull(java.util.UUID projectId, java.util.UUID projectMemberId);
}
