package com.project.devsphere.storage.domain;

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

/** Persistence mapping for attachments; cross-module references remain UUIDs. */
@Entity
@Table(name = "attachments")
@Getter
@Setter
public class Attachment extends UuidEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = true)
    private UUID projectId;

    @Column(name = "work_item_id", nullable = true)
    private UUID workItemId;

    @Column(name = "chat_message_id", nullable = true)
    private UUID chatMessageId;

    @Column(name = "report_id", nullable = true)
    private UUID reportId;

    @Column(name = "ci_run_id", nullable = true)
    private UUID ciRunId;

    @Column(name = "uploaded_by", nullable = true)
    private UUID uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", nullable = false, length = 64, columnDefinition = "char(64)")
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private Visibility visibility = Visibility.INTERNAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 12)
    private ScanStatus scanStatus = ScanStatus.PENDING;

    @Column(name = "deleted_at", nullable = true)
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Kind { TASK_FILE, CHAT_FILE, REPORT_EXPORT, CI_ARTIFACT }
    public enum Visibility { INTERNAL, CLIENT_SHARED }
    public enum ScanStatus { PENDING, CLEAN, REJECTED }
}
