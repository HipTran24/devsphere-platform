package com.project.devsphere.requirement.infrastructure.persistence;

import com.project.devsphere.requirement.domain.Requirement;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface RequirementRepository extends ProjectScopedRepository<Requirement> {
}
