package com.project.devsphere.administration.domain;

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

/** Persistence mapping for audit_logs; cross-module references remain UUIDs. */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@Immutable
public class AuditLog extends UuidEntity {

    @Column(name = "workspace_id", nullable = true)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = true)
    private UUID projectId;

    @Column(name = "actor_id", nullable = true)
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 120)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id", nullable = true)
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", nullable = true, columnDefinition = "jsonb")
    private Map<String, Object> beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", nullable = true, columnDefinition = "jsonb")
    private Map<String, Object> afterJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "trace_id", nullable = true, length = 64)
    private String traceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;


}
