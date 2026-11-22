package com.project.devsphere.identity.application;

import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private static final String PURPOSE_VERIFY_EMAIL = "VERIFY_EMAIL";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
    private static final Duration MIN_RESEND_INTERVAL = Duration.ofSeconds(30);
    private static final Duration HOURLY_WINDOW = Duration.ofHours(1);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_RESENDS_PER_HOUR = 5;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<VerificationCodeCrypto> cryptoProvider;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final PasswordEncoder passwordEncoder;
    private final String mailFrom;
    private final String smtpHost;

    public RegistrationService(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<VerificationCodeCrypto> cryptoProvider,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            PasswordEncoder passwordEncoder,
            @Value("${devsphere.auth.mail.from:}") String mailFrom,
            @Value("${spring.mail.host:}") String smtpHost) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.cryptoProvider = Objects.requireNonNull(cryptoProvider, "cryptoProvider must not be null");
        this.mailSenderProvider = Objects.requireNonNull(mailSenderProvider, "mailSenderProvider must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.mailFrom = mailFrom;
        this.smtpHost = smtpHost;
    }

    public record RegistrationReceipt(UUID userId, UUID challengeId, String emailMasked, String status, Instant expiresAt) {}

    public record ChallengeReceipt(UUID challengeId, String emailMasked, Instant expiresAt) {}

    public record VerificationOutcome(UUID userId, boolean accepted) {}

    public static final class Rejection extends RuntimeException {
        public final String code;
        public final int retryAfterSeconds;

        public Rejection(String code, int retryAfterSeconds) {
            super(code);
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    @Transactional
    public RegistrationReceipt register(String email, String password, String displayName) {
        VerificationCodeCrypto crypto = requireConfiguredCrypto();

        String normalizedEmail = email != null ? email.trim().toLowerCase(Locale.ROOT) : "";
        String cleanName = displayName != null ? displayName.trim() : "";
        String passwordHash = passwordEncoder.encode(password);

        UUID userId = insertUser(normalizedEmail, passwordHash, cleanName);
        if (userId == null) {
            throw new Rejection("EMAIL_ALREADY_EXISTS", 0);
        }

        Instant now = databaseNow();
        Instant expiresAt = now.plus(CHALLENGE_TTL);
        UUID challengeId = UUID.randomUUID();

        issueVerificationChallenge(crypto, userId, challengeId, normalizedEmail, now, expiresAt);

        return new RegistrationReceipt(userId, challengeId, maskEmail(normalizedEmail), STATUS_PENDING, expiresAt);
    }

    @Transactional
    public VerificationOutcome verify(UUID challengeId, String code) {
        VerificationCodeCrypto crypto = cryptoProvider.getIfAvailable();
        if (crypto == null) {
            throw new Rejection("AUTH_NOT_CONFIGURED", 0);
        }

        if (challengeId == null || code == null || code.isBlank()) {
            return new VerificationOutcome(null, false);
        }

        UserRow user = lockUserByChallenge(challengeId);
        if (user == null || !STATUS_PENDING.equalsIgnoreCase(user.status())) {
            return new VerificationOutcome(user != null ? user.id() : null, false);
        }

        AuthTokenRow token = findTokenById(challengeId, user.email());
        if (token == null
                || !PURPOSE_VERIFY_EMAIL.equals(token.purpose())
                || token.usedAt() != null
                || token.attempts() >= MAX_ATTEMPTS
                || !databaseNow().isBefore(token.expiresAt())) {
            return new VerificationOutcome(user.id(), false);
        }

        boolean matches = crypto.matches(challengeId, code, token.tokenHash());

        if (!matches) {
            incrementTokenAttempts(challengeId);
            return new VerificationOutcome(user.id(), false);
        }

        OffsetDateTime nowUtc = databaseNow().atOffset(ZoneOffset.UTC);
        consumeToken(challengeId, nowUtc);
        activateUser(user.id(), nowUtc);

        return new VerificationOutcome(user.id(), true);
    }

    @Transactional
    public ChallengeReceipt resend(UUID challengeId) {
        VerificationCodeCrypto crypto = requireConfiguredCrypto();

        if (challengeId == null) {
            throw new Rejection("OTP_INVALID", 0);
        }

        UserRow user = lockUserByChallenge(challengeId);
        if (user == null || !STATUS_PENDING.equalsIgnoreCase(user.status())) {
            throw new Rejection("OTP_INVALID", 0);
        }

        AuthTokenRow latestToken = findLatestVerifyToken(user.id());
        if (latestToken == null
                || !challengeId.equals(latestToken.id())
                || latestToken.usedAt() != null) {
            throw new Rejection("OTP_INVALID", 0);
        }

        Instant now = databaseNow();
        enforceRateLimits(user.id(), latestToken.createdAt(), now);

        OffsetDateTime nowUtc = now.atOffset(ZoneOffset.UTC);
        retireChallenge(challengeId, nowUtc);

        UUID newChallengeId = UUID.randomUUID();
        Instant expiresAt = now.plus(CHALLENGE_TTL);
        issueVerificationChallenge(crypto, user.id(), newChallengeId, user.email(), now, expiresAt);

        return new ChallengeReceipt(newChallengeId, maskEmail(user.email()), expiresAt);
    }

    private VerificationCodeCrypto requireConfiguredCrypto() {
        VerificationCodeCrypto crypto = cryptoProvider.getIfAvailable();
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (crypto == null || mailSender == null || mailFrom == null || mailFrom.isBlank()
                || smtpHost == null || smtpHost.isBlank()) {
            throw new Rejection("AUTH_NOT_CONFIGURED", 0);
        }
        return crypto;
    }

    private UUID insertUser(String email, String passwordHash, String displayName) {
        String sql = """
            INSERT INTO users (email, password_hash, display_name)
            VALUES (CAST(? AS citext), ?, ?)
            ON CONFLICT (email) DO NOTHING
            RETURNING id
            """;
        List<UUID> ids = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                email,
                passwordHash,
                displayName
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void issueVerificationChallenge(
            VerificationCodeCrypto crypto,
            UUID userId,
            UUID challengeId,
            String email,
            Instant now,
            Instant expiresAt) {

        String rawCode = crypto.generateCode();
        String tokenHash = crypto.hash(challengeId, rawCode);
        String ciphertext = crypto.encrypt(challengeId, rawCode);

        OffsetDateTime createdAtUtc = now.atOffset(ZoneOffset.UTC);
        OffsetDateTime expiresAtUtc = expiresAt.atOffset(ZoneOffset.UTC);

        insertAuthToken(challengeId, userId, email, tokenHash, expiresAtUtc, createdAtUtc);
        insertOutboxJob(userId, challengeId, ciphertext, expiresAtUtc);
    }

    private void insertAuthToken(
            UUID challengeId,
            UUID userId,
            String email,
            String tokenHash,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) {
        String sql = """
            INSERT INTO auth_tokens (id, user_id, email, purpose, token_hash, expires_at, created_at, used_at, attempts)
            VALUES (?, ?, CAST(? AS citext), ?, ?, ?, ?, NULL, 0)
            """;
        jdbcTemplate.update(
                sql,
                challengeId,
                userId,
                email,
                PURPOSE_VERIFY_EMAIL,
                tokenHash,
                expiresAt,
                createdAt
        );
    }

    private void insertOutboxJob(
            UUID userId,
            UUID challengeId,
            String ciphertext,
            OffsetDateTime expiresAt) {
        String sql = """
            INSERT INTO background_jobs (requested_by, job_kind, payload, expires_at, idempotency_key)
            VALUES (?, 'AUTH_VERIFY_EMAIL', jsonb_build_object('schemaVersion', 1, 'challengeId', cast(? as text), 'ciphertext', cast(? as text)), ?, ?)
            """;
        jdbcTemplate.update(
                sql,
                userId,
                challengeId.toString(),
                ciphertext,
                expiresAt,
                challengeId.toString()
        );
    }

    private UserRow lockUserByChallenge(UUID challengeId) {
        String sql = """
            SELECT u.id, u.email, u.status
            FROM users u
            WHERE u.id = (
                SELECT a.user_id
                FROM auth_tokens a
                WHERE a.id = ? AND a.purpose = ?
            )
            FOR UPDATE
            """;
        try {
            return jdbcTemplate.queryForObject(sql, new UserRowMapper(), challengeId, PURPOSE_VERIFY_EMAIL);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private AuthTokenRow findTokenById(UUID challengeId, String currentEmail) {
        String sql = "SELECT id, user_id, email, purpose, token_hash, expires_at, created_at, used_at, attempts FROM auth_tokens WHERE id = ? AND email = cast(? as citext)";
        try {
            return jdbcTemplate.queryForObject(sql, new AuthTokenRowMapper(), challengeId, currentEmail);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private AuthTokenRow findLatestVerifyToken(UUID userId) {
        String sql = """
            SELECT id, user_id, email, purpose, token_hash, expires_at, created_at, used_at, attempts
            FROM auth_tokens
            WHERE user_id = ? AND purpose = ?
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """;
        List<AuthTokenRow> list = jdbcTemplate.query(sql, new AuthTokenRowMapper(), userId, PURPOSE_VERIFY_EMAIL);
        return list.isEmpty() ? null : list.get(0);
    }

    private void incrementTokenAttempts(UUID challengeId) {
        String sql = "UPDATE auth_tokens SET attempts = attempts + 1 WHERE id = ?";
        jdbcTemplate.update(sql, challengeId);
    }

    private void consumeToken(UUID challengeId, OffsetDateTime usedAt) {
        String sql = "UPDATE auth_tokens SET used_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, usedAt, challengeId);
    }

    private void activateUser(UUID userId, OffsetDateTime timestamp) {
        String sql = """
            UPDATE users
            SET status = ?, email_verified_at = ?, updated_at = ?, version = version + 1
            WHERE id = ?
            """;
        jdbcTemplate.update(sql, STATUS_ACTIVE, timestamp, timestamp, userId);
    }

    private void retireChallenge(UUID challengeId, OffsetDateTime usedAt) {
        String sql = "UPDATE auth_tokens SET used_at = ? WHERE id = ? AND used_at IS NULL";
        jdbcTemplate.update(sql, usedAt, challengeId);
    }

    private void enforceRateLimits(UUID userId, Instant latestCreatedAt, Instant now) {
        long secondsSinceLast = Duration.between(latestCreatedAt, now).toSeconds();
        if (secondsSinceLast < MIN_RESEND_INTERVAL.toSeconds()) {
            int wait = (int) Math.max(1, MIN_RESEND_INTERVAL.toSeconds() - secondsSinceLast);
            throw new Rejection("RATE_LIMITED", wait);
        }

        OffsetDateTime cutoffUtc = now.minus(HOURLY_WINDOW).atOffset(ZoneOffset.UTC);
        String sql = """
            SELECT created_at
            FROM auth_tokens
            WHERE user_id = ? AND purpose = ? AND created_at >= ?
            ORDER BY created_at ASC
            """;
        List<Instant> recentIssues = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                userId,
                PURPOSE_VERIFY_EMAIL,
                cutoffUtc
        );

        if (recentIssues.size() >= MAX_RESENDS_PER_HOUR) {
            int retainedIndex = recentIssues.size() - MAX_RESENDS_PER_HOUR;
            Instant oldestRetained = recentIssues.get(retainedIndex);
            Duration wait = Duration.between(now, oldestRetained.plus(HOURLY_WINDOW));
            int retryAfter = Math.toIntExact(Math.max(1L, wait.toSeconds() + (wait.getNano() > 0 ? 1L : 0L)));
            throw new Rejection("RATE_LIMITED", retryAfter);
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int atIndex = email.lastIndexOf('@');
        if (atIndex <= 0) {
            return "";
        }
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }

    private Instant databaseNow() {
        return jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    private record UserRow(UUID id, String email, String status) {}

    private record AuthTokenRow(
            UUID id,
            UUID userId,
            String email,
            String purpose,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt,
            Instant usedAt,
            int attempts) {}

    private static final class UserRowMapper implements RowMapper<UserRow> {
        @Override
        public UserRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new UserRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("status")
            );
        }
    }

    private static final class AuthTokenRowMapper implements RowMapper<AuthTokenRow> {
        @Override
        public AuthTokenRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
            OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
            OffsetDateTime usedAt = rs.getObject("used_at", OffsetDateTime.class);

            return new AuthTokenRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("email"),
                    rs.getString("purpose"),
                    rs.getString("token_hash"),
                    expiresAt != null ? expiresAt.toInstant() : null,
                    createdAt != null ? createdAt.toInstant() : null,
                    usedAt != null ? usedAt.toInstant() : null,
                    rs.getInt("attempts")
            );
        }
    }
}
