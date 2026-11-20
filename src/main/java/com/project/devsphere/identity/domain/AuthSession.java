package com.project.devsphere.identity.domain;

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

/** Persistence mapping for auth_sessions; cross-module references remain UUIDs. */
@Entity
@Table(name = "auth_sessions")
@Getter
@Setter
public class AuthSession extends UuidEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "refresh_hash", nullable = false, length = 255)
    private String refreshHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = true)
    private Instant revokedAt;

    @Column(name = "user_agent", nullable = true, columnDefinition = "text")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip", nullable = true, columnDefinition = "inet")
    private InetAddress ip;

    @Column(name = "session_family_id", nullable = false)
    private UUID sessionFamilyId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;


}
