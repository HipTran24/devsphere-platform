package com.project.devsphere.administration.infrastructure.persistence;

import com.project.devsphere.administration.domain.AuditLog;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface AuditLogRepository extends ProjectScopedRepository<AuditLog> {
}
