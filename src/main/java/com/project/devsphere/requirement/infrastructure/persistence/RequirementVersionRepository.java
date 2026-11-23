package com.project.devsphere.requirement.infrastructure.persistence;

import com.project.devsphere.requirement.domain.RequirementVersion;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequirementVersionRepository extends ProjectScopedRepository<RequirementVersion> {

    List<RequirementVersion> findByRequirementIdOrderByVersionNoAsc(UUID requirementId);

    Optional<RequirementVersion> findByRequirementIdAndVersionNo(UUID requirementId, Integer versionNo);

    Optional<RequirementVersion> findByProjectIdAndId(UUID projectId, UUID id);
}
