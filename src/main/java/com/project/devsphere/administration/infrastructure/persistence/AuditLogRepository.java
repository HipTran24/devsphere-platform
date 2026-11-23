package com.project.devsphere.administration.infrastructure.persistence;

import com.project.devsphere.administration.domain.AuditLog;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends ProjectScopedRepository<AuditLog> {

    /** Most recent events correlated with one request trace. */
    List<AuditLog> findTop100ByTraceIdOrderByOccurredAtDesc(String traceId);

    /** Most recent events recorded against a single entity. */
    List<AuditLog> findTop100ByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, UUID entityId);

    long countByAction(String action);

    long countByTraceId(String traceId);
}
