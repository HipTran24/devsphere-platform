package com.project.devsphere.project.infrastructure.persistence;

import com.project.devsphere.project.domain.Project;
import com.project.devsphere.sharedkernel.persistence.WorkspaceScopedRepository;

public interface ProjectRepository extends WorkspaceScopedRepository<Project> {
}
