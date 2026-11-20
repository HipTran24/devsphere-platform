package com.project.devsphere.workitem.domain;

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

/** Persistence mapping for acceptance_criteria; cross-module references remain UUIDs. */
@Entity
@Table(name = "acceptance_criteria")
@Getter
@Setter
public class AcceptanceCriterion extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "work_item_id", nullable = false)
    private UUID workItemId;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_kind", nullable = false, length = 16)
    private EvidenceKind evidenceKind;

    @Column(name = "weight", nullable = false, precision = 8, scale = 3)
    private BigDecimal weight = new BigDecimal("1");

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.NOT_EVALUATED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private List<Object> evidence = new ArrayList<>();

    @Column(name = "verified_by", nullable = true)
    private UUID verifiedBy;

    @Column(name = "verified_at", nullable = true)
    private Instant verifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum EvidenceKind { CI_TEST, PR, FILE, MANUAL }
    public enum Status { NOT_EVALUATED, VERIFIED, NOT_MET, INSUFFICIENT_EVIDENCE }
}
