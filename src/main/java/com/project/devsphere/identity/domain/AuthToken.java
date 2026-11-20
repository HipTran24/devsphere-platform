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

/** Persistence mapping for auth_tokens; cross-module references remain UUIDs. */
@Entity
@Table(name = "auth_tokens")
@Getter
@Setter
public class AuthToken extends UuidEntity {

    @Column(name = "user_id", nullable = true)
    private UUID userId;

    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 24)
    private Purpose purpose;

    @Column(name = "token_hash", nullable = false, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at", nullable = true)
    private Instant usedAt;

    @Column(name = "attempts", nullable = false)
    private Short attempts = (short) 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }
}
