package com.project.devsphere.operations.infrastructure.persistence;

import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BackgroundJobRepository extends ProjectScopedRepository<BackgroundJob>, JpaSpecificationExecutor<BackgroundJob> {

    Optional<BackgroundJob> findById(UUID id);

    long countByStatus(BackgroundJob.Status status);

    long countByJobKindAndStatus(BackgroundJob.JobKind jobKind, BackgroundJob.Status status);

    /**
     * Paginated operator search. Every criterion is optional; jobs whose kind is in
     * {@code excludedKinds} (auth/idempotency records) are never returned.
     */
    default Page<BackgroundJob> searchForAdmin(
            BackgroundJob.JobKind jobKind,
            BackgroundJob.Status status,
            UUID workspaceId,
            UUID projectId,
            Collection<BackgroundJob.JobKind> excludedKinds,
            Pageable pageable) {
        Specification<BackgroundJob> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (jobKind != null) {
                predicates.add(cb.equal(root.get("jobKind"), jobKind));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (workspaceId != null) {
                predicates.add(cb.equal(root.get("workspaceId"), workspaceId));
            }
            if (projectId != null) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            }
            if (excludedKinds != null && !excludedKinds.isEmpty()) {
                predicates.add(cb.not(root.get("jobKind").in(excludedKinds)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return findAll(specification, pageable);
    }
}
