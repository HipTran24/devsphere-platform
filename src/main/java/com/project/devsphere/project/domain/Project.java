package com.project.devsphere.project.domain;

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

/** Persistence mapping for projects; cross-module references remain UUIDs. */
@Entity
@Table(name = "projects")
@Getter
@Setter
public class Project extends UuidEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_key", nullable = false, length = 16)
    private String projectKey;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

    @Column(name = "objective", nullable = true, columnDefinition = "text")
    private String objective;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status = Status.PLANNING;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "Asia/Ho_Chi_Minh";

    @Column(name = "planned_start", nullable = true)
    private LocalDate plannedStart;

    @Column(name = "committed_deadline", nullable = true)
    private LocalDate committedDeadline;

    @Column(name = "sprint_length_days", nullable = false)
    private Integer sprintLengthDays = 14;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @Column(name = "active_schedule_version_id", nullable = true)
    private UUID activeScheduleVersionId;

    @Column(name = "deadline_approver_id", nullable = true)
    private UUID deadlineApproverId;

    @Column(name = "next_item_number", nullable = false)
    private Long nextItemNumber = 1L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Status { PLANNING, ACTIVE, ON_HOLD, COMPLETED, ARCHIVED }
}
