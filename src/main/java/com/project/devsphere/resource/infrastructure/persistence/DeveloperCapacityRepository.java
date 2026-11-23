package com.project.devsphere.resource.infrastructure.persistence;

import com.project.devsphere.resource.domain.DeveloperCapacity;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeveloperCapacityRepository extends ProjectScopedRepository<DeveloperCapacity> {

    List<DeveloperCapacity> findByProjectId(UUID projectId);

    List<DeveloperCapacity> findByProjectIdAndUserId(UUID projectId, UUID userId);

    List<DeveloperCapacity> findByProjectIdAndUserIdOrderByEffectiveFromAsc(UUID projectId, UUID userId);

    List<DeveloperCapacity> findByProjectIdOrderByUserIdAscEffectiveFromAsc(UUID projectId);

    Optional<DeveloperCapacity> findByProjectIdAndUserIdAndEffectiveFrom(UUID projectId, UUID userId, LocalDate effectiveFrom);

    default List<DeveloperCapacity> findIntersecting(UUID projectId, UUID userId, LocalDate fromDate, LocalDate toDate) {
        return findByProjectIdAndUserIdOrderByEffectiveFromAsc(projectId, userId).stream()
                .filter(c -> (toDate == null || c.getEffectiveFrom().isBefore(toDate)) &&
                             (c.getEffectiveTo() == null || fromDate == null || c.getEffectiveTo().isAfter(fromDate)))
                .toList();
    }

    default List<DeveloperCapacity> findAllIntersecting(UUID projectId, LocalDate fromDate, LocalDate toDate) {
        return findByProjectIdOrderByUserIdAscEffectiveFromAsc(projectId).stream()
                .filter(c -> (toDate == null || c.getEffectiveFrom().isBefore(toDate)) &&
                             (c.getEffectiveTo() == null || fromDate == null || c.getEffectiveTo().isAfter(fromDate)))
                .toList();
    }
}
