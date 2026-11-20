package com.project.devsphere.communication.domain;

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

/** Persistence mapping for chat_channels; cross-module references remain UUIDs. */
@Entity
@Table(name = "chat_channels")
@Getter
@Setter
public class ChatChannel extends UuidEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = true)
    private UUID projectId;

    @Column(name = "work_item_id", nullable = true)
    private UUID workItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 12)
    private Type type;

    @Column(name = "name", nullable = true, length = 150)
    private String name;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "archived_at", nullable = true)
    private Instant archivedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Type { PROJECT, TEAM, TASK, PRIVATE, CLIENT_PM }
}
