package com.project.devsphere.administration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.administration.api.dto.AdminAuditLogResponse;
import com.project.devsphere.administration.api.dto.AdminPageResponse;
import com.project.devsphere.administration.api.dto.AdminUserResponse;
import com.project.devsphere.administration.api.dto.UserStatusUpdateRequest;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.User;
import com.project.devsphere.operations.api.dto.AuditSummaryResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AccountAdministrationService {

    private static final Map<String, String> USER_SORT_FIELDS = Map.of(
            "createdAt", "created_at",
            "email", "lower(email::text) COLLATE \"C\"",
            "displayName", "display_name",
            "status", "status"
    );

    private static final Map<String, String> AUDIT_SORT_FIELDS = Map.of(
            "occurredAt", "occurred_at",
            "createdAt", "created_at"
    );

    private static final Set<String> WHITELISTED_SAFE_FIELDS = Set.of(
            "status",
            "role",
            "system_role",
            "systemRole",
            "version"
    );

    private static final java.util.regex.Pattern TRACE_ID_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");
    private static final int MAX_SUMMARY_GROUPS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final AccessPolicy accessPolicy;

    public AccountAdministrationService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            AccessPolicy accessPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.accessPolicy = accessPolicy;
    }

    public AdminPageResponse<AdminUserResponse> getUsers(
            int page,
            int size,
            String keyword,
            User.Status status,
            User.SystemRole systemRole,
            String sort) {

        if (page < 0 || page > 10000) {
            throw AdminException.validationFailed("Page index out of bounds (0..10000)");
        }
        if (size < 1 || size > 100) {
            throw AdminException.validationFailed("Page size out of bounds (1..100)");
        }

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (keyword != null) {
            if (keyword.codePointCount(0, keyword.length()) > 200) {
                throw AdminException.validationFailed("Keyword exceeds maximum length of 200 code points");
            }
            String trimmed = keyword.strip();
            try {
                if (trimmed.contains("%25") || trimmed.contains("%20") || trimmed.contains("%5F")) {
                    trimmed = java.net.URLDecoder.decode(trimmed, java.nio.charset.StandardCharsets.UTF_8).strip();
                }
            } catch (Exception ignored) {}
            if (!trimmed.isEmpty()) {
                String escaped = escapeSqlLike(trimmed);
                whereClauses.add("(email::text ILIKE ? ESCAPE '!' OR display_name ILIKE ? ESCAPE '!')");
                params.add("%" + escaped + "%");
                params.add("%" + escaped + "%");
            }
        }

        if (status != null) {
            whereClauses.add("status = ?");
            params.add(status.name());
        }

        if (systemRole != null) {
            whereClauses.add("system_role = ?");
            params.add(systemRole.name());
        }

        String whereSql = whereClauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", whereClauses);
        String orderSql = parseAndValidateSort(sort, USER_SORT_FIELDS, "created_at DESC") + ", id ASC";

        String countSql = "SELECT count(*) FROM users" + whereSql;
        Long totalElementsObj = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = totalElementsObj != null ? totalElementsObj : 0L;
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);

        String selectSql = "SELECT id, email, display_name, status, system_role, email_verified_at, created_at, updated_at, version " +
                "FROM users" + whereSql + " " + orderSql + " LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(size);
        queryParams.add(page * size);

        List<AdminUserResponse> users = jdbcTemplate.query(
                selectSql,
                (rs, rowNum) -> mapToAdminUserResponse(rs),
                queryParams.toArray()
        );

        return new AdminPageResponse<>(users, page, size, totalElements, totalPages, null, null);
    }

    public AdminUserResponse updateUserStatus(
            SessionService.Principal principal,
            UUID targetUserId,
            UserStatusUpdateRequest request) {

        if (principal == null) {
            throw AdminException.unauthorized("Authentication required");
        }
        if (targetUserId == null || request == null || request.status() == null || request.expectedVersion() == null) {
            throw AdminException.validationFailed("Invalid request parameters");
        }
        if (request.expectedVersion() < 0) {
            throw AdminException.validationFailed("Expected version must be non-negative");
        }

        // Validate admin access via central access policy
        accessPolicy.requireSystemAdmin(principal);

        return transactionTemplate.execute(status -> {
            // 1. Transaction advisory lock
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(1005001)");

            UUID actorId = principal.userId();

            // 2. Deterministic row locking order on users
            if (actorId.equals(targetUserId)) {
                jdbcTemplate.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", actorId);
            } else {
                UUID first = actorId.compareTo(targetUserId) < 0 ? actorId : targetUserId;
                UUID second = actorId.compareTo(targetUserId) < 0 ? targetUserId : actorId;
                jdbcTemplate.queryForList("SELECT id FROM users WHERE id IN (?, ?) ORDER BY id FOR UPDATE", first, second);
            }

            // 3. Validate actor is ACTIVE, verified, and ADMIN
            List<Map<String, Object>> actorRows = jdbcTemplate.queryForList(
                    "SELECT id, status, system_role, email_verified_at FROM users WHERE id = ?",
                    actorId
            );
            if (actorRows.isEmpty()) {
                throw AdminException.forbidden("Actor not found");
            }
            Map<String, Object> actorRow = actorRows.get(0);
            String actorStatus = (String) actorRow.get("status");
            String actorRole = (String) actorRow.get("system_role");
            Object actorVerified = actorRow.get("email_verified_at");
            if (!"ACTIVE".equalsIgnoreCase(actorStatus) || !"ADMIN".equalsIgnoreCase(actorRole) || actorVerified == null) {
                throw AdminException.forbidden("Actor must be an active, verified administrator");
            }

            // 4. Validate target exists
            List<TargetUserData> targetRows = jdbcTemplate.query(
                    "SELECT id, email, display_name, status, system_role, email_verified_at, created_at, updated_at, version " +
                            "FROM users WHERE id = ?",
                    (rs, rowNum) -> new TargetUserData(
                            (UUID) rs.getObject("id"),
                            rs.getString("email"),
                            rs.getString("display_name"),
                            User.Status.valueOf(rs.getString("status")),
                            User.SystemRole.valueOf(rs.getString("system_role")),
                            getInstant(rs, "email_verified_at"),
                            getInstant(rs, "created_at"),
                            getInstant(rs, "updated_at"),
                            rs.getLong("version")
                    ),
                    targetUserId
            );
            if (targetRows.isEmpty()) {
                throw AdminException.notFound("Target user not found");
            }
            TargetUserData target = targetRows.get(0);

            // 5. Validate optimistic version
            if (!target.version().equals(request.expectedVersion())) {
                throw AdminException.concurrentChange("Concurrent modification detected: version mismatch");
            }

            // 6. Idempotent no-op
            if (target.status() == request.status()) {
                return new AdminUserResponse(
                        target.id(),
                        target.email(),
                        target.displayName(),
                        target.status(),
                        target.systemRole(),
                        target.emailVerifiedAt(),
                        target.createdAt(),
                        target.updatedAt(),
                        target.version()
                );
            }

            // 7. Validate state transitions:
            // - ACTIVE -> LOCKED (valid)
            // - PENDING -> LOCKED (valid)
            // - LOCKED -> ACTIVE only if email_verified_at != null (valid; if unverified -> 409 STATE_TRANSITION_INVALID)
            // - LOCKED -> PENDING only if email_verified_at == null (valid; if verified -> 409 STATE_TRANSITION_INVALID)
            // - Any other transition -> 409 STATE_TRANSITION_INVALID
            User.Status currentStatus = target.status();
            User.Status newStatus = request.status();

            if (currentStatus == User.Status.ACTIVE && newStatus == User.Status.LOCKED) {
                // Valid
            } else if (currentStatus == User.Status.PENDING && newStatus == User.Status.LOCKED) {
                // Valid
            } else if (currentStatus == User.Status.LOCKED && newStatus == User.Status.ACTIVE) {
                if (target.emailVerifiedAt() == null) {
                    throw AdminException.stateTransitionInvalid("Cannot activate unverified account");
                }
            } else if (currentStatus == User.Status.LOCKED && newStatus == User.Status.PENDING) {
                if (target.emailVerifiedAt() != null) {
                    throw AdminException.stateTransitionInvalid("Cannot transition verified account to PENDING");
                }
            } else {
                throw AdminException.stateTransitionInvalid("Invalid status transition from " + currentStatus + " to " + newStatus);
            }

            // 8. Last active admin protection:
            // If target has system_role = 'ADMIN' and is ACTIVE, count active verified admins
            if (target.systemRole() == User.SystemRole.ADMIN && currentStatus == User.Status.ACTIVE) {
                Integer activeAdmins = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM users WHERE system_role = 'ADMIN' AND status = 'ACTIVE' AND email_verified_at IS NOT NULL",
                        Integer.class
                );
                if (activeAdmins == null || activeAdmins <= 1) {
                    throw AdminException.lastActiveAdmin("Cannot lock or demote the last active verified administrator");
                }
            }

            // 9. Sets local devsphere.actor_id before update so ds_audit_change attributes the audit log
            jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, actorId.toString());

            // 10. Performs UPDATE users SET status = ?, version = version + 1 WHERE id = ? AND version = ?
            int rowsUpdated = jdbcTemplate.update(
                    "UPDATE users SET status = ?, version = version + 1, updated_at = clock_timestamp() WHERE id = ? AND version = ?",
                    newStatus.name(),
                    targetUserId,
                    target.version()
            );
            if (rowsUpdated == 0) {
                throw AdminException.concurrentChange("Concurrent modification detected during update");
            }

            // 11. Fetch updated target user
            TargetUserData updated = jdbcTemplate.queryForObject(
                    "SELECT id, email, display_name, status, system_role, email_verified_at, created_at, updated_at, version " +
                            "FROM users WHERE id = ?",
                    (rs, rowNum) -> new TargetUserData(
                            (UUID) rs.getObject("id"),
                            rs.getString("email"),
                            rs.getString("display_name"),
                            User.Status.valueOf(rs.getString("status")),
                            User.SystemRole.valueOf(rs.getString("system_role")),
                            getInstant(rs, "email_verified_at"),
                            getInstant(rs, "created_at"),
                            getInstant(rs, "updated_at"),
                            rs.getLong("version")
                    ),
                    targetUserId
            );

            return new AdminUserResponse(
                    updated.id(),
                    updated.email(),
                    updated.displayName(),
                    updated.status(),
                    updated.systemRole(),
                    updated.emailVerifiedAt(),
                    updated.createdAt(),
                    updated.updatedAt(),
                    updated.version()
            );
        });
    }

    public AdminPageResponse<AdminAuditLogResponse> getAuditLogs(
            int page,
            int size,
            UUID actorId,
            String action,
            String entityType,
            Instant from,
            Instant to,
            String sort) {
        return getAuditLogs(page, size, AuditLogFilter.of(actorId, action, entityType, from, to), sort);
    }

    public AdminPageResponse<AdminAuditLogResponse> getAuditLogs(
            int page,
            int size,
            AuditLogFilter filter,
            String sort) {

        if (page < 0 || page > 10000) {
            throw AdminException.validationFailed("Page index out of bounds (0..10000)");
        }
        if (size < 1 || size > 100) {
            throw AdminException.validationFailed("Page size out of bounds (1..100)");
        }

        AuditWhere where = buildAuditWhere(filter);
        String whereSql = where.sql();
        List<Object> params = where.params();
        Instant effectiveFrom = where.effectiveFrom();
        Instant effectiveTo = where.effectiveTo();
        String orderSql = parseAndValidateSort(sort, AUDIT_SORT_FIELDS, "occurred_at DESC") + ", id ASC";

        String countSql = "SELECT count(*) FROM audit_logs" + whereSql;
        Long totalElementsObj = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = totalElementsObj != null ? totalElementsObj : 0L;
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);

        String selectSql = "SELECT id, workspace_id, project_id, actor_id, action, entity_type, entity_id, " +
                "before_json, after_json, occurred_at, trace_id FROM audit_logs" +
                whereSql + " " + orderSql + " LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(size);
        queryParams.add(page * size);

        List<AdminAuditLogResponse> logs = jdbcTemplate.query(
                selectSql,
                (rs, rowNum) -> mapToAdminAuditLogResponse(rs),
                queryParams.toArray()
        );

        return new AdminPageResponse<>(logs, page, size, totalElements, totalPages, effectiveFrom, effectiveTo);
    }

    /** Counts audit events per action inside the (validated) window, most frequent first. */
    public AuditSummaryResponse summarizeAuditLogs(Instant from, Instant to) {
        AuditWhere where = buildAuditWhere(AuditLogFilter.range(from, to));
        List<AuditSummaryResponse.ActionCount> actions = jdbcTemplate.query(
                "SELECT action, count(*) AS events FROM audit_logs" + where.sql() +
                        " GROUP BY action ORDER BY events DESC, action ASC LIMIT " + MAX_SUMMARY_GROUPS,
                (rs, rowNum) -> new AuditSummaryResponse.ActionCount(rs.getString("action"), rs.getLong("events")),
                where.params().toArray()
        );
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs" + where.sql(), Long.class, where.params().toArray());
        return new AuditSummaryResponse(where.effectiveFrom(), where.effectiveTo(), total != null ? total : 0L, actions);
    }

    /**
     * Optional audit search criteria. {@code from}/{@code to} must be supplied together (max 31 days);
     * when both are omitted the last 30 days are searched.
     */
    public record AuditLogFilter(
            UUID actorId,
            String action,
            String entityType,
            UUID workspaceId,
            UUID projectId,
            UUID entityId,
            String traceId,
            Instant from,
            Instant to) {

        public static AuditLogFilter of(UUID actorId, String action, String entityType, Instant from, Instant to) {
            return new AuditLogFilter(actorId, action, entityType, null, null, null, null, from, to);
        }

        public static AuditLogFilter range(Instant from, Instant to) {
            return new AuditLogFilter(null, null, null, null, null, null, null, from, to);
        }
    }

    private record AuditWhere(String sql, List<Object> params, Instant effectiveFrom, Instant effectiveTo) {}

    private AuditWhere buildAuditWhere(AuditLogFilter filter) {
        AuditLogFilter criteria = filter != null ? filter : AuditLogFilter.range(null, null);
        Instant effectiveTo = criteria.to();
        Instant effectiveFrom = criteria.from();

        if (effectiveFrom == null && effectiveTo == null) {
            effectiveTo = databaseNow();
            effectiveFrom = effectiveTo.minus(Duration.ofDays(30));
        } else if (effectiveFrom == null || effectiveTo == null) {
            throw AdminException.validationFailed("Both 'from' and 'to' must be provided together or omitted");
        } else {
            if (!effectiveFrom.isBefore(effectiveTo)) {
                throw AdminException.validationFailed("'from' timestamp must be strictly before 'to' timestamp");
            }
            long days = Duration.between(effectiveFrom, effectiveTo).toDays();
            if (days > 31) {
                throw AdminException.validationFailed("Date span cannot exceed 31 days");
            }
        }

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        whereClauses.add("occurred_at >= ? AND occurred_at <= ?");
        params.add(effectiveFrom.atOffset(ZoneOffset.UTC));
        params.add(effectiveTo.atOffset(ZoneOffset.UTC));

        if (criteria.actorId() != null) {
            whereClauses.add("actor_id = ?");
            params.add(criteria.actorId());
        }

        if (criteria.action() != null && !criteria.action().isBlank()) {
            whereClauses.add("action = ?");
            params.add(criteria.action().strip());
        }

        if (criteria.entityType() != null && !criteria.entityType().isBlank()) {
            whereClauses.add("entity_type = ?");
            params.add(criteria.entityType().strip());
        }

        if (criteria.workspaceId() != null) {
            whereClauses.add("workspace_id = ?");
            params.add(criteria.workspaceId());
        }

        if (criteria.projectId() != null) {
            whereClauses.add("project_id = ?");
            params.add(criteria.projectId());
        }

        if (criteria.entityId() != null) {
            whereClauses.add("entity_id = ?");
            params.add(criteria.entityId());
        }

        if (criteria.traceId() != null && !criteria.traceId().isBlank()) {
            String traceId = criteria.traceId().strip();
            if (!TRACE_ID_PATTERN.matcher(traceId).matches()) {
                throw AdminException.validationFailed("traceId must match ^[a-zA-Z0-9._-]{1,64}$");
            }
            whereClauses.add("trace_id = ?");
            params.add(traceId);
        }

        return new AuditWhere(" WHERE " + String.join(" AND ", whereClauses), params, effectiveFrom, effectiveTo);
    }

    private String parseAndValidateSort(String sort, Map<String, String> allowedFields, String defaultOrder) {
        if (sort == null || sort.isBlank()) {
            return "ORDER BY " + defaultOrder;
        }

        String[] parts = sort.split(",");
        String field = parts[0].trim();
        String direction = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "asc";

        if (!allowedFields.containsKey(field)) {
            throw AdminException.validationFailed("Invalid sort field: " + field);
        }
        if (!"asc".equals(direction) && !"desc".equals(direction)) {
            throw AdminException.validationFailed("Invalid sort direction: " + direction);
        }

        String column = allowedFields.get(field);
        if ("email".equals(field) || "displayName".equals(field)) {
            return "ORDER BY " + column + " COLLATE \"C\" " + direction.toUpperCase(Locale.ROOT);
        }
        return "ORDER BY " + column + " " + direction.toUpperCase(Locale.ROOT);
    }

    private String escapeSqlLike(String value) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private AdminUserResponse mapToAdminUserResponse(ResultSet rs) throws SQLException {
        return new AdminUserResponse(
                (UUID) rs.getObject("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                User.Status.valueOf(rs.getString("status")),
                User.SystemRole.valueOf(rs.getString("system_role")),
                getInstant(rs, "email_verified_at"),
                getInstant(rs, "created_at"),
                getInstant(rs, "updated_at"),
                rs.getLong("version")
        );
    }

    private AdminAuditLogResponse mapToAdminAuditLogResponse(ResultSet rs) throws SQLException {
        UUID id = (UUID) rs.getObject("id");
        UUID workspaceId = (UUID) rs.getObject("workspace_id");
        UUID projectId = (UUID) rs.getObject("project_id");
        UUID actorId = (UUID) rs.getObject("actor_id");
        String action = rs.getString("action");
        String entityType = rs.getString("entity_type");
        UUID entityId = (UUID) rs.getObject("entity_id");
        String beforeJson = rs.getString("before_json");
        String afterJson = rs.getString("after_json");
        Instant occurredAt = getInstant(rs, "occurred_at");
        String traceId = rs.getString("trace_id");

        Map<String, Object> safeDetails = buildSafeDetails(beforeJson, afterJson);

        return new AdminAuditLogResponse(
                id,
                workspaceId,
                projectId,
                actorId,
                action,
                entityType,
                entityId,
                safeDetails,
                occurredAt,
                traceId
        );
    }

    private Map<String, Object> buildSafeDetails(String beforeJson, String afterJson) {
        Map<String, Object> safeDetails = new LinkedHashMap<>();
        Map<String, Object> beforeMap = extractWhitelistedFields(beforeJson);
        Map<String, Object> afterMap = extractWhitelistedFields(afterJson);

        if (!beforeMap.isEmpty()) {
            safeDetails.put("before", beforeMap);
        }
        if (!afterMap.isEmpty()) {
            safeDetails.put("after", afterMap);
        }

        return safeDetails;
    }

    private Map<String, Object> extractWhitelistedFields(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            JsonNode root = objectMapper.readTree(jsonStr);
            if (root == null || !root.isObject()) {
                return Collections.emptyMap();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            var fields = root.properties();
            for (var entry : fields) {
                String fieldName = entry.getKey();
                if (WHITELISTED_SAFE_FIELDS.contains(fieldName)) {
                    JsonNode val = entry.getValue();
                    if (val.isTextual()) {
                        result.put(fieldName, val.asText());
                    } else if (val.isNumber()) {
                        result.put(fieldName, val.numberValue());
                    } else if (val.isBoolean()) {
                        result.put(fieldName, val.asBoolean());
                    } else if (val.isNull()) {
                        result.put(fieldName, null);
                    }
                }
            }
            return result;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    private Instant getInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }

    private Instant databaseNow() {
        Timestamp ts = jdbcTemplate.queryForObject("SELECT now()", Timestamp.class);
        return ts != null ? ts.toInstant() : Instant.now();
    }

    private record TargetUserData(
            UUID id,
            String email,
            String displayName,
            User.Status status,
            User.SystemRole systemRole,
            Instant emailVerifiedAt,
            Instant createdAt,
            Instant updatedAt,
            Long version
    ) {}
}
