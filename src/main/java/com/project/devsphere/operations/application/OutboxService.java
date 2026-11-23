package com.project.devsphere.operations.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.operations.infrastructure.persistence.JobQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional outbox writer for {@code background_jobs}.
 *
 * <p>Jobs are inserted in the caller's transaction, so they become visible to workers only when the
 * business change commits. Each job carries a deterministic idempotency key and the SHA-256 hash of
 * its canonical payload: re-enqueueing the same key with the same payload returns the existing job,
 * while the same key with a different payload is rejected as a conflict.</p>
 */
@Service
public class OutboxService {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 180;

    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "passwordhash", "apikey", "accesstoken", "refreshtoken", "secret", "privatekey", "clientsecret");

    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final JdbcTemplate jdbc;

    public OutboxService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    /** Job to enqueue; {@code scheduledAt} defaults to "now" when null. */
    public record EnqueueRequest(
            BackgroundJob.JobKind kind,
            UUID workspaceId,
            UUID projectId,
            UUID requestedBy,
            String idempotencyKey,
            Map<String, Object> payload,
            Instant scheduledAt) {
    }

    /** Identifier of the enqueued job and whether this call created it. */
    public record EnqueueResult(UUID jobId, boolean created, String idempotencyKey, String requestHash) {
    }

    /**
     * Records a domain event for asynchronous publication. The idempotency key is derived from the
     * event type, aggregate type and aggregate id plus an optional discriminator.
     */
    @Transactional
    public EnqueueResult enqueueOutboxEvent(
            UUID workspaceId,
            UUID projectId,
            UUID requestedBy,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            String discriminator,
            Map<String, Object> data) {
        if (eventType == null || !eventType.matches("[A-Z][A-Z0-9_]{1,99}")) {
            throw OperationsException.validationFailed("eventType must be an UPPER_SNAKE_CASE identifier");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw OperationsException.validationFailed("aggregateType must not be blank");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("aggregateType", aggregateType);
        if (aggregateId != null) {
            payload.put("aggregateId", aggregateId.toString());
        }
        payload.put("data", data == null ? Map.of() : data);
        String key = idempotencyKey("outbox", eventType, aggregateType,
                aggregateId == null ? "none" : aggregateId.toString(),
                discriminator == null ? "" : discriminator);
        return enqueueJob(new EnqueueRequest(BackgroundJob.JobKind.OUTBOX_EVENT, workspaceId, projectId, requestedBy, key, payload, null));
    }

    @Transactional
    public EnqueueResult enqueueJob(EnqueueRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        BackgroundJob.JobKind kind = request.kind();
        if (kind == null || JobQueue.NON_DISPATCHABLE_KINDS.contains(kind)) {
            throw OperationsException.validationFailed("Job kind cannot be enqueued through the outbox: " + kind);
        }
        if (request.projectId() != null && request.workspaceId() == null) {
            throw OperationsException.validationFailed("workspaceId is required when projectId is set");
        }
        String key = request.idempotencyKey();
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw OperationsException.validationFailed("idempotencyKey of 1.." + MAX_IDEMPOTENCY_KEY_LENGTH + " characters is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        if (request.payload() != null) {
            request.payload().forEach((name, value) -> {
                if (!"schemaVersion".equals(name)) {
                    payload.put(name, value);
                }
            });
        }
        rejectSecrets(payload);
        String payloadJson = canonicalJson(payload);
        String hash = sha256(payloadJson);
        Instant scheduledAt = request.scheduledAt() != null ? request.scheduledAt() : Instant.now();

        List<UUID> inserted = jdbc.query("""
                insert into background_jobs(workspace_id,project_id,requested_by,job_kind,idempotency_key,request_hash,payload,scheduled_at)
                values (?,?,?,?,?,?,?::jsonb,?)
                on conflict (workspace_id,project_id,job_kind,idempotency_key) where idempotency_key is not null do nothing
                returning id
                """, (rs, n) -> rs.getObject(1, UUID.class),
                request.workspaceId(), request.projectId(), request.requestedBy(), kind.name(), key, hash, payloadJson,
                Timestamp.from(scheduledAt));
        if (!inserted.isEmpty()) {
            return new EnqueueResult(inserted.getFirst(), true, key, hash);
        }

        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id,request_hash from background_jobs
                where workspace_id is not distinct from ?::uuid and project_id is not distinct from ?::uuid
                  and job_kind=? and idempotency_key=?
                """, request.workspaceId(), request.projectId(), kind.name(), key);
        if (existing.isEmpty()) {
            throw new IllegalStateException("Idempotent job disappeared during enqueue");
        }
        Map<String, Object> row = existing.getFirst();
        String storedHash = row.get("request_hash") == null ? null : row.get("request_hash").toString().trim();
        if (!hash.equals(storedHash)) {
            throw OperationsException.idempotencyConflict("Idempotency key is already bound to a different request");
        }
        return new EnqueueResult((UUID) row.get("id"), false, key, hash);
    }

    /**
     * Builds a deterministic idempotency key by joining the parts with ':'. Keys that would exceed
     * the column limit keep a readable prefix followed by a SHA-256 digest of the full key.
     */
    public static String idempotencyKey(String... parts) {
        if (parts == null || parts.length == 0) {
            throw OperationsException.validationFailed("At least one idempotency key part is required");
        }
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            if (!key.isEmpty()) {
                key.append(':');
            }
            key.append(part == null ? "" : part.strip());
        }
        String value = key.toString();
        if (value.length() <= MAX_IDEMPOTENCY_KEY_LENGTH) {
            return value;
        }
        String digest = sha256(value);
        return value.substring(0, MAX_IDEMPOTENCY_KEY_LENGTH - digest.length() - 1) + "#" + digest;
    }

    /** SHA-256 (lower-case hex) of the canonical, key-ordered JSON form of the payload. */
    public static String requestHash(Map<String, Object> payload) {
        return sha256(canonicalJson(payload == null ? Map.of() : payload));
    }

    static String canonicalJson(Map<String, Object> payload) {
        try {
            return CANONICAL_JSON.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw OperationsException.validationFailed("Job payload cannot be serialised as JSON");
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Mirrors the database ds_json_has_secret guard so callers receive a clear validation error. */
    static void rejectSecrets(Object document) {
        if (document instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).replace("_", "").toLowerCase(Locale.ROOT);
                if (SECRET_KEYS.contains(key) && entry.getValue() != null) {
                    throw OperationsException.validationFailed("Job payload must not contain secret attributes");
                }
                rejectSecrets(entry.getValue());
            }
        } else if (document instanceof Iterable<?> items) {
            for (Object item : items) {
                rejectSecrets(item);
            }
        }
    }
}
