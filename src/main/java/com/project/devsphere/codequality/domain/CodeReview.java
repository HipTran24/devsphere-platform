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

/** Persistence mapping for code_reviews; cross-module references remain UUIDs. */
@Entity
@Table(name = "code_reviews")
@Getter
@Setter
public class CodeReview extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "work_item_id", nullable = true)
    private UUID workItemId;

    @Column(name = "ai_job_id", nullable = true)
    private UUID aiJobId;

    @Column(name = "pr_number", nullable = true)
    private Integer prNumber;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "head_sha", nullable = false, length = 40, columnDefinition = "char(40)")
    private String headSha;

    @Column(name = "context_version", nullable = false)
    private Long contextVersion = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 8)
    private Scope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_kind", nullable = false, length = 8)
    private ReviewerKind reviewerKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", nullable = false, columnDefinition = "jsonb")
    private List<Object> findings = new ArrayList<>();

    @Column(name = "summary", nullable = true, columnDefinition = "text")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gate_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> gateSnapshot = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @Column(name = "reviewer_id", nullable = true)
    private UUID reviewerId;

    @Column(name = "model_name", nullable = true, length = 120)
    private String modelName;

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

    public enum Scope { PUSH, PR }
    public enum ReviewerKind { AI, HUMAN }
    public enum Status { PENDING, RUNNING, PASS, FAIL, REQUIRES_CHANGES, UNAVAILABLE, STALE }
}
