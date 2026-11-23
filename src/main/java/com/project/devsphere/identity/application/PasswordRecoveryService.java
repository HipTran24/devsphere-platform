package com.project.devsphere.identity.application;

import com.project.devsphere.identity.domain.PasswordResetGrantToken;
import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class PasswordRecoveryService {

    private static final int ADVISORY_LOCK_SEED = 142857;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<VerificationCodeCrypto> cryptoProvider;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final PasswordEncoder passwordEncoder;
    private final String mailFrom;
    private final String smtpHost;

    private final LoginAttemptLimiter forgotPasswordLimiter = new LoginAttemptLimiter();
    private final LoginAttemptLimiter verifyLimiter = new LoginAttemptLimiter();
    private final LoginAttemptLimiter resetLimiter = new LoginAttemptLimiter();

    public PasswordRecoveryService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectProvider<VerificationCodeCrypto> cryptoProvider,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            PasswordEncoder passwordEncoder,
            @Value("${devsphere.auth.mail.from:}") String mailFrom,
            @Value("${spring.mail.host:}") String smtpHost) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.cryptoProvider = Objects.requireNonNull(cryptoProvider, "cryptoProvider must not be null");
        this.mailSenderProvider = Objects.requireNonNull(mailSenderProvider, "mailSenderProvider must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.mailFrom = mailFrom;
        this.smtpHost = smtpHost;
    }

    public record VerifyRecoveryResult(boolean accepted, String resetGrant, Instant expiresAt) {
        public static VerifyRecoveryResult rejected() {
            return new VerifyRecoveryResult(false, null, null);
        }

        public static VerifyRecoveryResult accepted(String resetGrant, Instant expiresAt) {
            return new VerifyRecoveryResult(true, resetGrant, expiresAt);
        }

        @Override
        public String toString() {
            return "VerifyRecoveryResult[redacted]";
        }
    }

    public static class RecoveryException extends RuntimeException {
        private final String code;
        private final int retryAfterSeconds;

        public RecoveryException(String code, int retryAfterSeconds) {
            super(code);
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() {
            return code;
        }

        public int getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    public static class AuthNotConfiguredException extends RecoveryException {
        public AuthNotConfiguredException() {
            super("AUTH_NOT_CONFIGURED", 0);
        }
    }

    public static class RateLimitedException extends RecoveryException {
        public RateLimitedException(int retryAfterSeconds) {
            super("RATE_LIMITED", retryAfterSeconds);
        }
    }

    public static class ResetCodeInvalidException extends RecoveryException {
        public ResetCodeInvalidException() {
            super("RESET_CODE_INVALID", 0);
        }
    }

    public static class ResetGrantInvalidException extends RecoveryException {
        public ResetGrantInvalidException() {
            super("RESET_GRANT_INVALID", 0);
        }
    }

    public void requestPasswordRecovery(String email, String peerIp) {
        requireConfiguredCryptoAndMail();

        String normalizedEmail = normalizeEmail(email);

        Duration wait = forgotPasswordLimiter.retryAfter(normalizedEmail, peerIp);
        if (!wait.isZero()) {
            return;
        }

        VerificationCodeCrypto crypto = cryptoProvider.getIfAvailable();

        transactionTemplate.executeWithoutResult(status -> {
            acquireEmailAdvisoryLock(normalizedEmail);

            // Capture one DB issuance instant after advisory lock
            OffsetDateTime dbNow = jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            Instant now = dbNow.toInstant();

            // 60-second cooldown check from latest issue
            List<OffsetDateTime> latestList = jdbcTemplate.query(
                    """
                    SELECT created_at
                    FROM auth_tokens
                    WHERE email = cast(? as citext)
                      AND purpose = 'RESET_PASSWORD'
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                    """,
                    (rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class),
                    normalizedEmail
            );

            if (!latestList.isEmpty()) {
                OffsetDateTime latest = latestList.get(0);
                long secondsSinceLatest = Duration.between(latest.toInstant(), now).getSeconds();
                if (secondsSinceLatest < 60) {
                    return;
                }
            }

            // Rolling hour limit: strictly greater than now-minus-hour so one-hour anniversary is eligible
            Integer recentCount = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM auth_tokens
                    WHERE email = cast(? as citext)
                      AND purpose = 'RESET_PASSWORD'
                      AND created_at > (cast(? as timestamptz) - INTERVAL '1 hour')
                    """,
                    Integer.class,
                    normalizedEmail,
                    dbNow
            );

            if (recentCount != null && recentCount >= 5) {
                return;
            }

            UUID challengeId = UUID.randomUUID();
            String otp = crypto.generateCode();
            String tokenHash = crypto.hash(challengeId, otp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
            String ciphertext = crypto.encrypt(challengeId, otp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

            OffsetDateTime dbExpiry = dbNow.plusMinutes(10);

            jdbcTemplate.update(
                    """
                    INSERT INTO auth_tokens (id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                    VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                    """,
                    challengeId,
                    normalizedEmail,
                    tokenHash,
                    dbNow,
                    dbExpiry
            );

            jdbcTemplate.update(
                    """
                    INSERT INTO background_jobs (job_kind, idempotency_key, payload, expires_at, scheduled_at)
                    VALUES ('AUTH_RESET_PASSWORD', cast(? as text),
                            jsonb_build_object('schemaVersion', 1, 'challengeId', cast(? as text), 'ciphertext', cast(? as text)),
                            ?, ?)
                    """,
                    challengeId.toString(),
                    challengeId.toString(),
                    ciphertext,
                    dbExpiry,
                    dbNow
            );
        });
    }

    public VerifyRecoveryResult verifyRecoveryCode(String email, String code, String peerIp) {
        String normalizedEmail = normalizeEmail(email);

        Duration wait = verifyLimiter.retryAfter(normalizedEmail, peerIp);
        if (!wait.isZero()) {
            throw new RateLimitedException(toPositiveSeconds(wait));
        }

        VerificationCodeCrypto crypto = cryptoProvider.getIfAvailable();
        if (crypto == null) {
            throw new AuthNotConfiguredException();
        }

        return transactionTemplate.execute(status -> {
            UserRecord user = null;
            try {
                user = jdbcTemplate.queryForObject(
                        "SELECT id, email, status, email_verified_at FROM users WHERE email = cast(? as citext) FOR UPDATE",
                        (rs, rowNum) -> {
                            OffsetDateTime ver = rs.getObject("email_verified_at", OffsetDateTime.class);
                            return new UserRecord(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("email"),
                                    rs.getString("status"),
                                    ver != null ? ver.toInstant() : null
                            );
                        },
                        normalizedEmail
                );
            } catch (EmptyResultDataAccessException ex) {
                return VerifyRecoveryResult.rejected();
            }

            if (user == null || !"ACTIVE".equals(user.status()) || user.emailVerifiedAt() == null) {
                return VerifyRecoveryResult.rejected();
            }

            TokenRecord latestToken = null;
            try {
                latestToken = jdbcTemplate.queryForObject(
                        """
                        SELECT id, user_id, email, purpose, token_hash, expires_at, created_at, used_at, attempts
                        FROM auth_tokens
                        WHERE user_id = ? AND email = cast(? as citext) AND purpose = 'RESET_PASSWORD'
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        FOR UPDATE
                        """,
                        (rs, rowNum) -> {
                            OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                            OffsetDateTime cr = rs.getObject("created_at", OffsetDateTime.class);
                            OffsetDateTime used = rs.getObject("used_at", OffsetDateTime.class);
                            return new TokenRecord(
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("user_id", UUID.class),
                                    rs.getString("email"),
                                    rs.getString("purpose"),
                                    rs.getString("token_hash"),
                                    exp != null ? exp.toInstant() : null,
                                    cr != null ? cr.toInstant() : null,
                                    used != null ? used.toInstant() : null,
                                    rs.getInt("attempts")
                            );
                        },
                        user.id(),
                        user.email()
                );
            } catch (EmptyResultDataAccessException ex) {
                return VerifyRecoveryResult.rejected();
            }

            Instant now = databaseNow();
            boolean isLive = latestToken.usedAt() == null
                    && latestToken.attempts() < 5
                    && latestToken.expiresAt() != null
                    && now.isBefore(latestToken.expiresAt());

            if (!isLive) {
                return VerifyRecoveryResult.rejected();
            }

            boolean matches = crypto.matches(
                    latestToken.id(),
                    code,
                    latestToken.tokenHash(),
                    VerificationCodeCrypto.Purpose.RESET_PASSWORD
            );

            if (!matches) {
                jdbcTemplate.update("UPDATE auth_tokens SET attempts = attempts + 1 WHERE id = ?", latestToken.id());
                return VerifyRecoveryResult.rejected();
            }

            // Matched: consume recovery token
            jdbcTemplate.update("UPDATE auth_tokens SET used_at = clock_timestamp() WHERE id = ?", latestToken.id());

            // Retire previous unused grants for the user
            jdbcTemplate.update(
                    """
                    UPDATE auth_tokens
                    SET used_at = clock_timestamp()
                    WHERE user_id = ? AND purpose = 'RESET_PASSWORD_GRANT' AND used_at IS NULL
                    """,
                    user.id()
            );

            // Generate reset grant using one DB issuance instant
            OffsetDateTime grantIssuance = jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            OffsetDateTime grantExpiry = grantIssuance.plusMinutes(10);
            PasswordResetGrantToken grantToken = PasswordResetGrantToken.generate();
            UUID grantId = UUID.randomUUID();

            jdbcTemplate.update(
                    """
                    INSERT INTO auth_tokens (id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                    VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, ?, ?, NULL, 0)
                    """,
                    grantId,
                    user.id(),
                    user.email(),
                    grantToken.digest(),
                    grantIssuance,
                    grantExpiry
            );

            return VerifyRecoveryResult.accepted(grantToken.rawGrant(), grantExpiry.toInstant());
        });
    }

    public int resetPassword(String resetGrant, String newPassword, String peerIp) {
        Duration wait = resetLimiter.retryAfter(peerIp, peerIp);
        if (!wait.isZero()) {
            throw new RateLimitedException(toPositiveSeconds(wait));
        }

        String grantDigest;
        try {
            grantDigest = PasswordResetGrantToken.digest(resetGrant);
        } catch (IllegalArgumentException ex) {
            throw new ResetGrantInvalidException();
        }

        // Shallow lookup outside transaction matching user email under DB citext equality
        UserTokenHint hint;
        try {
            hint = jdbcTemplate.queryForObject(
                    """
                    SELECT t.user_id, t.email
                    FROM auth_tokens t
                    JOIN users u ON u.id = t.user_id AND u.email = t.email
                    WHERE t.token_hash = ?
                      AND t.purpose = 'RESET_PASSWORD_GRANT'
                      AND t.used_at IS NULL
                      AND t.expires_at > clock_timestamp()
                      AND u.status = 'ACTIVE'
                      AND u.email_verified_at IS NOT NULL
                    """,
                    (rs, rowNum) -> new UserTokenHint(rs.getObject("user_id", UUID.class), rs.getString("email")),
                    grantDigest
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new ResetGrantInvalidException();
        }

        String passwordHash = passwordEncoder.encode(newPassword);

        return transactionTemplate.execute(status -> {
            // Lock user first
            UserRecord user;
            try {
                user = jdbcTemplate.queryForObject(
                        "SELECT id, email, status, email_verified_at FROM users WHERE id = ? AND email = cast(? as citext) FOR UPDATE",
                        (rs, rowNum) -> {
                            OffsetDateTime ver = rs.getObject("email_verified_at", OffsetDateTime.class);
                            return new UserRecord(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("email"),
                                    rs.getString("status"),
                                    ver != null ? ver.toInstant() : null
                            );
                        },
                        hint.userId(),
                        hint.email()
                );
            } catch (EmptyResultDataAccessException ex) {
                throw new ResetGrantInvalidException();
            }

            if (user == null || !"ACTIVE".equals(user.status()) || user.emailVerifiedAt() == null) {
                throw new ResetGrantInvalidException();
            }

            // Lock email advisory lock
            acquireEmailAdvisoryLock(user.email());

            // Re-read grant under lock using DB clock
            TokenRecord grant;
            try {
                grant = jdbcTemplate.queryForObject(
                        """
                        SELECT id, user_id, email, purpose, token_hash, expires_at, created_at, used_at, attempts
                        FROM auth_tokens
                        WHERE token_hash = ? AND purpose = 'RESET_PASSWORD_GRANT' AND user_id = ? AND email = cast(? as citext)
                        FOR UPDATE
                        """,
                        (rs, rowNum) -> {
                            OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                            OffsetDateTime cr = rs.getObject("created_at", OffsetDateTime.class);
                            OffsetDateTime used = rs.getObject("used_at", OffsetDateTime.class);
                            return new TokenRecord(
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("user_id", UUID.class),
                                    rs.getString("email"),
                                    rs.getString("purpose"),
                                    rs.getString("token_hash"),
                                    exp != null ? exp.toInstant() : null,
                                    cr != null ? cr.toInstant() : null,
                                    used != null ? used.toInstant() : null,
                                    rs.getInt("attempts")
                            );
                        },
                        grantDigest,
                        user.id(),
                        user.email()
                );
            } catch (EmptyResultDataAccessException ex) {
                throw new ResetGrantInvalidException();
            }

            OffsetDateTime dbNow = jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            Instant now = dbNow.toInstant();
            if (grant.usedAt() != null || grant.expiresAt() == null || !now.isBefore(grant.expiresAt())) {
                throw new ResetGrantInvalidException();
            }

            // Consume grant once
            int updatedGrant = jdbcTemplate.update(
                    "UPDATE auth_tokens SET used_at = clock_timestamp() WHERE id = ? AND used_at IS NULL",
                    grant.id()
            );
            if (updatedGrant != 1) {
                throw new ResetGrantInvalidException();
            }

            // Invalidate remaining unused recovery grants and bound recovery tokens for this user
            jdbcTemplate.update(
                    """
                    UPDATE auth_tokens
                    SET used_at = clock_timestamp()
                    WHERE user_id = ?
                      AND purpose IN ('RESET_PASSWORD_GRANT', 'RESET_PASSWORD')
                      AND id <> ?
                      AND used_at IS NULL
                    """,
                    user.id(),
                    grant.id()
            );

            // Invalidate unused unbound recovery OTPs for that current email admitted before the reset
            jdbcTemplate.update(
                    """
                    UPDATE auth_tokens
                    SET used_at = clock_timestamp()
                    WHERE user_id IS NULL
                      AND email = cast(? as citext)
                      AND purpose = 'RESET_PASSWORD'
                      AND used_at IS NULL
                      AND created_at <= ?
                    """,
                    user.email(),
                    dbNow
            );

            // Update password hash, updated_at, version
            int updatedUser = jdbcTemplate.update(
                    """
                    UPDATE users
                    SET password_hash = ?, updated_at = clock_timestamp(), version = version + 1
                    WHERE id = ?
                    """,
                    passwordHash,
                    user.id()
            );
            if (updatedUser != 1) {
                throw new ResetGrantInvalidException();
            }

            // Revoke every unrevoked session row across all families
            return jdbcTemplate.update(
                    """
                    UPDATE auth_sessions
                    SET revoked_at = clock_timestamp()
                    WHERE user_id = ? AND revoked_at IS NULL
                    """,
                    user.id()
            );
        });
    }

    private void requireConfiguredCryptoAndMail() {
        VerificationCodeCrypto crypto = cryptoProvider.getIfAvailable();
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (crypto == null || mailSender == null || mailFrom == null || mailFrom.isBlank()
                || smtpHost == null || smtpHost.isBlank()) {
            throw new AuthNotConfiguredException();
        }
    }

    private String normalizeEmail(String email) {
        return email != null ? email.strip().toLowerCase(Locale.ROOT) : "";
    }

    private void acquireEmailAdvisoryLock(String email) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(?, hashtext(lower(cast(? as text))))",
                (ResultSetExtractor<Void>) rs -> null,
                ADVISORY_LOCK_SEED,
                email
        );
    }

    private Instant databaseNow() {
        return jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    private int toPositiveSeconds(Duration duration) {
        long sec = duration.toSeconds();
        if (duration.getNano() > 0) {
            sec += 1;
        }
        return Math.toIntExact(Math.max(1L, sec));
    }

    private record UserRecord(UUID id, String email, String status, Instant emailVerifiedAt) {}
    private record TokenRecord(UUID id, UUID userId, String email, String purpose, String tokenHash, Instant expiresAt, Instant createdAt, Instant usedAt, int attempts) {}
    private record UserTokenHint(UUID userId, String email) {}
}
