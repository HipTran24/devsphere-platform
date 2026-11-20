package com.project.devsphere.project.infrastructure.persistence;

import com.project.devsphere.project.domain.ProjectMember;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface ProjectMemberRepository extends ProjectScopedRepository<ProjectMember> {

    java.util.Optional<ProjectMember> findByProjectIdAndUserId(java.util.UUID projectId, java.util.UUID userId);
}
