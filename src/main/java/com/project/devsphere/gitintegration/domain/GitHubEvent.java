package com.project.devsphere.gitintegration.domain;

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

/** Persistence mapping for github_events; cross-module references remain UUIDs. */
@Entity
@Table(name = "github_events")
@Getter
@Setter
public class GitHubEvent extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "delivery_id", nullable = false, length = 120)
    private String deliveryId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "action", nullable = true, length = 80)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "process_status", nullable = false, length = 12)
    private ProcessStatus processStatus = ProcessStatus.RECEIVED;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at", nullable = true)
    private Instant processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum ProcessStatus { RECEIVED, PROCESSING, DONE, FAILED }
}
