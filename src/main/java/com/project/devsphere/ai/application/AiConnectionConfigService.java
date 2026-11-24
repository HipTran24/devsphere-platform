package com.project.devsphere.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.ai.api.dto.AiConnectionInput;
import com.project.devsphere.ai.api.dto.AiConnectionSummaryResponse;
import com.project.devsphere.ai.api.dto.AiWorkspaceConfigResponse;
import com.project.devsphere.ai.api.dto.UpdateAiConnectionsRequest;
import com.project.devsphere.ai.api.dto.UpdateAiPrivacyRequest;
import com.project.devsphere.ai.api.dto.UpdateAiRoutesRequest;
import com.project.devsphere.ai.domain.AiJob;
import com.project.devsphere.ai.domain.AiPrivacyPolicy;
import com.project.devsphere.ai.domain.AiProviderType;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Manages workspace AI connections, task routes, and privacy policies persisted in
 * {@code workspaces.ai_connections}, {@code workspaces.ai_task_routes}, and {@code workspaces.settings}.
 */
@Service
public class AiConnectionConfigService {

    static final String KEY_ENCRYPTED_CREDENTIAL = "encryptedCredential";
    static final String KEY_PRIVACY_POLICY = "aiPrivacyPolicy";
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AccessPolicy accessPolicy;
    private final AiEndpointValidator endpointValidator;
    private final AiSecretCipher secretCipher;
    private final ObjectMapper objectMapper;

    public AiConnectionConfigService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            AccessPolicy accessPolicy,
            AiEndpointValidator endpointValidator,
            AiSecretCipher secretCipher) {
        this(jdbcTemplate, transactionTemplate, accessPolicy, endpointValidator, secretCipher, null);
    }

    @Autowired
    public AiConnectionConfigService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            AccessPolicy accessPolicy,
            AiEndpointValidator endpointValidator,
            AiSecretCipher secretCipher,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.endpointValidator = Objects.requireNonNull(endpointValidator, "endpointValidator must not be null");
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    public AiWorkspaceConfigResponse getConfig(SessionService.Principal principal, UUID workspaceId) {
        accessPolicy.requireWorkspaceMember(principal, workspaceId);
        WorkspaceAiConfigRecord record = loadConfigRecord(workspaceId, false);
        return toRedactedResponse(workspaceId, record);
    }

    public AiWorkspaceConfigResponse updateConnections(
            SessionService.Principal principal,
            UUID workspaceId,
            UpdateAiConnectionsRequest request) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);
        if (request == null || request.connections() == null) {
            throw AiException.invalidInput("connections must not be null");
        }

        return transactionTemplate.execute(status -> {
            setActorId(principal.userId());
            WorkspaceAiConfigRecord existing = loadConfigRecord(workspaceId, true);
            Map<String, StoredAiConnection> existingById = indexById(existing.connections());

            Set<String> seenIds = new LinkedHashSet<>();
            List<Map<String, Object>> serializedConnections = new ArrayList<>();

            for (AiConnectionInput input : request.connections()) {
                if (input == null) {
                    throw AiException.invalidInput("connection entry must not be null");
                }
                String id = requireBoundedText(input.id(), "connection id", 80);
                if (!seenIds.add(id)) {
                    throw AiException.invalidInput("Duplicate AI connection id: " + id);
                }

                AiProviderType provider = AiProviderType.parse(input.provider());
                URI normalizedBaseUrl = endpointValidator.validate(input.baseUrl(), provider);
                String model = requireBoundedText(input.model(), "model", 140);
                boolean enabled = input.enabled() == null || input.enabled();

                String encryptedCredential = resolveEncryptedCredential(input.apiKey(), existingById.get(id));

                Map<String, Object> stored = new LinkedHashMap<>();
                stored.put("id", id);
                stored.put("provider", provider.name());
                stored.put("baseUrl", normalizedBaseUrl.toString());
                stored.put("model", model);
                stored.put("enabled", enabled);
                if (encryptedCredential != null) {
                    stored.put(KEY_ENCRYPTED_CREDENTIAL, encryptedCredential);
                }
                serializedConnections.add(stored);
            }

            String json = writeJson(serializedConnections);
            jdbcTemplate.update(
                    "UPDATE workspaces SET ai_connections = ?::jsonb WHERE id = ?",
                    json,
                    workspaceId
            );

            WorkspaceAiConfigRecord updated = loadConfigRecord(workspaceId, false);
            return toRedactedResponse(workspaceId, updated);
        });
    }

    public AiWorkspaceConfigResponse updateRoutes(
            SessionService.Principal principal,
            UUID workspaceId,
            UpdateAiRoutesRequest request) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);
        if (request == null || request.routes() == null) {
            throw AiException.invalidInput("routes must not be null");
        }

        return transactionTemplate.execute(status -> {
            setActorId(principal.userId());
            WorkspaceAiConfigRecord existing = loadConfigRecord(workspaceId, true);
            Map<String, StoredAiConnection> configured = indexById(existing.connections());

            Map<String, String> normalizedRoutes = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : request.routes().entrySet()) {
                AiJob.JobType jobType = parseJobType(entry.getKey());
                String connectionId = requireBoundedText(entry.getValue(), "connectionId for " + jobType.name(), 80);
                if (!configured.containsKey(connectionId)) {
                    throw AiException.invalidInput("Route " + jobType.name() + " references unknown connectionId: " + connectionId);
                }
                if (normalizedRoutes.containsKey(jobType.name())) {
                    throw AiException.invalidInput("Duplicate route for jobType: " + jobType.name());
                }
                normalizedRoutes.put(jobType.name(), connectionId);
            }

            Map<String, Object> document = new LinkedHashMap<>();
            document.put("schemaVersion", 1);
            document.put("routes", normalizedRoutes);
            String json = writeJson(document);

            jdbcTemplate.update(
                    "UPDATE workspaces SET ai_task_routes = ?::jsonb WHERE id = ?",
                    json,
                    workspaceId
            );

            WorkspaceAiConfigRecord updated = loadConfigRecord(workspaceId, false);
            return toRedactedResponse(workspaceId, updated);
        });
    }

    public AiWorkspaceConfigResponse updatePrivacyPolicy(
            SessionService.Principal principal,
            UUID workspaceId,
            UpdateAiPrivacyRequest request) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);
        if (request == null) {
            throw AiException.invalidInput("privacyPolicy request must not be null");
        }
        AiPrivacyPolicy policy = AiPrivacyPolicy.parse(request.privacyPolicy());

        return transactionTemplate.execute(status -> {
            setActorId(principal.userId());
            WorkspaceAiConfigRecord existing = loadConfigRecord(workspaceId, true);
            Map<String, Object> settings = new LinkedHashMap<>(existing.settings());
            settings.putIfAbsent("schemaVersion", 1);
            settings.put(KEY_PRIVACY_POLICY, policy.name());
            String json = writeJson(settings);

            jdbcTemplate.update(
                    "UPDATE workspaces SET settings = ?::jsonb WHERE id = ?",
                    json,
                    workspaceId
            );

            WorkspaceAiConfigRecord updated = loadConfigRecord(workspaceId, false);
            return toRedactedResponse(workspaceId, updated);
        });
    }

    WorkspaceAiConfigRecord loadConfigRecord(UUID workspaceId, boolean lockForUpdate) {
        String sql = lockForUpdate
                ? "SELECT settings::text AS settings_json, ai_connections::text AS connections_json, ai_task_routes::text AS routes_json FROM workspaces WHERE id = ? FOR UPDATE"
                : "SELECT settings::text AS settings_json, ai_connections::text AS connections_json, ai_task_routes::text AS routes_json FROM workspaces WHERE id = ?";

        List<WorkspaceAiConfigRecord> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> parseRecord(
                        rs.getString("settings_json"),
                        rs.getString("connections_json"),
                        rs.getString("routes_json")
                ),
                workspaceId
        );
        if (rows.isEmpty()) {
            throw AiException.notFound("Workspace not found: " + workspaceId);
        }
        return rows.get(0);
    }

    WorkspaceAiConfigRecord parseRecord(String settingsJson, String connectionsJson, String routesJson) {
        Map<String, Object> settings = readMap(settingsJson);
        AiPrivacyPolicy policy = AiPrivacyPolicy.parseOrDefault(
                settings.get(KEY_PRIVACY_POLICY),
                AiPrivacyPolicy.CLOUD_ALLOWED
        );
        List<StoredAiConnection> connections = parseConnections(connectionsJson);
        Map<String, String> routes = parseRoutes(routesJson);
        return new WorkspaceAiConfigRecord(policy, settings, connections, routes);
    }

    private String resolveEncryptedCredential(String rawApiKey, StoredAiConnection previous) {
        if (rawApiKey != null && !rawApiKey.isBlank()) {
            return secretCipher.encrypt(rawApiKey.trim());
        }
        if (previous != null && previous.encryptedCredential() != null && !previous.encryptedCredential().isBlank()) {
            return previous.encryptedCredential();
        }
        return null;
    }

    private AiWorkspaceConfigResponse toRedactedResponse(UUID workspaceId, WorkspaceAiConfigRecord record) {
        List<AiConnectionSummaryResponse> summaries = record.connections().stream()
                .map(c -> new AiConnectionSummaryResponse(
                        c.id(),
                        c.provider().name(),
                        c.baseUrl(),
                        c.model(),
                        c.enabled(),
                        c.encryptedCredential() != null && !c.encryptedCredential().isBlank()
                ))
                .toList();
        return new AiWorkspaceConfigResponse(
                workspaceId,
                record.privacyPolicy().name(),
                summaries,
                Map.copyOf(record.routes())
        );
    }

    private List<StoredAiConnection> parseConnections(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, LIST_OF_MAPS);
            List<StoredAiConnection> result = new ArrayList<>(raw.size());
            for (Map<String, Object> item : raw) {
                if (item == null) {
                    continue;
                }
                String id = asTrimmedString(item.get("id"));
                String providerRaw = asTrimmedString(item.get("provider"));
                String baseUrl = asTrimmedString(item.get("baseUrl"));
                String model = asTrimmedString(item.get("model"));
                if (id == null || providerRaw == null || baseUrl == null || model == null) {
                    continue;
                }
                AiProviderType provider = AiProviderType.parse(providerRaw);
                boolean enabled = !(item.get("enabled") instanceof Boolean b) || b;
                String encryptedCredential = asTrimmedString(item.get(KEY_ENCRYPTED_CREDENTIAL));
                result.add(new StoredAiConnection(id, provider, baseUrl, model, enabled, encryptedCredential));
            }
            return result;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse workspaces.ai_connections JSONB", ex);
        }
    }

    private Map<String, String> parseRoutes(String json) {
        Map<String, Object> raw = readMap(json);
        Map<String, String> parsed = new LinkedHashMap<>();

        Object nested = raw.get("routes");
        if (nested instanceof Map<?, ?> nestedMap) {
            extractRouteEntries(nestedMap, parsed);
        } else {
            extractRouteEntries(raw, parsed);
        }
        return parsed;
    }

    private static void extractRouteEntries(Map<?, ?> source, Map<String, String> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().toString().trim();
            if ("schemaVersion".equals(key)) {
                continue;
            }
            String value = entry.getValue().toString().trim();
            if (!value.isEmpty()) {
                try {
                    AiJob.JobType jobType = AiJob.JobType.valueOf(key.toUpperCase(Locale.ROOT));
                    target.put(jobType.name(), value);
                } catch (IllegalArgumentException ignored) {
                    // Ignore non-JobType metadata fields
                }
            }
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse workspace JSONB map", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize AI configuration JSONB", ex);
        }
    }

    private void setActorId(UUID actorId) {
        if (actorId != null) {
            jdbcTemplate.queryForObject(
                    "SELECT set_config('devsphere.actor_id', ?, true)",
                    String.class,
                    actorId.toString()
            );
        }
    }

    private static Map<String, StoredAiConnection> indexById(List<StoredAiConnection> connections) {
        Map<String, StoredAiConnection> map = new LinkedHashMap<>();
        for (StoredAiConnection conn : connections) {
            map.put(conn.id(), conn);
        }
        return map;
    }

    private static AiJob.JobType parseJobType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AiException.invalidInput("route jobType must not be blank");
        }
        try {
            return AiJob.JobType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw AiException.invalidInput("Invalid jobType in routes: " + raw);
        }
    }

    private static String requireBoundedText(String raw, String fieldName, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw AiException.invalidInput(fieldName + " must not be blank");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw AiException.invalidInput(fieldName + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private static String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    record StoredAiConnection(
            String id,
            AiProviderType provider,
            String baseUrl,
            String model,
            boolean enabled,
            String encryptedCredential
    ) {
        @Override
        public String toString() {
            return "StoredAiConnection[id=" + id
                    + ", provider=" + provider
                    + ", baseUrl=" + baseUrl
                    + ", model=" + model
                    + ", enabled=" + enabled
                    + ", credentialPresent=" + (encryptedCredential != null && !encryptedCredential.isBlank()) + "]";
        }
    }

    record WorkspaceAiConfigRecord(
            AiPrivacyPolicy privacyPolicy,
            Map<String, Object> settings,
            List<StoredAiConnection> connections,
            Map<String, String> routes
    ) {
    }
}
