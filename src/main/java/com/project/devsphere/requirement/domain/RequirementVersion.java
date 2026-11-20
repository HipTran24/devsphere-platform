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

/** Persistence mapping for requirement_versions; cross-module references remain UUIDs. */
@Entity
@Table(name = "requirement_versions")
@Getter
@Setter
public class RequirementVersion extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "acceptance_summary", nullable = false, columnDefinition = "text")
    private String acceptanceSummary = "";

    @Column(name = "change_reason", nullable = true, columnDefinition = "text")
    private String changeReason;

    @Column(name = "approved_at", nullable = true)
    private Instant approvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;


}
