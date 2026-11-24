package com.project.devsphere.ai.application;

import com.project.devsphere.ai.application.AiConnectionConfigService.StoredAiConnection;
import com.project.devsphere.ai.application.AiConnectionConfigService.WorkspaceAiConfigRecord;
import com.project.devsphere.ai.domain.AiJob;
import com.project.devsphere.ai.domain.AiPrivacyPolicy;
import com.project.devsphere.ai.domain.AiProviderType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves {@code (workspaceId, jobType)} to an active AI connection while enforcing
 * workspace {@link AiPrivacyPolicy}. Under {@code SELF_HOST_ONLY}, any cloud ({@code OPENAI})
 * route or fallback is strictly rejected with {@code 503 AI_UNAVAILABLE} prior to HTTP I/O.
 */
@Component
public class AiTaskRouter {

    private static final String DEFAULT_CONNECTION_PLACEHOLDER = "default-ai-connection";

    private final AiConnectionConfigService configService;
    private final AiEndpointValidator endpointValidator;
    private final AiSecretCipher secretCipher;

    @Autowired
    public AiTaskRouter(
            AiConnectionConfigService configService,
            AiEndpointValidator endpointValidator,
            AiSecretCipher secretCipher) {
        this.configService = Objects.requireNonNull(configService, "configService must not be null");
        this.endpointValidator = Objects.requireNonNull(endpointValidator, "endpointValidator must not be null");
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher must not be null");
    }

    public ResolvedAiRoute resolve(UUID workspaceId, AiJob.JobType jobType) {
        return resolve(workspaceId, jobType, null);
    }

    public ResolvedAiRoute resolve(UUID workspaceId, AiJob.JobType jobType, String requestedConnectionId) {
        if (workspaceId == null) {
            throw AiException.invalidInput("workspaceId must not be null");
        }
        WorkspaceAiConfigRecord config = configService.loadConfigRecord(workspaceId, false);
        return resolveFromConfig(config, jobType, requestedConnectionId);
    }

    ResolvedAiRoute resolveFromConfig(
            WorkspaceAiConfigRecord config,
            AiJob.JobType jobType,
            String requestedConnectionId) {
        if (config == null) {
            throw AiException.unavailable("Workspace AI configuration is unavailable");
        }
        if (jobType == null) {
            throw AiException.invalidInput("jobType must not be null");
        }

        AiPrivacyPolicy policy = config.privacyPolicy() != null
                ? config.privacyPolicy()
                : AiPrivacyPolicy.CLOUD_ALLOWED;

        Map<String, StoredAiConnection> byId = new LinkedHashMap<>();
        for (StoredAiConnection conn : config.connections()) {
            byId.put(conn.id(), conn);
        }

        StoredAiConnection selected = selectConnection(policy, byId, config.connections(), config.routes(), jobType, requestedConnectionId);

        URI validatedUri;
        try {
            validatedUri = endpointValidator.validate(selected.baseUrl(), selected.provider());
        } catch (AiException ex) {
            throw AiException.unavailable("Configured AI connection '" + selected.id() + "' failed endpoint validation");
        }

        String decryptedApiKey = secretCipher.decrypt(selected.encryptedCredential());
        return new ResolvedAiRoute(
                selected.id(),
                selected.provider(),
                validatedUri.toString(),
                selected.model(),
                decryptedApiKey,
                policy
        );
    }

    private StoredAiConnection selectConnection(
            AiPrivacyPolicy policy,
            Map<String, StoredAiConnection> byId,
            List<StoredAiConnection> orderedConnections,
            Map<String, String> routes,
            AiJob.JobType jobType,
            String requestedConnectionId) {

        String routedId = routes != null ? routes.get(jobType.name()) : null;
        if (routedId != null && !routedId.isBlank()) {
            StoredAiConnection routed = requireEligibleConnection(byId.get(routedId.trim()), routedId.trim(), policy);
            return routed;
        }

        if (requestedConnectionId != null
                && !requestedConnectionId.isBlank()
                && !DEFAULT_CONNECTION_PLACEHOLDER.equals(requestedConnectionId.trim())) {
            String trimmed = requestedConnectionId.trim();
            return requireEligibleConnection(byId.get(trimmed), trimmed, policy);
        }

        for (StoredAiConnection candidate : orderedConnections) {
            if (!candidate.enabled()) {
                continue;
            }
            if (policy == AiPrivacyPolicy.SELF_HOST_ONLY && candidate.provider().isCloud()) {
                continue;
            }
            return candidate;
        }

        if (policy == AiPrivacyPolicy.SELF_HOST_ONLY) {
            throw AiException.unavailable("No enabled self-hosted AI connection available under SELF_HOST_ONLY privacy policy");
        }
        throw AiException.unavailable("No enabled AI connection configured for workspace");
    }

    private static StoredAiConnection requireEligibleConnection(
            StoredAiConnection connection,
            String connectionId,
            AiPrivacyPolicy policy) {
        if (connection == null) {
            throw AiException.unavailable("Configured AI connection not found: " + connectionId);
        }
        if (!connection.enabled()) {
            throw AiException.unavailable("AI connection is disabled: " + connectionId);
        }
        if (policy == AiPrivacyPolicy.SELF_HOST_ONLY && connection.provider().isCloud()) {
            throw AiException.unavailable("Cloud AI connection '" + connectionId + "' is blocked under SELF_HOST_ONLY privacy policy");
        }
        return connection;
    }

    public record ResolvedAiRoute(
            String connectionId,
            AiProviderType provider,
            String baseUrl,
            String model,
            String apiKey,
            AiPrivacyPolicy privacyPolicy
    ) {
        @Override
        public String toString() {
            return "ResolvedAiRoute[connectionId=" + connectionId
                    + ", provider=" + provider
                    + ", baseUrl=" + baseUrl
                    + ", model=" + model
                    + ", privacyPolicy=" + privacyPolicy
                    + ", apiKeyConfigured=" + (apiKey != null && !apiKey.isBlank()) + "]";
        }
    }
}
