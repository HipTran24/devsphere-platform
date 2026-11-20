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

/** Persistence mapping for schedule_versions; cross-module references remain UUIDs. */
@Entity
@Table(name = "schedule_versions")
@Getter
@Setter
@Immutable
public class ScheduleVersion extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source;

    @Column(name = "proposal_id", nullable = true)
    private UUID proposalId;

    @Column(name = "approved_by", nullable = false)
    private UUID approvedBy;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    @Column(name = "planned_finish", nullable = true)
    private LocalDate plannedFinish;

    @Column(name = "forecast_finish", nullable = true)
    private LocalDate forecastFinish;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "milestone_delays", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> milestoneDelays = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "baseline_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> baselineSnapshot = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum Source { MANUAL, AI_PLAN, RESCHEDULE }
}
