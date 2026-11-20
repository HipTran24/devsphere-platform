package com.project.devsphere.monitoring.domain;

import com.project.devsphere.sharedkernel.persistence.UuidEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Persistence mapping for alerts; cross-module references remain UUIDs. */
@Entity
@Table(name = "alerts")
@Getter
@Setter
public class Alert extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "work_item_id", nullable = true)
    private UUID workItemId;

    @Column(name = "previous_alert_id", nullable = true)
    private UUID previousAlertId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fingerprint", nullable = false, length = 64, columnDefinition = "char(64)")
    private String fingerprint;

    @Column(name = "alert_code", nullable = false, length = 80)
    private String alertCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 12)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.NEW;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "impact_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> impactSnapshot = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @Column(name = "assigned_to", nullable = true)
    private UUID assignedTo;

    @Column(name = "acknowledged_by", nullable = true)
    private UUID acknowledgedBy;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();

    @Column(name = "resolved_at", nullable = true)
    private Instant resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
    public enum Status { NEW, ACKNOWLEDGED, IN_PROGRESS, RESOLVED }
}
