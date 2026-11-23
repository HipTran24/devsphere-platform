package com.project.devsphere.ai.infrastructure.persistence;

import com.project.devsphere.ai.domain.AiJob;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiJobRepository extends ProjectScopedRepository<AiJob> {
    Optional<AiJob> findByIdAndProjectId(UUID id, UUID projectId);
    List<AiJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
