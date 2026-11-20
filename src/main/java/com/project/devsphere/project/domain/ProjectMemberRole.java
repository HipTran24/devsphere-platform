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

/** Persistence mapping for project_member_roles; cross-module references remain UUIDs. */
@Entity
@Table(name = "project_member_roles")
@Getter
@Setter
public class ProjectMemberRole extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "project_member_id", nullable = false)
    private UUID projectMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 12)
    private Role role;

    @Column(name = "granted_by", nullable = false)
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at", nullable = true)
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum Role { PM, DEVELOPER, CLIENT, VIEWER }
}
