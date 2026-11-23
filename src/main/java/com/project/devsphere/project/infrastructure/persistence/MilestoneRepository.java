package com.project.devsphere.project.infrastructure.persistence;

import com.project.devsphere.project.domain.Milestone;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MilestoneRepository extends ProjectScopedRepository<Milestone> {
    List<Milestone> findByProjectIdOrderByDueDateAsc(UUID projectId);
    List<Milestone> findByProjectIdAndPublishedToClientTrueOrderByDueDateAsc(UUID projectId);
    Optional<Milestone> findByIdAndProjectId(UUID id, UUID projectId);
}
