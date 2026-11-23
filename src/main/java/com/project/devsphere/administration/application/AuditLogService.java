package com.project.devsphere.administration.application;

import com.project.devsphere.administration.domain.AuditLog;
import com.project.devsphere.administration.infrastructure.persistence.AuditLogRepository;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Single application-level entry point for writing immutable audit events.
 *
 * <p>Every event is attributed to the acting user through the transaction-local
 * {@code devsphere.actor_id} setting (so database triggers fired in the same transaction agree on
 * the actor) and is correlated with the request trace held in the SLF4J MDC.</p>
 */
@Service
public class AuditLogService {

    public static final String TRACE_ID_MDC_KEY = "traceId";

    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "passwordhash", "apikey", "accesstoken", "refreshtoken", "secret", "privatekey", "clientsecret");
    private static final int MAX_ACTION_LENGTH = 120;
    private static final int MAX_ENTITY_TYPE_LENGTH = 100;

    private final AuditLogRepository auditLogRepository;
    private final JdbcTemplate jdbcTemplate;

    public AuditLogService(AuditLogRepository auditLogRepository, JdbcTemplate jdbcTemplate) {
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /** Immutable description of one audit event. */
    public record AuditEvent(
            UUID workspaceId,
            UUID projectId,
            UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> before,
            Map<String, Object> after,
            Map<String, Object> metadata) {

        public static AuditEvent system(UUID actorId, String action, String entityType, UUID entityId, Map<String, Object> metadata) {
            return new AuditEvent(null, null, actorId, action, entityType, entityId, null, null, metadata);
        }
    }

    @Transactional
    public AuditLog record(UUID actorId, String action, String entityType, UUID entityId, Map<String, Object> metadata) {
        return record(AuditEvent.system(actorId, action, entityType, entityId, metadata));
    }

    @Transactional
    public AuditLog record(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String action = requireText(event.action(), "action", MAX_ACTION_LENGTH);
        String entityType = requireText(event.entityType(), "entityType", MAX_ENTITY_TYPE_LENGTH);
        if (event.projectId() != null && event.workspaceId() == null) {
            throw new IllegalArgumentException("workspaceId is required when projectId is set");
        }
        rejectSecrets(event.before(), "before");
        rejectSecrets(event.after(), "after");
        rejectSecrets(event.metadata(), "metadata");

        if (event.actorId() != null) {
            jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, event.actorId().toString());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("source", "application");
        if (event.metadata() != null) {
            event.metadata().forEach((key, value) -> {
                if (!"schemaVersion".equals(key) && !"source".equals(key)) {
                    metadata.put(key, value);
                }
            });
        }

        AuditLog log = new AuditLog();
        log.setWorkspaceId(event.workspaceId());
        log.setProjectId(event.projectId());
        log.setActorId(event.actorId());
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(event.entityId());
        log.setBeforeJson(event.before() == null ? null : new LinkedHashMap<>(event.before()));
        log.setAfterJson(event.after() == null ? null : new LinkedHashMap<>(event.after()));
        log.setMetadata(metadata);
        log.setOccurredAt(Instant.now());
        log.setTraceId(currentTraceId());
        return auditLogRepository.save(log);
    }

    /** Trace identifier bound to the current thread when it is safe to persist, otherwise {@code null}. */
    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return traceId != null && VALID_TRACE_ID.matcher(traceId).matches() ? traceId : null;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return trimmed;
    }

    private static void rejectSecrets(Object document, String field) {
        if (document instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).replace("_", "").toLowerCase(Locale.ROOT);
                if (SECRET_KEYS.contains(key) && entry.getValue() != null) {
                    throw new IllegalArgumentException(field + " must not contain secret attributes");
                }
                rejectSecrets(entry.getValue(), field);
            }
        } else if (document instanceof Iterable<?> items) {
            for (Object item : items) {
                rejectSecrets(item, field);
            }
        }
    }
}
