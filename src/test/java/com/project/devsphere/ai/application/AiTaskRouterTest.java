package com.project.devsphere.ai.application;

import com.project.devsphere.ai.application.AiConnectionConfigService.StoredAiConnection;
import com.project.devsphere.ai.application.AiConnectionConfigService.WorkspaceAiConfigRecord;
import com.project.devsphere.ai.application.AiTaskRouter.ResolvedAiRoute;
import com.project.devsphere.ai.domain.AiJob;
import com.project.devsphere.ai.domain.AiPrivacyPolicy;
import com.project.devsphere.ai.domain.AiProviderType;
import com.project.devsphere.ai.infrastructure.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskRouterTest {

    @Mock
    private AiConnectionConfigService configService;

    private AiSecretCipher secretCipher;
    private AiEndpointValidator endpointValidator;
    private AiTaskRouter router;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        AiProperties properties = new AiProperties();
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(key));
        properties.setAllowLoopbackSelfHost(true);

        secretCipher = new AiSecretCipher(properties);
        endpointValidator = new AiEndpointValidator(properties);
        router = new AiTaskRouter(configService, endpointValidator, secretCipher);
        workspaceId = UUID.randomUUID();
    }

    @Test
    @DisplayName("TC-21: CLOUD_ALLOWED routes jobs to configured OPENAI and VLLM_SELF_HOST connections with decrypted API keys")
    void routesCloudAndSelfHostUnderCloudAllowed() {
        String encOpenAi = secretCipher.encrypt("test-mock-openai-key");
        String encVllm = secretCipher.encrypt("test-mock-vllm-token");

        WorkspaceAiConfigRecord record = new WorkspaceAiConfigRecord(
                AiPrivacyPolicy.CLOUD_ALLOWED,
                Map.of("schemaVersion", 1, "aiPrivacyPolicy", "CLOUD_ALLOWED"),
                List.of(
                        new StoredAiConnection("openai-main", AiProviderType.OPENAI, "https://api.openai.com", "gpt-4o", true, encOpenAi),
                        new StoredAiConnection("vllm-local", AiProviderType.VLLM_SELF_HOST, "http://127.0.0.1:8000", "qwen-2.5", true, encVllm)
                ),
                Map.of(
                        "CHAT_ANSWER", "openai-main",
                        "REQUIREMENT_PLAN", "vllm-local"
                )
        );
        when(configService.loadConfigRecord(workspaceId, false)).thenReturn(record);

        ResolvedAiRoute cloudRoute = router.resolve(workspaceId, AiJob.JobType.CHAT_ANSWER);
        assertThat(cloudRoute.connectionId()).isEqualTo("openai-main");
        assertThat(cloudRoute.provider()).isEqualTo(AiProviderType.OPENAI);
        assertThat(cloudRoute.apiKey()).isEqualTo("test-mock-openai-key");
        assertThat(cloudRoute.toString()).doesNotContain("test-mock-openai-key");

        ResolvedAiRoute vllmRoute = router.resolve(workspaceId, AiJob.JobType.REQUIREMENT_PLAN);
        assertThat(vllmRoute.connectionId()).isEqualTo("vllm-local");
        assertThat(vllmRoute.provider()).isEqualTo(AiProviderType.VLLM_SELF_HOST);
        assertThat(vllmRoute.apiKey()).isEqualTo("test-mock-vllm-token");
    }

    @Test
    @DisplayName("TC-22: SELF_HOST_ONLY strictly blocks OPENAI routes with 503 AI_UNAVAILABLE while permitting VLLM_SELF_HOST")
    void selfHostOnlyBlocksCloudWith503AiUnavailable() {
        String encOpenAi = secretCipher.encrypt("test-mock-openai-key");
        WorkspaceAiConfigRecord record = new WorkspaceAiConfigRecord(
                AiPrivacyPolicy.SELF_HOST_ONLY,
                Map.of("schemaVersion", 1, "aiPrivacyPolicy", "SELF_HOST_ONLY"),
                List.of(
                        new StoredAiConnection("openai-main", AiProviderType.OPENAI, "https://api.openai.com", "gpt-4o", true, encOpenAi),
                        new StoredAiConnection("vllm-local", AiProviderType.VLLM_SELF_HOST, "http://127.0.0.1:8000", "qwen-2.5", true, null)
                ),
                Map.of(
                        "CHAT_ANSWER", "openai-main",
                        "REQUIREMENT_PLAN", "vllm-local"
                )
        );
        when(configService.loadConfigRecord(workspaceId, false)).thenReturn(record);

        assertThatThrownBy(() -> router.resolve(workspaceId, AiJob.JobType.CHAT_ANSWER))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> {
                    AiException aiEx = (AiException) ex;
                    assertThat(aiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(aiEx.getCode()).isEqualTo("AI_UNAVAILABLE");
                });

        ResolvedAiRoute selfHost = router.resolve(workspaceId, AiJob.JobType.REQUIREMENT_PLAN);
        assertThat(selfHost.connectionId()).isEqualTo("vllm-local");
        assertThat(selfHost.provider()).isEqualTo(AiProviderType.VLLM_SELF_HOST);
        assertThat(selfHost.apiKey()).isNull();
    }

    @Test
    @DisplayName("SELF_HOST_ONLY fallback skips cloud and rejects with 503 when only cloud connection exists")
    void selfHostOnlyUnroutedThrowsWhenOnlyCloudConfigured() {
        WorkspaceAiConfigRecord onlyCloud = new WorkspaceAiConfigRecord(
                AiPrivacyPolicy.SELF_HOST_ONLY,
                Map.of(),
                List.of(new StoredAiConnection("openai-main", AiProviderType.OPENAI, "https://api.openai.com", "gpt-4o", true, null)),
                Map.of()
        );
        when(configService.loadConfigRecord(workspaceId, false)).thenReturn(onlyCloud);

        assertThatThrownBy(() -> router.resolve(workspaceId, AiJob.JobType.REPORT_SUMMARY))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> {
                    AiException aiEx = (AiException) ex;
                    assertThat(aiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(aiEx.getCode()).isEqualTo("AI_UNAVAILABLE");
                });
    }

    @Test
    @DisplayName("AiSecretCipher encrypts with v1 prefix, distinct random IVs, and AiProperties redacts key in toString")
    void secretCipherAndPropertiesSafeToString() {
        String c1 = secretCipher.encrypt("test-mock-cipher-input");
        String c2 = secretCipher.encrypt("test-mock-cipher-input");
        assertThat(c1).startsWith("v1:");
        assertThat(c2).startsWith("v1:");
        assertThat(c1).isNotEqualTo(c2);
        assertThat(secretCipher.decrypt(c1)).isEqualTo("test-mock-cipher-input");
        assertThat(secretCipher.decrypt(c2)).isEqualTo("test-mock-cipher-input");

        AiProperties props = new AiProperties();
        String sampleKey = Base64.getEncoder().encodeToString(
                "test-redaction-key-sample-32byte".getBytes(StandardCharsets.UTF_8));
        props.setEncryptionKey(sampleKey);
        assertThat(props.toString()).doesNotContain(sampleKey);
    }
}
