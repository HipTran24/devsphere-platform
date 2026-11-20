package com.project.devsphere.resource.domain;

import com.project.devsphere.sharedkernel.persistence.UuidEntity;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.resource.api.TimeOff;
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

/** Persistence mapping for developer_capacity; cross-module references remain UUIDs. */
@Entity
@Table(name = "developer_capacity")
@Getter
@Setter
public class DeveloperCapacity extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skills", nullable = false, columnDefinition = "jsonb")
    private List<SkillLevel> skills = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weekly_slots", nullable = false, columnDefinition = "jsonb")
    private List<WeeklySlot> weeklySlots = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "time_off", nullable = false, columnDefinition = "jsonb")
    private List<TimeOff> timeOff = new ArrayList<>();

    @Column(name = "minutes_per_workday", nullable = false)
    private Integer minutesPerWorkday;

    @Column(name = "allocation_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal allocationPct = new BigDecimal("100");

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to", nullable = true)
    private LocalDate effectiveTo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;


}
