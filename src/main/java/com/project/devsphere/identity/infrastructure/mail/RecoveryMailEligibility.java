package com.project.devsphere.identity.infrastructure.mail;

import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class RecoveryMailEligibility {

    private static final int ADVISORY_LOCK_SEED = 142857;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<VerificationCodeCrypto> cryptoProvider;

    public RecoveryMailEligibility(JdbcTemplate jdbcTemplate, ObjectProvider<VerificationCodeCrypto> cryptoProvider) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.cryptoProvider = Objects.requireNonNull(cryptoProvider, "cryptoProvider must not be null");
    }

    public enum Status {
        BOUND,
        TERMINAL,
        ABORT
    }

    public record BindingResult(Status status, String errorCode, String email, UUID userId) {
        public static BindingResult bound(String email, UUID userId) {
            return new BindingResult(Status.BOUND, null, email, userId);
        }

        public static BindingResult terminal(String errorCode) {
            return new BindingResult(Status.TERMINAL, errorCode, null, null);
        }

        public static BindingResult abort(String errorCode) {
            return new BindingResult(Status.ABORT, errorCode, null, null);
        }
    }

    @Transactional
    public BindingResult bindAndValidate(
            UUID jobId,
            RecoveryMailPayload claimedPayload,
            String owner,
            int claimedAttempts,
            String decryptedOtp
    ) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(claimedPayload, "claimedPayload must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(decryptedOtp, "decryptedOtp must not be null");
        if (claimedAttempts < 1 || claimedAttempts > 5) {
            return BindingResult.abort("LOST_LEASE");
        }

        UUID challengeId = claimedPayload.challengeId();

        VerificationCodeCrypto crypto = cryptoProvider.getIfAvailable();
        if (crypto == null) {
            return BindingResult.abort("CRYPTO_UNAVAILABLE");
        }

        ChallengeHint hint;
        try {
            hint = jdbcTemplate.queryForObject(
                    "SELECT email, token_hash FROM auth_tokens WHERE id = ? AND purpose = 'RESET_PASSWORD'",
                    (rs, rowNum) -> new ChallengeHint(rs.getString("email"), rs.getString("token_hash")),
                    challengeId
            );
        } catch (EmptyResultDataAccessException ex) {
            return finishTerminalGuarded(jobId, owner, "INVALID_CHALLENGE", null, false);
        }

        if (hint == null || hint.email() == null || hint.tokenHash() == null) {
            return finishTerminalGuarded(jobId, owner, "INVALID_CHALLENGE", null, false);
        }

        String emailHint = hint.email();
        String tokenHashHint = hint.tokenHash();

        if (!crypto.matches(challengeId, decryptedOtp, tokenHashHint, VerificationCodeCrypto.Purpose.RESET_PASSWORD)) {
            return finishTerminalGuarded(jobId, owner, "HMAC_MISMATCH", null, false);
        }

        // 1. Lock user first by email (if exists)
        UserRecord user = null;
        try {
            user = jdbcTemplate.queryForObject(
                    "SELECT id, email, status, email_verified_at FROM users WHERE email = cast(? as citext) FOR UPDATE",
                    (rs, rowNum) -> {
                        OffsetDateTime verified = rs.getObject("email_verified_at", OffsetDateTime.class);
                        return new UserRecord(
                                rs.getObject("id", UUID.class),
                                rs.getString("email"),
                                rs.getString("status"),
                                verified != null ? verified.toInstant() : null
                        );
                    },
                    emailHint
            );
        } catch (EmptyResultDataAccessException ignored) {
            // User does not exist
        }

        // 2. Lock email advisory transaction lock
        acquireEmailAdvisoryLock(emailHint);

        // 3. Lock challenge token
        TokenRecord token;
        try {
            token = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, email, purpose, token_hash, expires_at, created_at, used_at, attempts
                    FROM auth_tokens
                    WHERE id = ? AND purpose = 'RESET_PASSWORD'
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
                    challengeId
            );
        } catch (EmptyResultDataAccessException ex) {
            return finishTerminalGuarded(jobId, owner, "MISSING_TOKEN", null, false);
        }

        // 4. Lock current background job
        JobRecord job;
        try {
            job = jdbcTemplate.queryForObject(
                    """
                    SELECT id, job_kind, status, attempts, locked_by, locked_until, expires_at, requested_by,
                           payload::text AS raw_payload,
                           idempotency_key
                    FROM background_jobs
                    WHERE id = ? AND job_kind = 'AUTH_RESET_PASSWORD'
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> {
                        OffsetDateTime until = rs.getObject("locked_until", OffsetDateTime.class);
                        OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                        return new JobRecord(
                                rs.getObject("id", UUID.class),
                                rs.getString("job_kind"),
                                rs.getString("status"),
                                rs.getInt("attempts"),
                                rs.getString("locked_by"),
                                until != null ? until.toInstant() : null,
                                exp != null ? exp.toInstant() : null,
                                rs.getObject("requested_by", UUID.class),
                                rs.getString("raw_payload"),
                                rs.getString("idempotency_key")
                        );
                    },
                    jobId
            );
        } catch (EmptyResultDataAccessException ex) {
            return finishTerminalGuarded(jobId, owner, "MISSING_JOB", null, false);
        }

        Instant dbNow = databaseNow();

        // Validate job lease and fence under locks
        if (!"RUNNING".equals(job.status())
                || !owner.equals(job.lockedBy())
                || job.lockedUntil() == null
                || !dbNow.isBefore(job.lockedUntil())) {
            return BindingResult.abort("LOST_LEASE");
        }
        if (job.attempts() != claimedAttempts) {
            return BindingResult.abort("LOST_LEASE");
        }
        if (job.expiresAt() != null && !dbNow.isBefore(job.expiresAt())) {
            return finishTerminalGuarded(jobId, owner, "JOB_EXPIRED", challengeId, token.userId() == null);
        }

        // Re-read and validate persisted payload shape & identity against claimed payload
        RecoveryMailPayload persistedPayload;
        try {
            persistedPayload = RecoveryMailPayload.parse(job.rawPayload(), job.idempotencyKey());
        } catch (IllegalArgumentException ex) {
            return finishTerminalGuarded(jobId, owner, "INVALID_PAYLOAD", challengeId, token.userId() == null);
        }

        if (persistedPayload.schemaVersion() != claimedPayload.schemaVersion()
                || !persistedPayload.challengeId().equals(claimedPayload.challengeId())
                || !persistedPayload.ciphertext().equals(claimedPayload.ciphertext())
                || !persistedPayload.rawJson().equals(claimedPayload.rawJson())
                || !job.idempotencyKey().equals(claimedPayload.challengeString())) {
            return finishTerminalGuarded(jobId, owner, "INVALID_PAYLOAD", challengeId, token.userId() == null);
        }

        // Validate token/job matching expiry and positive lifetime
        if (token.expiresAt() == null || job.expiresAt() == null || !token.expiresAt().equals(job.expiresAt())) {
            return finishTerminalGuarded(jobId, owner, "EXPIRY_MISMATCH", challengeId, token.userId() == null);
        }
        if (token.createdAt() == null || !token.expiresAt().isAfter(token.createdAt()) || !job.expiresAt().isAfter(dbNow)) {
            return finishTerminalGuarded(jobId, owner, "EXPIRY_MISMATCH", challengeId, token.userId() == null);
        }

        // Validate token under locks
        if (token.usedAt() != null
                || token.attempts() >= 5
                || token.expiresAt() == null
                || !dbNow.isBefore(token.expiresAt())
                || !crypto.matches(challengeId, decryptedOtp, token.tokenHash(), VerificationCodeCrypto.Purpose.RESET_PASSWORD)) {
            return finishTerminalGuarded(jobId, owner, "TOKEN_UNUSABLE", challengeId, token.userId() == null);
        }

        // Validate that token's current email matches locked user's current email under PostgreSQL citext equality
        boolean emailMatches = user != null && token.email() != null && user.email() != null
                && token.email().equalsIgnoreCase(user.email())
                && Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                        "SELECT cast(? as citext) = cast(? as citext)",
                        Boolean.class,
                        token.email(),
                        user.email()
                ));

        // Check recipient eligibility (verified at issuance: emailVerifiedAt <= token.createdAt)
        boolean eligible = user != null
                && "ACTIVE".equals(user.status())
                && user.emailVerifiedAt() != null
                && token.createdAt() != null
                && !user.emailVerifiedAt().isAfter(token.createdAt())
                && emailMatches;

        if (!eligible) {
            // Ineligible candidate: consume never-bound challenge if still unused under email lock and finish job terminally
            return finishTerminalGuarded(jobId, owner, "INELIGIBLE_RECIPIENT", challengeId, token.userId() == null);
        }

        // Check requested_by binding consistency
        if (token.userId() != null) {
            if (!token.userId().equals(user.id())
                    || job.requestedBy() == null
                    || !job.requestedBy().equals(user.id())
                    || !emailMatches) {
                return finishTerminalGuarded(jobId, owner, "BINDING_MISMATCH", challengeId, false);
            }
            return BindingResult.bound(user.email(), user.id());
        }

        if (job.requestedBy() != null) {
            return finishTerminalGuarded(jobId, owner, "BINDING_MISMATCH", challengeId, false);
        }

        // Check newest previously bound OTP for this owner/current email (high-water mark via SQL composite ordering)
        // Note: prev.user_id = user.id() (non-null UUID) strictly excludes NULL rows; unbound tokens do not supersede
        Boolean hasNewerBound = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM auth_tokens prev
                    WHERE prev.user_id = ?
                      AND prev.email = cast(? as citext)
                      AND prev.purpose = 'RESET_PASSWORD'
                      AND prev.id <> ?
                      AND (prev.created_at, prev.id) > (?, ?)
                )
                """,
                Boolean.class,
                user.id(),
                user.email(),
                challengeId,
                token.createdAt().atOffset(ZoneOffset.UTC),
                token.id()
        );

        if (Boolean.TRUE.equals(hasNewerBound)) {
            // Older late job cannot revive or supersede newer binding
            return finishTerminalGuarded(jobId, owner, "SUPERSEDED_BY_NEWER_CHALLENGE", challengeId, true);
        }

        // Atomically bind challenge token and background job
        int updatedToken = jdbcTemplate.update(
                "UPDATE auth_tokens SET user_id = ? WHERE id = ? AND user_id IS NULL",
                user.id(),
                challengeId
        );
        int updatedJob = jdbcTemplate.update(
                """
                UPDATE background_jobs
                SET requested_by = ?
                WHERE id = ? AND job_kind = 'AUTH_RESET_PASSWORD' AND status = 'RUNNING'
                  AND locked_by = ? AND locked_until > clock_timestamp() AND requested_by IS NULL
                """,
                user.id(),
                jobId,
                owner
        );

        if (updatedToken != 1 || updatedJob != 1) {
            throw new IllegalStateException("Failed to atomically bind token and job");
        }

        // Retire older unused bound recovery OTPs for this owner/current email
        jdbcTemplate.update(
                """
                UPDATE auth_tokens
                SET used_at = clock_timestamp()
                WHERE user_id = ?
                  AND email = cast(? as citext)
                  AND purpose = 'RESET_PASSWORD'
                  AND id <> ?
                  AND used_at IS NULL
                """,
                user.id(),
                user.email(),
                challengeId
        );

        return BindingResult.bound(user.email(), user.id());
    }

    public boolean isEligibleToSend(
            UUID jobId,
            RecoveryMailPayload claimedPayload,
            String owner,
            int claimedAttempts,
            String decryptedOtp
    ) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(claimedPayload, "claimedPayload must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        if (claimedAttempts < 1 || claimedAttempts > 5) {
            return false;
        }
        if (decryptedOtp == null || decryptedOtp.isBlank()) {
            return false;
        }

        String sql = """
            SELECT t.id AS token_id, t.email AS token_email, t.used_at AS token_used_at,
                   t.attempts AS token_attempts, t.expires_at AS token_expires_at, t.created_at AS token_created_at,
                   t.token_hash, t.user_id AS token_user_id,
                   u.id AS user_id, u.status AS user_status, u.email_verified_at AS user_verified_at, u.email AS user_email,
                   j.status AS job_status, j.locked_by AS job_locked_by, j.locked_until AS job_locked_until,
                   j.expires_at AS job_expires_at, j.attempts AS job_attempts, j.requested_by AS job_requested_by,
                   j.payload::text AS raw_payload, j.idempotency_key,
                   NOT EXISTS (
                       SELECT 1 FROM auth_tokens t2
                       WHERE t2.user_id = u.id AND t2.email = u.email AND t2.purpose = 'RESET_PASSWORD'
                         AND t2.id <> t.id AND (t2.created_at, t2.id) > (t.created_at, t.id)
                   ) AS is_newest_bound
            FROM auth_tokens t
            JOIN users u ON u.id = t.user_id
            JOIN background_jobs j ON j.id = ? AND j.requested_by = t.user_id
            WHERE t.id = ?
              AND t.purpose = 'RESET_PASSWORD'
              AND j.job_kind = 'AUTH_RESET_PASSWORD'
            """;
        List<PreSendCheck> checks = jdbcTemplate.query(sql, (rs, rowNum) -> {
            OffsetDateTime tExp = rs.getObject("token_expires_at", OffsetDateTime.class);
            OffsetDateTime tCr = rs.getObject("token_created_at", OffsetDateTime.class);
            OffsetDateTime tUsed = rs.getObject("token_used_at", OffsetDateTime.class);
            OffsetDateTime uVer = rs.getObject("user_verified_at", OffsetDateTime.class);
            OffsetDateTime jUntil = rs.getObject("job_locked_until", OffsetDateTime.class);
            OffsetDateTime jExp = rs.getObject("job_expires_at", OffsetDateTime.class);

            return new PreSendCheck(
                    rs.getObject("token_id", UUID.class),
                    rs.getString("token_email"),
                    tUsed != null ? tUsed.toInstant() : null,
                    rs.getInt("token_attempts"),
                    tExp != null ? tExp.toInstant() : null,
                    tCr != null ? tCr.toInstant() : null,
                    rs.getString("token_hash"),
                    rs.getObject("token_user_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("user_status"),
                    uVer != null ? uVer.toInstant() : null,
                    rs.getString("user_email"),
                    rs.getString("job_status"),
                    rs.getString("job_locked_by"),
                    jUntil != null ? jUntil.toInstant() : null,
                    jExp != null ? jExp.toInstant() : null,
                    rs.getInt("job_attempts"),
                    rs.getObject("job_requested_by", UUID.class),
                    rs.getString("raw_payload"),
                    rs.getString("idempotency_key"),
                    rs.getBoolean("is_newest_bound")
            );
        }, jobId, claimedPayload.challengeId());

        if (checks.isEmpty()) {
            return false;
        }

        PreSendCheck c = checks.get(0);
        Instant dbNow = databaseNow();

        // 1. Current lease, owner, and attempts fence
        if (!"RUNNING".equals(c.jobStatus())
                || !owner.equals(c.jobLockedBy())
                || c.jobLockedUntil() == null
                || !dbNow.isBefore(c.jobLockedUntil())) {
            return false;
        }
        if (c.jobAttempts() != claimedAttempts) {
            return false;
        }
        if (c.jobExpiresAt() == null || !dbNow.isBefore(c.jobExpiresAt())) {
            return false;
        }

        // 2. Token usability and positive lifetime
        if (c.tokenUsedAt() != null
                || c.tokenAttempts() >= 5
                || c.tokenExpiresAt() == null
                || !dbNow.isBefore(c.tokenExpiresAt())) {
            return false;
        }

        // 3. Token/job expiry equality
        if (!c.tokenExpiresAt().equals(c.jobExpiresAt())) {
            return false;
        }
        if (c.tokenCreatedAt() == null || !c.tokenExpiresAt().isAfter(c.tokenCreatedAt())) {
            return false;
        }

        // 4. Current persisted payload and identity against claimed payload
        RecoveryMailPayload persistedPayload;
        try {
            persistedPayload = RecoveryMailPayload.parse(c.rawPayload(), c.idempotencyKey());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (persistedPayload.schemaVersion() != claimedPayload.schemaVersion()
                || !persistedPayload.challengeId().equals(claimedPayload.challengeId())
                || !persistedPayload.ciphertext().equals(claimedPayload.ciphertext())
                || !persistedPayload.rawJson().equals(claimedPayload.rawJson())
                || !c.idempotencyKey().equals(claimedPayload.challengeString())) {
            return false;
        }

        // 5. Challenge, token hash, and recipient binding
        if (!c.tokenId().equals(claimedPayload.challengeId())) {
            return false;
        }
        VerificationCodeCrypto crypto = cryptoProvider.getIfAvailable();
        if (crypto == null || !crypto.matches(claimedPayload.challengeId(), decryptedOtp, c.tokenHash(), VerificationCodeCrypto.Purpose.RESET_PASSWORD)) {
            return false;
        }
        if (c.userEmail() == null || c.tokenEmail() == null || !c.userEmail().equalsIgnoreCase(c.tokenEmail())) {
            return false;
        }
        if (c.userId() == null || !c.userId().equals(c.tokenUserId()) || !c.userId().equals(c.jobRequestedBy())) {
            return false;
        }

        // 6. User active and verified at issuance
        if (!"ACTIVE".equals(c.userStatus())
                || c.userVerifiedAt() == null
                || c.userVerifiedAt().isAfter(c.tokenCreatedAt())) {
            return false;
        }

        // 7. High-water mark
        return c.isNewestBound();
    }

    private BindingResult finishTerminalGuarded(UUID jobId, String owner, String errorCode, UUID challengeId, boolean consumeChallenge) {
        int rows = jdbcTemplate.update(
                """
                UPDATE background_jobs
                SET status = 'FAILED',
                    finished_at = clock_timestamp(),
                    last_error = ?,
                    payload = payload - 'ciphertext',
                    locked_by = NULL,
                    locked_until = NULL
                WHERE id = ? AND status = 'RUNNING' AND job_kind = 'AUTH_RESET_PASSWORD'
                  AND locked_by = ? AND locked_until > clock_timestamp()
                """,
                errorCode,
                jobId,
                owner
        );

        if (rows == 1 && consumeChallenge && challengeId != null) {
            jdbcTemplate.update(
                    "UPDATE auth_tokens SET used_at = clock_timestamp() WHERE id = ? AND used_at IS NULL AND user_id IS NULL",
                    challengeId
            );
        }
        return BindingResult.terminal(errorCode);
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

    private record ChallengeHint(String email, String tokenHash) {}
    private record UserRecord(UUID id, String email, String status, Instant emailVerifiedAt) {}
    private record TokenRecord(UUID id, UUID userId, String email, String purpose, String tokenHash, Instant expiresAt, Instant createdAt, Instant usedAt, int attempts) {}
    private record JobRecord(UUID id, String jobKind, String status, int attempts, String lockedBy, Instant lockedUntil, Instant expiresAt, UUID requestedBy, String rawPayload, String idempotencyKey) {}
    private record PreSendCheck(
            UUID tokenId,
            String tokenEmail,
            Instant tokenUsedAt,
            int tokenAttempts,
            Instant tokenExpiresAt,
            Instant tokenCreatedAt,
            String tokenHash,
            UUID tokenUserId,
            UUID userId,
            String userStatus,
            Instant userVerifiedAt,
            String userEmail,
            String jobStatus,
            String jobLockedBy,
            Instant jobLockedUntil,
            Instant jobExpiresAt,
            int jobAttempts,
            UUID jobRequestedBy,
            String rawPayload,
            String idempotencyKey,
            boolean isNewestBound
    ) {}
}
