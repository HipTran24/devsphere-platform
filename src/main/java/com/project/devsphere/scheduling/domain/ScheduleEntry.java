package com.project.devsphere.scheduling.domain;

import com.project.devsphere.sharedkernel.persistence.UuidEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Immutable;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Persistence mapping for schedule_entries; cross-module references remain UUIDs. */
@Entity
@Table(name = "schedule_entries")
@Getter
@Setter
@Immutable
public class ScheduleEntry extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "schedule_version_id", nullable = false)
    private UUID scheduleVersionId;

    @Column(name = "work_item_id", nullable = false)
    private UUID workItemId;

    @Column(name = "sprint_id", nullable = true)
    private UUID sprintId;

    @Column(name = "assignee_id", nullable = true)
    private UUID assigneeId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "finish_at", nullable = false)
    private Instant finishAt;

    @Column(name = "remaining_minutes_snapshot", nullable = false)
    private Integer remainingMinutesSnapshot;

    @Column(name = "frozen", nullable = false)
    private Boolean frozen = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;


}
