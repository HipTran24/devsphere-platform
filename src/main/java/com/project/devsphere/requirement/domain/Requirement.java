package com.project.devsphere.requirement.domain;

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

/** Persistence mapping for requirements; cross-module references remain UUIDs. */
@Entity
@Table(name = "requirements")
@Getter
@Setter
public class Requirement extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind = Kind.REQUIREMENT;

    @Column(name = "parent_requirement_id", nullable = true)
    private UUID parentRequirementId;

    @Column(name = "title", nullable = false, length = 220)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_request_status", nullable = true, length = 24)
    private ChangeRequestStatus changeRequestStatus;

    @Column(name = "current_version", nullable = false)
    private Integer currentVersion = 1;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "approved_by", nullable = true)
    private UUID approvedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "impact_json", nullable = true, columnDefinition = "jsonb")
    private Map<String, Object> impactJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Kind { REQUIREMENT, CHANGE_REQUEST }
    public enum Status { DRAFT, APPROVED, ASSESSING, REJECTED, CHANGED, ARCHIVED }
    public enum ChangeRequestStatus { PENDING, APPROVED, REJECTED, DEFERRED, IMPLEMENTED }
}
