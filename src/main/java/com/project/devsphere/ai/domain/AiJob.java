package com.project.devsphere.ai.domain;

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

/** Persistence mapping for ai_jobs; cross-module references remain UUIDs. */
@Entity
@Table(name = "ai_jobs")
@Getter
@Setter
public class AiJob extends UuidEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = true)
    private UUID projectId;

    @Column(name = "requested_by", nullable = true)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 24)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status = Status.QUEUED;

    @Column(name = "model_name", nullable = false, length = 140)
    private String modelName;

    @Column(name = "connection_id", nullable = false, length = 80)
    private String connectionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "route_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> routeSnapshot;

    @Column(name = "prompt_version", nullable = false, length = 40)
    private String promptVersion;

    @Column(name = "output_schema_version", nullable = false, length = 40)
    private String outputSchemaVersion = "1";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_refs", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputRefs = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", nullable = true, columnDefinition = "jsonb")
    private Map<String, Object> outputJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 16)
    private ValidationStatus validationStatus = ValidationStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_report", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> validationReport = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @Column(name = "validation_attempts", nullable = false)
    private Short validationAttempts = (short) 0;

    @Column(name = "validated_by_model", nullable = true, length = 140)
    private String validatedByModel;

    @Column(name = "tokens_in", nullable = true)
    private Integer tokensIn;

    @Column(name = "tokens_out", nullable = true)
    private Integer tokensOut;

    @Column(name = "error_code", nullable = true, length = 100)
    private String errorCode;

    @Column(name = "started_at", nullable = true)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = true)
    private Instant finishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum JobType { REQUIREMENT_PLAN, PUSH_REVIEW, PR_REVIEW, PROGRESS_EXPLAIN, ALERT_EXPLAIN, CHAT_ANSWER, REPORT_SUMMARY, AI_CRITIQUE }
    public enum Status { QUEUED, RUNNING, SUCCESS, FAILED }
    public enum ValidationStatus { PENDING, VALID, INVALID, NEEDS_REVIEW }
}
