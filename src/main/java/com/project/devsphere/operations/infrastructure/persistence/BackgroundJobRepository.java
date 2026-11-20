package com.project.devsphere.operations.infrastructure.persistence;

import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface BackgroundJobRepository extends ProjectScopedRepository<BackgroundJob> {
}
