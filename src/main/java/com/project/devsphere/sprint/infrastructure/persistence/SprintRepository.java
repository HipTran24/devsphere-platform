package com.project.devsphere.sprint.infrastructure.persistence;

import com.project.devsphere.sprint.domain.Sprint;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SprintRepository extends ProjectScopedRepository<Sprint> {

    List<Sprint> findByProjectIdOrderByStartDateDesc(UUID projectId);

    List<Sprint> findByProjectIdOrderByStartDateAscIdAsc(UUID projectId);

    List<Sprint> findByProjectIdAndStatus(UUID projectId, Sprint.Status status);

    List<Sprint> findByProjectIdAndStatusOrderByStartDateAscIdAsc(UUID projectId, Sprint.Status status);

    boolean existsByProjectIdAndStatus(UUID projectId, Sprint.Status status);

    Optional<Sprint> findFirstByProjectIdAndStatus(UUID projectId, Sprint.Status status);
}
