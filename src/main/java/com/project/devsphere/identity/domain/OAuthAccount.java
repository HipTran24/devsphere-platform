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

/** Persistence mapping for oauth_accounts; cross-module references remain UUIDs. */
@Entity
@Table(name = "oauth_accounts")
@Getter
@Setter
public class OAuthAccount extends UuidEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private Provider provider;

    @Column(name = "provider_subject", nullable = false, length = 200)
    private String providerSubject;

    @Column(name = "provider_login", nullable = true, length = 160)
    private String providerLogin;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt = Instant.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum Provider { GOOGLE, GITHUB }
}
