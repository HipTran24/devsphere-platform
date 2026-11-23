package com.project.devsphere.reporting.infrastructure.persistence;

import com.project.devsphere.reporting.domain.Report;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends ProjectScopedRepository<Report> {
    List<Report> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    List<Report> findByProjectIdAndVisibleToClientTrueAndStatusOrderByPublishedAtDesc(UUID projectId, Report.Status status);
    Optional<Report> findByIdAndProjectId(UUID id, UUID projectId);
}
