package com.project.devsphere.workitem.domain;

import com.project.devsphere.sharedkernel.persistence.UuidEntity;
import com.project.devsphere.resource.api.SkillLevel;
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

/** Persistence mapping for work_items; cross-module references remain UUIDs. */
@Entity
@Table(name = "work_items")
@Getter
@Setter
public class WorkItem extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @org.hibernate.annotations.Generated(event = org.hibernate.generator.EventType.INSERT)
    @lombok.Setter(lombok.AccessLevel.NONE)
    @Column(name = "item_key", nullable = false, length = 32, insertable = false, updatable = false)
    private String itemKey;

    @Column(name = "parent_id", nullable = true)
    private UUID parentId;

    @Column(name = "requirement_id", nullable = true)
    private UUID requirementId;

    @Column(name = "sprint_id", nullable = true)
    private UUID sprintId;

    @Column(name = "milestone_id", nullable = true)
    private UUID milestoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_kind", nullable = false, length = 12)
    private WorkKind workKind = WorkKind.CODE;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.TO_DO;

    @Column(name = "is_blocked", nullable = false)
    private Boolean isBlocked = false;

    @Column(name = "blocked_reason", nullable = true, columnDefinition = "text")
    private String blockedReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 12)
    private Priority priority = Priority.MEDIUM;

    @Column(name = "assignee_id", nullable = true)
    private UUID assigneeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_skills", nullable = false, columnDefinition = "jsonb")
    private List<SkillLevel> requiredSkills = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "labels", nullable = false, columnDefinition = "text[]")
    private String[] labels = new String[0];

    @Column(name = "backlog_rank", nullable = false, precision = 24, scale = 8)
    private BigDecimal backlogRank = new BigDecimal("0");

    @Column(name = "story_points", nullable = true, precision = 5, scale = 2)
    private BigDecimal storyPoints;

    @Column(name = "estimate_minutes", nullable = true)
    private Integer estimateMinutes;

    @Column(name = "remaining_minutes", nullable = true)
    private Integer remainingMinutes;

    @Column(name = "time_spent_minutes", nullable = false)
    private Integer timeSpentMinutes = 0;

    @Column(name = "claimed_progress", nullable = false, precision = 5, scale = 2)
    private BigDecimal claimedProgress = new BigDecimal("0");

    @Column(name = "verified_progress", nullable = true, precision = 5, scale = 2)
    private BigDecimal verifiedProgress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress_evidence", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> progressEvidence = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @Column(name = "started_at", nullable = true)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = true)
    private Instant completedAt;

    @Column(name = "due_at", nullable = true)
    private Instant dueAt;

    @Column(name = "accepted_by", nullable = true)
    private UUID acceptedBy;

    @Column(name = "deleted_at", nullable = true)
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Type { EPIC, USER_STORY, TASK, SUBTASK, BUG }
    public enum WorkKind { CODE, NON_CODE }
    public enum Status { TO_DO, IN_PROGRESS, IN_REVIEW, DONE }
    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
}
