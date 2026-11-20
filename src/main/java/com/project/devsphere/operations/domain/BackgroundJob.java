package com.project.devsphere.operations.domain;

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

/** Persistence mapping for background_jobs; cross-module references remain UUIDs. */
@Entity
@Table(name = "background_jobs")
@Getter
@Setter
public class BackgroundJob extends UuidEntity {

    @Column(name = "workspace_id", nullable = true)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = true)
    private UUID projectId;

    @Column(name = "requested_by", nullable = true)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_kind", nullable = false, length = 24)
    private JobKind jobKind;

    @Column(name = "idempotency_key", nullable = true, length = 180)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = true, length = 64, columnDefinition = "char(64)")
    private String requestHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", nullable = true, columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status = Status.QUEUED;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt = Instant.now();

    @Column(name = "started_at", nullable = true)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = true)
    private Instant finishedAt;

    @Column(name = "expires_at", nullable = true)
    private Instant expiresAt;

    @Column(name = "locked_by", nullable = true, length = 120)
    private String lockedBy;

    @Column(name = "locked_until", nullable = true)
    private Instant lockedUntil;

    @Column(name = "last_error", nullable = true, columnDefinition = "text")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum JobKind { OUTBOX_EVENT, WEBHOOK_RETRY, NOTIFICATION_EMAIL, GITHUB_BACKFILL, REPORT_EXPORT, SCHEDULE_SOLVE, IDEMPOTENCY, BACKUP_CHECK }
    public enum Status { QUEUED, RUNNING, SUCCESS, FAILED }
}
