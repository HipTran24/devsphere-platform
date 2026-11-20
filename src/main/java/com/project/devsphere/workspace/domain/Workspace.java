package com.project.devsphere.workspace.domain;

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

/** Persistence mapping for workspaces; cross-module references remain UUIDs. */
@Entity
@Table(name = "workspaces")
@Getter
@Setter
public class Workspace extends UuidEntity {

    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.SETUP_INCOMPLETE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_connections", nullable = false, columnDefinition = "jsonb")
    private List<Object> aiConnections = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_task_routes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> aiTaskRoutes = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Status { SETUP_INCOMPLETE, ACTIVE }
}
