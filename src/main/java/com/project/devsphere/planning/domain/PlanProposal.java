package com.project.devsphere.planning.domain;

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

/** Persistence mapping for plan_proposals; cross-module references remain UUIDs. */
@Entity
@Table(name = "plan_proposals")
@Getter
@Setter
public class PlanProposal extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private Kind kind;

    @Column(name = "base_project_version", nullable = false)
    private Long baseProjectVersion;

    @Column(name = "base_schedule_version_id", nullable = true)
    private UUID baseScheduleVersionId;

    @Column(name = "requirement_version_id", nullable = true)
    private UUID requirementVersionId;

    @Column(name = "change_request_id", nullable = true)
    private UUID changeRequestId;

    @Column(name = "ai_job_id", nullable = true)
    private UUID aiJobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> proposalPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "solver_status", nullable = false, length = 16)
    private SolverStatus solverStatus = SolverStatus.NOT_RUN;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "solver_details", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> solverDetails = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assumptions", nullable = false, columnDefinition = "jsonb")
    private List<Object> assumptions = new ArrayList<>();

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "approved_by", nullable = true)
    private UUID approvedBy;

    @Column(name = "approved_at", nullable = true)
    private Instant approvedAt;

    @Column(name = "changes_committed_deadline", nullable = false)
    private Boolean changesCommittedDeadline = false;

    @Column(name = "proposed_committed_deadline", nullable = true)
    private LocalDate proposedCommittedDeadline;

    @Column(name = "deadline_confirmed_by", nullable = true)
    private UUID deadlineConfirmedBy;

    @Column(name = "deadline_confirmed_at", nullable = true)
    private Instant deadlineConfirmedAt;

    @Column(name = "deadline_confirmation_version", nullable = true)
    private Long deadlineConfirmationVersion;

    @Column(name = "deadline_confirmed_project_version", nullable = true)
    private Long deadlineConfirmedProjectVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Kind { AI_REQUIREMENT, MANUAL_REPLAN, AUTO_REPLAN }
    public enum Status { DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, APPLIED, STALE }
    public enum SolverStatus { NOT_RUN, OPTIMAL, FEASIBLE, INFEASIBLE, UNKNOWN, ERROR }
}
