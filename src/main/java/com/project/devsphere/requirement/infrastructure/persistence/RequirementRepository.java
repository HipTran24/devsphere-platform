package com.project.devsphere.requirement.infrastructure.persistence;

import com.project.devsphere.requirement.domain.Requirement;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequirementRepository extends ProjectScopedRepository<Requirement> {

    List<Requirement> findByProjectIdAndKindOrderByCreatedAtAsc(UUID projectId, Requirement.Kind kind);

    List<Requirement> findByProjectIdAndKindAndStatusOrderByCreatedAtAsc(UUID projectId, Requirement.Kind kind, Requirement.Status status);

    List<Requirement> findByProjectIdAndKindAndRequestedByOrderByCreatedAtAsc(UUID projectId, Requirement.Kind kind, UUID requestedBy);

    Optional<Requirement> findByProjectIdAndId(UUID projectId, UUID id);

    Optional<Requirement> findByProjectIdAndIdAndKind(UUID projectId, UUID id, Requirement.Kind kind);
}
