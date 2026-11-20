package com.project.devsphere.notification.domain;

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

/** Persistence mapping for notifications; cross-module references remain UUIDs. */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification extends UuidEntity {

    @Column(name = "workspace_id", nullable = true)
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "project_id", nullable = true)
    private UUID projectId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "event_key", nullable = false, length = 180)
    private String eventKey;

    @Column(name = "title", nullable = false, length = 180)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "deeplink", nullable = true, columnDefinition = "text")
    private String deeplink;

    @Column(name = "read_at", nullable = true)
    private Instant readAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_status", nullable = false, length = 16)
    private EmailStatus emailStatus = EmailStatus.NOT_REQUESTED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum EmailStatus { NOT_REQUESTED, PENDING, SENT, FAILED }
}
