package com.project.devsphere.gitintegration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.gitintegration.domain.GitHubEvent;
import com.project.devsphere.gitintegration.domain.GitHubRepository;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubEventRepository;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubRepositoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitHubWebhookServiceTest {

    @Mock private GitHubEventRepository eventRepository;
    @Mock private GitHubRepositoryRepository repositoryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GitHubWebhookService webhookService;

    private final String testSecret = "super-secret-webhook-key-123";
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        webhookService = new GitHubWebhookService(eventRepository, repositoryRepository, objectMapper);
    }

    private String computeHmacHex(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    @DisplayName("verifySignature succeeds when signature header exactly matches HMAC-SHA256")
    void verifySignature_validSignature_passes() {
        byte[] payload = "{\"action\":\"opened\",\"repository\":{\"id\":123456}}".getBytes(StandardCharsets.UTF_8);
        String hex = computeHmacHex(payload, testSecret);
        String header = "sha256=" + hex;

        // Must complete without exception
        webhookService.verifySignature(payload, header, testSecret);
    }

    @Test
    @DisplayName("verifySignature throws 401 when signature header is missing or blank")
    void verifySignature_missingHeader_throwsUnauthorized() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> webhookService.verifySignature(payload, null, testSecret))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("Missing X-Hub-Signature-256 header")
                .satisfies(ex -> assertThat(((GitHubException) ex).getCode()).isEqualTo("WEBHOOK_SIGNATURE_INVALID"));

        assertThatThrownBy(() -> webhookService.verifySignature(payload, "   ", testSecret))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("Missing X-Hub-Signature-256 header");
    }

    @Test
    @DisplayName("verifySignature throws 401 when header format does not start with sha256=")
    void verifySignature_invalidPrefix_throwsUnauthorized() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> webhookService.verifySignature(payload, "sha1=abcdef123456", testSecret))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("must start with sha256=")
                .satisfies(ex -> assertThat(((GitHubException) ex).getCode()).isEqualTo("WEBHOOK_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("verifySignature throws 401 when signature hex is malformed")
    void verifySignature_malformedHex_throwsUnauthorized() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> webhookService.verifySignature(payload, "sha256=not-a-valid-hex-digest", testSecret))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("Malformed hex")
                .satisfies(ex -> assertThat(((GitHubException) ex).getCode()).isEqualTo("WEBHOOK_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("verifySignature throws 401 when signature does not match (tampered payload or secret)")
    void verifySignature_mismatchSignature_throwsUnauthorized() {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        String hex = computeHmacHex("{\"action\":\"tampered\"}".getBytes(StandardCharsets.UTF_8), testSecret);
        String header = "sha256=" + hex;

        assertThatThrownBy(() -> webhookService.verifySignature(payload, header, testSecret))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("verification failed")
                .satisfies(ex -> assertThat(((GitHubException) ex).getCode()).isEqualTo("WEBHOOK_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("recordEvent saves a new delivery with RECEIVED status")
    void recordEvent_newDelivery_savesSuccessfully() {
        String deliveryId = "del-12345-uuid";
        Map<String, Object> payload = Map.of("action", "opened", "number", 101);

        when(eventRepository.findByDeliveryId(deliveryId)).thenReturn(Optional.empty());
        when(eventRepository.save(any(GitHubEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        GitHubEvent event = webhookService.recordEvent(
                deliveryId, "pull_request", "opened", payload, repositoryId, projectId
        );

        assertThat(event).isNotNull();
        assertThat(event.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(event.getEventType()).isEqualTo("pull_request");
        assertThat(event.getAction()).isEqualTo("opened");
        assertThat(event.getProcessStatus()).isEqualTo(GitHubEvent.ProcessStatus.RECEIVED);
        assertThat(event.getProjectId()).isEqualTo(projectId);
        assertThat(event.getRepositoryId()).isEqualTo(repositoryId);

        ArgumentCaptor<GitHubEvent> captor = ArgumentCaptor.forClass(GitHubEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getDeliveryId()).isEqualTo(deliveryId);
    }

    @Test
    @DisplayName("recordEvent returns existing event without re-inserting on duplicate delivery")
    void recordEvent_duplicateDelivery_returnsExistingWithoutDuplicateSave() {
        String deliveryId = "del-already-seen";
        GitHubEvent existingEvent = new GitHubEvent();
        existingEvent.setId(UUID.randomUUID());
        existingEvent.setDeliveryId(deliveryId);
        existingEvent.setEventType("push");
        existingEvent.setProcessStatus(GitHubEvent.ProcessStatus.DONE);

        when(eventRepository.findByDeliveryId(deliveryId)).thenReturn(Optional.of(existingEvent));

        GitHubEvent event = webhookService.recordEvent(
                deliveryId, "push", null, Map.of(), repositoryId, projectId
        );

        assertThat(event).isSameAs(existingEvent);
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordEventByRepoId returns empty when repository is not found or inactive")
    void recordEventByRepoId_unknownRepo_returnsEmpty() {
        Long githubRepoId = 999999L;
        String deliveryId = "del-unknown-repo";

        when(eventRepository.findByDeliveryId(deliveryId)).thenReturn(Optional.empty());
        when(repositoryRepository.findByGithubRepoIdAndActiveTrue(githubRepoId)).thenReturn(Optional.empty());

        Optional<GitHubEvent> result = webhookService.recordEventByRepoId(
                deliveryId, "push", null, Map.of(), githubRepoId
        );

        assertThat(result).isEmpty();
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordEventByRepoId resolves project and repo when repository is registered")
    void recordEventByRepoId_registeredRepo_recordsEvent() {
        Long githubRepoId = 888888L;
        String deliveryId = "del-registered-repo";

        GitHubRepository repo = new GitHubRepository();
        repo.setId(repositoryId);
        repo.setProjectId(projectId);
        repo.setGithubRepoId(githubRepoId);
        repo.setActive(true);

        when(eventRepository.findByDeliveryId(deliveryId)).thenReturn(Optional.empty());
        when(repositoryRepository.findByGithubRepoIdAndActiveTrue(githubRepoId)).thenReturn(Optional.of(repo));
        when(eventRepository.save(any(GitHubEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<GitHubEvent> result = webhookService.recordEventByRepoId(
                deliveryId, "push", null, Map.of("ref", "refs/heads/main"), githubRepoId
        );

        assertThat(result).isPresent();
        assertThat(result.get().getRepositoryId()).isEqualTo(repositoryId);
        assertThat(result.get().getProjectId()).isEqualTo(projectId);
    }
}
