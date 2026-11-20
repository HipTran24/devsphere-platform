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

/** Persistence mapping for users; cross-module references remain UUIDs. */
@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends UuidEntity {

    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash", nullable = true, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "avatar_key", nullable = true, columnDefinition = "text")
    private String avatarKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_role", nullable = false, length = 8)
    private SystemRole systemRole = SystemRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status = Status.PENDING;

    @Column(name = "email_verified_at", nullable = true)
    private Instant emailVerifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum SystemRole { ADMIN, USER }
    public enum Status { PENDING, ACTIVE, LOCKED }
}
