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

/** Persistence mapping for milestones; cross-module references remain UUIDs. */
@Entity
@Table(name = "milestones")
@Getter
@Setter
public class Milestone extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "code", nullable = false, length = 24)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "forecast_date", nullable = true)
    private LocalDate forecastDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(name = "acceptance_status", nullable = false, length = 32)
    private AcceptanceStatus acceptanceStatus = AcceptanceStatus.NOT_SUBMITTED;

    @Column(name = "published_to_client", nullable = false)
    private Boolean publishedToClient = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acceptance_history", nullable = false, columnDefinition = "jsonb")
    private List<Object> acceptanceHistory = new ArrayList<>();

    @Column(name = "accepted_by", nullable = true)
    private UUID acceptedBy;

    @Column(name = "accepted_at", nullable = true)
    private Instant acceptedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Status { PLANNED, AT_RISK, READY_FOR_ACCEPTANCE, ACCEPTED }
    public enum AcceptanceStatus { NOT_SUBMITTED, PENDING_REVIEW, ACCEPTED, CHANGES_REQUESTED }
}
