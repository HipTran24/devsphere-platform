package com.project.devsphere.sprint.domain;

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

/** Persistence mapping for sprints; cross-module references remain UUIDs. */
@Entity
@Table(name = "sprints")
@Getter
@Setter
public class Sprint extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "goal", nullable = false, columnDefinition = "text")
    private String goal = "";

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status = Status.PLANNED;

    @Column(name = "review_notes", nullable = true, columnDefinition = "text")
    private String reviewNotes;

    @Column(name = "retrospective_notes", nullable = true, columnDefinition = "text")
    private String retrospectiveNotes;

    @Column(name = "committed_points", nullable = true, precision = 8, scale = 2)
    private BigDecimal committedPoints;

    @Column(name = "completed_points", nullable = true, precision = 8, scale = 2)
    private BigDecimal completedPoints;

    @Column(name = "closed_at", nullable = true)
    private Instant closedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Status { PLANNED, ACTIVE, CLOSED }
}
