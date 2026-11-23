package com.project.devsphere.monitoring.infrastructure.persistence;

import com.project.devsphere.monitoring.domain.Alert;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends ProjectScopedRepository<Alert> {

    Optional<Alert> findFirstByProjectIdAndFingerprintAndStatusNot(UUID projectId, String fingerprint, Alert.Status status);

    Optional<Alert> findFirstByProjectIdAndFingerprintAndStatusOrderByResolvedAtDesc(UUID projectId, String fingerprint, Alert.Status status);

    List<Alert> findByProjectIdOrderByDetectedAtDesc(UUID projectId);

    List<Alert> findByProjectIdAndStatusOrderByDetectedAtDesc(UUID projectId, Alert.Status status);

    List<Alert> findByProjectIdAndSeverityOrderByDetectedAtDesc(UUID projectId, Alert.Severity severity);

    List<Alert> findByProjectIdAndWorkItemIdOrderByDetectedAtDesc(UUID projectId, UUID workItemId);

    @Query("SELECT a FROM Alert a WHERE a.projectId = :projectId AND a.status <> com.project.devsphere.monitoring.domain.Alert.Status.RESOLVED ORDER BY a.detectedAt DESC")
    List<Alert> findOpenAlertsByProjectId(@Param("projectId") UUID projectId);
}
