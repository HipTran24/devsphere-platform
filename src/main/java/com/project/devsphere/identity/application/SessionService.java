package com.project.devsphere.identity.application;

import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.realtime.application.RealtimeSessionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {

    private static final Duration REFRESH_FAMILY_DURATION = Duration.ofDays(14);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokens tokens;
    private final ObjectProvider<RealtimeSessionRegistry> realtimeSessionRegistry;
    private final String dummyPasswordHash;

    public SessionService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectProvider<PasswordEncoder> passwordEncoder,
            SessionTokens tokens) {
        this(jdbcTemplate, transactionTemplate, passwordEncoder, tokens, null);
    }

    @Autowired
    public SessionService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectProvider<PasswordEncoder> passwordEncoder,
            SessionTokens tokens,
            ObjectProvider<RealtimeSessionRegistry> realtimeSessionRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.passwordEncoder = passwordEncoder.getIfAvailable(() -> new Argon2PasswordEncoder(16, 32, 1, 19456, 2));
        this.tokens = tokens;
        this.realtimeSessionRegistry = realtimeSessionRegistry;
        this.dummyPasswordHash = this.passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public enum Outcome {
        SUCCESS,
        INVALID_CREDENTIALS,
        ACCOUNT_LOCKED,
        EMAIL_UNVERIFIED,
        INVALID_SESSION,
        SESSION_REPLAYED
    }

    public record Attempt(
            Outcome outcome,
            SessionTokens.IssuedAccess access,
            String refresh,
            Instant refreshExpiresAt) {

        @Override
        public String toString() {
            return "Attempt[outcome=" + outcome + ", access=[REDACTED], refresh=[REDACTED], refreshExpiresAt=" + refreshExpiresAt + "]";
        }
    }

    public record Principal(
            UUID userId,
            UUID sessionId,
            String displayName,
            String systemRole) {
    }

    private record UserCredentials(UUID id, String passwordHash) {}

    private record LockedUserState(String passwordHash, String status) {}

    private record RefreshLookup(UUID id, UUID userId, UUID familyId, Instant expiresAt, Instant revokedAt, Instant rotatedAt) {}

    public Attempt login(String email, String password) {
        if (email == null || password == null) {
            return new Attempt(Outcome.INVALID_CREDENTIALS, null, null, null);
        }

        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        UserCredentials creds = null;
        try {
            creds = jdbcTemplate.queryForObject(
                    "SELECT id, password_hash FROM users WHERE email = ?::citext",
                    (rs, rowNum) -> new UserCredentials(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("password_hash")
                    ),
                    normalizedEmail
            );
        } catch (EmptyResultDataAccessException ignored) {
        }

        String checkHash = (creds != null && creds.passwordHash() != null) ? creds.passwordHash() : dummyPasswordHash;
        boolean matches = passwordEncoder.matches(password, checkHash);

        if (creds == null || creds.passwordHash() == null || !matches) {
            return new Attempt(Outcome.INVALID_CREDENTIALS, null, null, null);
        }

        final UUID targetUserId = creds.id();
        final String verifiedHash = creds.passwordHash();

        return transactionTemplate.execute(status -> {
            LockedUserState lockedUser;
            try {
                lockedUser = jdbcTemplate.queryForObject(
                        "SELECT password_hash, status FROM users WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> new LockedUserState(
                                rs.getString("password_hash"),
                                rs.getString("status")
                        ),
                        targetUserId
                );
            } catch (EmptyResultDataAccessException e) {
                return new Attempt(Outcome.INVALID_CREDENTIALS, null, null, null);
            }

            if (lockedUser == null || !verifiedHash.equals(lockedUser.passwordHash())) {
                return new Attempt(Outcome.INVALID_CREDENTIALS, null, null, null);
            }

            if ("LOCKED".equalsIgnoreCase(lockedUser.status())) {
                return new Attempt(Outcome.ACCOUNT_LOCKED, null, null, null);
            }
            if ("PENDING".equalsIgnoreCase(lockedUser.status())) {
                return new Attempt(Outcome.EMAIL_UNVERIFIED, null, null, null);
            }
            if (!"ACTIVE".equalsIgnoreCase(lockedUser.status())) {
                return new Attempt(Outcome.INVALID_CREDENTIALS, null, null, null);
            }

            Instant now = databaseNow();
            OffsetDateTime nowOdt = now.atOffset(ZoneOffset.UTC);
            UUID sessionId = UUID.randomUUID();
            UUID familyId = UUID.randomUUID();
            String rawRefresh = tokens.generateRefresh();
            String refreshDigest = tokens.digestRefresh(rawRefresh);
            Instant refreshExpiresAt = now.plus(REFRESH_FAMILY_DURATION);
            OffsetDateTime refreshExpiresOdt = refreshExpiresAt.atOffset(ZoneOffset.UTC);

            jdbcTemplate.update(
                    "INSERT INTO auth_sessions (id, user_id, refresh_hash, issued_at, expires_at, revoked_at, created_at, session_family_id, user_agent, ip, rotated_at) " +
                            "VALUES (?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, NULL)",
                    sessionId,
                    targetUserId,
                    refreshDigest,
                    nowOdt,
                    refreshExpiresOdt,
                    nowOdt,
                    familyId
            );

            SessionTokens.IssuedAccess issuedAccess = tokens.issueAccess(targetUserId, sessionId);
            return new Attempt(Outcome.SUCCESS, issuedAccess, rawRefresh, refreshExpiresAt);
        });
    }

    public Attempt refresh(String rawRefresh) {
        if (rawRefresh == null || rawRefresh.isBlank()) {
            return new Attempt(Outcome.INVALID_SESSION, null, null, null);
        }

        String digest;
        try {
            digest = tokens.digestRefresh(rawRefresh);
        } catch (IllegalArgumentException e) {
            return new Attempt(Outcome.INVALID_SESSION, null, null, null);
        }

        RefreshLookup preLookup;
        try {
            preLookup = jdbcTemplate.queryForObject(
                    "SELECT id, user_id, session_family_id, expires_at, revoked_at, rotated_at " +
                            "FROM auth_sessions WHERE refresh_hash = ?",
                    (rs, rowNum) -> {
                        OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                        OffsetDateTime rev = rs.getObject("revoked_at", OffsetDateTime.class);
                        OffsetDateTime rot = rs.getObject("rotated_at", OffsetDateTime.class);
                        return new RefreshLookup(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("user_id")),
                                UUID.fromString(rs.getString("session_family_id")),
                                exp != null ? exp.toInstant() : null,
                                rev != null ? rev.toInstant() : null,
                                rot != null ? rot.toInstant() : null
                        );
                    },
                    digest
            );
        } catch (EmptyResultDataAccessException e) {
            return new Attempt(Outcome.INVALID_SESSION, null, null, null);
        }

        final UUID ownerUserId = preLookup.userId();

        return transactionTemplate.execute(status -> {
            String userStatus;
            try {
                userStatus = jdbcTemplate.queryForObject(
                        "SELECT status FROM users WHERE id = ? FOR UPDATE",
                        String.class,
                        ownerUserId
                );
            } catch (EmptyResultDataAccessException e) {
                return new Attempt(Outcome.INVALID_SESSION, null, null, null);
            }

            if (!"ACTIVE".equalsIgnoreCase(userStatus)) {
                return new Attempt(Outcome.INVALID_SESSION, null, null, null);
            }

            RefreshLookup session;
            try {
                session = jdbcTemplate.queryForObject(
                        "SELECT id, user_id, session_family_id, expires_at, revoked_at, rotated_at " +
                                "FROM auth_sessions WHERE refresh_hash = ? AND user_id = ? FOR UPDATE",
                        (rs, rowNum) -> {
                            OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                            OffsetDateTime rev = rs.getObject("revoked_at", OffsetDateTime.class);
                            OffsetDateTime rot = rs.getObject("rotated_at", OffsetDateTime.class);
                            return new RefreshLookup(
                                    UUID.fromString(rs.getString("id")),
                                    UUID.fromString(rs.getString("user_id")),
                                    UUID.fromString(rs.getString("session_family_id")),
                                    exp != null ? exp.toInstant() : null,
                                    rev != null ? rev.toInstant() : null,
                                    rot != null ? rot.toInstant() : null
                            );
                        },
                        digest,
                        ownerUserId
                );
            } catch (EmptyResultDataAccessException e) {
                return new Attempt(Outcome.INVALID_SESSION, null, null, null);
            }

            Instant now = databaseNow();
            OffsetDateTime nowOdt = now.atOffset(ZoneOffset.UTC);
            if (session.expiresAt() == null || !now.isBefore(session.expiresAt())) {
                return new Attempt(Outcome.INVALID_SESSION, null, null, null);
            }

            if (session.revokedAt() != null) {
                if (session.rotatedAt() != null && now.isBefore(session.expiresAt())) {
                    List<UUID> revokedIds = jdbcTemplate.query(
                            "UPDATE auth_sessions SET revoked_at = ? " +
                                    "WHERE user_id = ? AND session_family_id = ? AND revoked_at IS NULL " +
                                    "RETURNING id",
                            (rs, rowNum) -> rs.getObject("id", UUID.class),
                            nowOdt,
                            ownerUserId,
                            session.familyId()
                    );
                    scheduleDisconnectAuthSessions(revokedIds);
                    return new Attempt(Outcome.SESSION_REPLAYED, null, null, null);
                }
                return new Attempt(Outcome.INVALID_SESSION, null, null, null);
            }

            jdbcTemplate.update(
                    "UPDATE auth_sessions SET revoked_at = ?, rotated_at = ? WHERE id = ?",
                    nowOdt,
                    nowOdt,
                    session.id()
            );
            scheduleDisconnectAuthSessions(List.of(session.id()));

            UUID successorSessionId = UUID.randomUUID();
            String successorRawRefresh = tokens.generateRefresh();
            String successorDigest = tokens.digestRefresh(successorRawRefresh);
            OffsetDateTime familyExpiryOdt = session.expiresAt().atOffset(ZoneOffset.UTC);

            jdbcTemplate.update(
                    "INSERT INTO auth_sessions (id, user_id, refresh_hash, issued_at, expires_at, revoked_at, created_at, session_family_id, user_agent, ip, rotated_at) " +
                            "VALUES (?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, NULL)",
                    successorSessionId,
                    ownerUserId,
                    successorDigest,
                    nowOdt,
                    familyExpiryOdt,
                    nowOdt,
                    session.familyId()
            );

            SessionTokens.IssuedAccess access = tokens.issueAccess(ownerUserId, successorSessionId);
            return new Attempt(Outcome.SUCCESS, access, successorRawRefresh, session.expiresAt());
        });
    }

    public void logout(String rawRefresh) {
        if (rawRefresh == null || rawRefresh.isBlank()) {
            return;
        }

        String digest;
        try {
            digest = tokens.digestRefresh(rawRefresh);
        } catch (IllegalArgumentException e) {
            return;
        }

        RefreshLookup preLookup;
        try {
            preLookup = jdbcTemplate.queryForObject(
                    "SELECT id, user_id, session_family_id, expires_at, revoked_at, rotated_at " +
                            "FROM auth_sessions WHERE refresh_hash = ?",
                    (rs, rowNum) -> {
                        OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                        OffsetDateTime rev = rs.getObject("revoked_at", OffsetDateTime.class);
                        OffsetDateTime rot = rs.getObject("rotated_at", OffsetDateTime.class);
                        return new RefreshLookup(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("user_id")),
                                UUID.fromString(rs.getString("session_family_id")),
                                exp != null ? exp.toInstant() : null,
                                rev != null ? rev.toInstant() : null,
                                rot != null ? rot.toInstant() : null
                        );
                    },
                    digest
            );
        } catch (EmptyResultDataAccessException e) {
            return;
        }

        final UUID ownerUserId = preLookup.userId();

        transactionTemplate.executeWithoutResult(status -> {
            try {
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE id = ? FOR UPDATE",
                        UUID.class,
                        ownerUserId
                );
            } catch (EmptyResultDataAccessException e) {
                return;
            }

            RefreshLookup session;
            try {
                session = jdbcTemplate.queryForObject(
                        "SELECT id, user_id, session_family_id, expires_at, revoked_at, rotated_at " +
                                "FROM auth_sessions WHERE refresh_hash = ? AND user_id = ? FOR UPDATE",
                        (rs, rowNum) -> {
                            OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                            OffsetDateTime rev = rs.getObject("revoked_at", OffsetDateTime.class);
                            OffsetDateTime rot = rs.getObject("rotated_at", OffsetDateTime.class);
                            return new RefreshLookup(
                                    UUID.fromString(rs.getString("id")),
                                    UUID.fromString(rs.getString("user_id")),
                                    UUID.fromString(rs.getString("session_family_id")),
                                    exp != null ? exp.toInstant() : null,
                                    rev != null ? rev.toInstant() : null,
                                    rot != null ? rot.toInstant() : null
                            );
                        },
                        digest,
                        ownerUserId
                );
            } catch (EmptyResultDataAccessException e) {
                return;
            }

            Instant now = databaseNow();
            OffsetDateTime nowOdt = now.atOffset(ZoneOffset.UTC);
            if (session.expiresAt() != null && now.isBefore(session.expiresAt())) {
                List<UUID> revokedIds = jdbcTemplate.query(
                        "UPDATE auth_sessions SET revoked_at = ? " +
                                "WHERE user_id = ? AND session_family_id = ? AND revoked_at IS NULL " +
                                "RETURNING id",
                        (rs, rowNum) -> rs.getObject("id", UUID.class),
                        nowOdt,
                        ownerUserId,
                        session.familyId()
                );
                scheduleDisconnectAuthSessions(revokedIds);
            }
        });
    }

    public Optional<Principal> authenticate(String rawAccess) {
        if (rawAccess == null || rawAccess.isBlank()) {
            return Optional.empty();
        }

        var accessIdentityOpt = tokens.readAccess(rawAccess);
        if (accessIdentityOpt.isEmpty()) {
            return Optional.empty();
        }

        var identity = accessIdentityOpt.get();
        UUID userId = identity.userId();
        UUID sessionId = identity.sessionId();
        if (userId == null || sessionId == null) {
            return Optional.empty();
        }

        OffsetDateTime nowOdt = databaseNow().atOffset(ZoneOffset.UTC);

        try {
            Principal principal = jdbcTemplate.queryForObject(
                    "SELECT u.id AS user_id, s.id AS session_id, u.display_name, u.system_role " +
                            "FROM auth_sessions s " +
                            "JOIN users u ON u.id = s.user_id " +
                            "WHERE s.id = ? AND s.user_id = ? AND s.revoked_at IS NULL " +
                            "AND s.expires_at > ? AND u.status = 'ACTIVE'",
                    (rs, rowNum) -> new Principal(
                            UUID.fromString(rs.getString("user_id")),
                            UUID.fromString(rs.getString("session_id")),
                            rs.getString("display_name"),
                            rs.getString("system_role")
                    ),
                    sessionId,
                    userId,
                    nowOdt
            );
            return Optional.ofNullable(principal);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Instant databaseNow() {
        return jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    public record SessionSummary(UUID familyId, Instant issuedAt, Instant expiresAt, boolean current) {}

    public List<SessionSummary> list(Principal principal) {
        if (principal == null || principal.userId() == null || principal.sessionId() == null) {
            return List.of();
        }
        OffsetDateTime now = databaseNow().atOffset(ZoneOffset.UTC);
        String sql = """
            SELECT s.id, s.session_family_id, s.issued_at, s.expires_at
            FROM auth_sessions s
            WHERE s.user_id = ?
              AND s.revoked_at IS NULL
              AND s.expires_at > ?
              AND EXISTS (
                  SELECT 1 FROM users u
                  WHERE u.id = ? AND u.status = 'ACTIVE'
              )
              AND EXISTS (
                  SELECT 1 FROM auth_sessions cur
                  WHERE cur.id = ?
                    AND cur.user_id = ?
                    AND cur.revoked_at IS NULL
                    AND cur.expires_at > ?
              )
            ORDER BY s.issued_at DESC, s.id ASC
            LIMIT 100
            """;
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> {
                UUID id = rs.getObject("id", UUID.class);
                UUID familyId = rs.getObject("session_family_id", UUID.class);
                OffsetDateTime issuedAt = rs.getObject("issued_at", OffsetDateTime.class);
                OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                boolean current = principal.sessionId().equals(id);
                return new SessionSummary(
                    familyId,
                    issuedAt.toInstant(),
                    expiresAt.toInstant(),
                    current
                );
            },
            principal.userId(),
            now,
            principal.userId(),
            principal.sessionId(),
            principal.userId(),
            now
        );
    }

    public boolean revoke(Principal principal, UUID familyId) {
        if (principal == null || principal.userId() == null || principal.sessionId() == null || familyId == null) {
            return false;
        }
        Boolean result = transactionTemplate.execute(status -> {
            List<UUID> locked = jdbcTemplate.query(
                "SELECT id FROM users WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                principal.userId()
            );
            if (locked.isEmpty()) {
                return false;
            }
            OffsetDateTime now = databaseNow().atOffset(ZoneOffset.UTC);
            String updateSql = """
                UPDATE auth_sessions
                SET revoked_at = ?
                WHERE user_id = ?
                  AND session_family_id = ?
                  AND revoked_at IS NULL
                  AND expires_at > ?
                  AND EXISTS (
                      SELECT 1 FROM auth_sessions cur
                      WHERE cur.id = ?
                        AND cur.user_id = ?
                        AND cur.revoked_at IS NULL
                        AND cur.expires_at > ?
                  )
                RETURNING id
                """;
            List<UUID> revokedIds = jdbcTemplate.query(
                updateSql,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                now,
                principal.userId(),
                familyId,
                now,
                principal.sessionId(),
                principal.userId(),
                now
            );
            if (!revokedIds.isEmpty()) {
                scheduleDisconnectAuthSessions(revokedIds);
                return true;
            }
            return false;
        });
        return Boolean.TRUE.equals(result);
    }

    private void scheduleDisconnectAuthSessions(List<UUID> revokedSessionIds) {
        if (revokedSessionIds == null || revokedSessionIds.isEmpty()) {
            return;
        }
        RealtimeSessionRegistry registry = realtimeSessionRegistry == null ? null : realtimeSessionRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        List<UUID> idsToDisconnect = List.copyOf(revokedSessionIds);
        Runnable disconnectTask = () -> {
            for (UUID authSessionId : idsToDisconnect) {
                registry.disconnectAuthSession(authSessionId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    disconnectTask.run();
                }
            });
        } else {
            disconnectTask.run();
        }
    }
}
