package com.project.devsphere.codequality.domain;

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

/** Persistence mapping for ci_runs; cross-module references remain UUIDs. */
@Entity
@Table(name = "ci_runs")
@Getter
@Setter
public class CiRun extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "work_item_id", nullable = true)
    private UUID workItemId;

    @Column(name = "github_run_id", nullable = false)
    private Long githubRunId;

    @Column(name = "run_attempt", nullable = false)
    private Integer runAttempt = 1;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "head_sha", nullable = false, length = 40, columnDefinition = "char(40)")
    private String headSha;

    @Column(name = "pr_number", nullable = true)
    private Integer prNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "conclusion", nullable = true, length = 16)
    private Conclusion conclusion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checks", nullable = false, columnDefinition = "jsonb")
    private List<Object> checks = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metrics = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @Column(name = "started_at", nullable = true)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = true)
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Status { QUEUED, IN_PROGRESS, COMPLETED }
    public enum Conclusion { SUCCESS, FAILURE, CANCELLED, SKIPPED, TIMED_OUT, NEUTRAL, ACTION_REQUIRED, STALE, STARTUP_FAILURE }
}
