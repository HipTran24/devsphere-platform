package com.project.devsphere.reporting.domain;

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

/** Persistence mapping for reports; cross-module references remain UUIDs. */
@Entity
@Table(name = "reports")
@Getter
@Setter
public class Report extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private Type type;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status = Status.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> content;

    @Column(name = "visible_to_client", nullable = false)
    private Boolean visibleToClient = false;

    @Column(name = "milestone_id", nullable = true)
    private UUID milestoneId;

    @Column(name = "generated_by", nullable = true)
    private UUID generatedBy;

    @Column(name = "ai_job_id", nullable = true)
    private UUID aiJobId;

    @Column(name = "approved_by", nullable = true)
    private UUID approvedBy;

    @Column(name = "published_at", nullable = true)
    private Instant publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Type { DAILY, WEEKLY, SPRINT, RISK, CODE_QUALITY, CLIENT_ROADMAP }
    public enum Status { DRAFT, APPROVED, PUBLISHED }
}
