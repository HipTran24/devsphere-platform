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

/** Persistence mapping for chat_messages; cross-module references remain UUIDs. */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
public class ChatMessage extends UuidEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = true)
    private UUID projectId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "sender_id", nullable = true)
    private UUID senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_kind", nullable = false, length = 8)
    private SenderKind senderKind = SenderKind.USER;

    @Column(name = "ai_job_id", nullable = true)
    private UUID aiJobId;

    @Column(name = "reply_to_id", nullable = true)
    private UUID replyToId;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "mentions", nullable = false, columnDefinition = "uuid[]")
    private UUID[] mentions = new UUID[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provenance", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> provenance = new LinkedHashMap<>(Map.of("schemaVersion", 1));

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "edited_at", nullable = true)
    private Instant editedAt;

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

    public enum SenderKind { USER, AI, SYSTEM }
}
