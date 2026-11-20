package com.project.devsphere.monitoring.infrastructure.persistence;

import com.project.devsphere.monitoring.domain.Alert;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface AlertRepository extends ProjectScopedRepository<Alert> {
}
