package com.project.devsphere.gitintegration.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.gitintegration.domain.GitHubEvent;
import com.project.devsphere.gitintegration.domain.GitHubRepository;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubEventRepository;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubRepositoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class GitHubWebhookService {

    private final GitHubEventRepository eventRepository;
    private final GitHubRepositoryRepository repositoryRepository;
    private final ObjectMapper objectMapper;

    @Value("${devsphere.github.webhook-secret:}")
    private String defaultWebhookSecret = "";

    @Autowired
    public GitHubWebhookService(
            GitHubEventRepository eventRepository,
            GitHubRepositoryRepository repositoryRepository,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository must not be null");
        this.repositoryRepository = Objects.requireNonNull(repositoryRepository, "repositoryRepository must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public GitHubWebhookService(
            GitHubEventRepository eventRepository,
            GitHubRepositoryRepository repositoryRepository) {
        this(eventRepository, repositoryRepository, null);
    }

    /**
     * Verifies the GitHub HMAC-SHA256 signature against the raw payload bytes.
     * Uses constant-time MessageDigest.isEqual comparison to eliminate timing attacks.
     * Throws 401 WEBHOOK_SIGNATURE_INVALID if missing or invalid.
     */
    public void verifySignature(byte[] rawPayload, String signatureHeader, String secret) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw GitHubException.webhookSignatureInvalid("Missing X-Hub-Signature-256 header");
        }
        if (!signatureHeader.startsWith("sha256=")) {
            throw GitHubException.webhookSignatureInvalid("Invalid signature format, must start with sha256=");
        }

        String hexSignature = signatureHeader.substring("sha256=".length()).trim();
        byte[] expectedBytes;
        try {
            expectedBytes = HexFormat.of().parseHex(hexSignature);
        } catch (IllegalArgumentException ex) {
            throw GitHubException.webhookSignatureInvalid("Malformed hex in signature header: " + ex.getMessage());
        }

        String effectiveSecret = (secret != null && !secret.isBlank()) ? secret : defaultWebhookSecret;
        if (effectiveSecret == null) {
            effectiveSecret = "";
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    effectiveSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKeySpec);
            byte[] computedHash = mac.doFinal(rawPayload != null ? rawPayload : new byte[0]);

            if (!MessageDigest.isEqual(computedHash, expectedBytes)) {
                throw GitHubException.webhookSignatureInvalid("Webhook signature verification failed");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Failed to calculate HMAC signature", ex);
        }
    }

    /**
     * Checks if a delivery ID has already been recorded.
     */
    @Transactional(readOnly = true)
    public boolean isDuplicateDelivery(String deliveryId) {
        return deliveryId != null && eventRepository.existsByDeliveryId(deliveryId);
    }

    /**
     * Records a webhook event with deduplication.
     * If the delivery ID exists, returns the existing event (200 OK duplicate ignore).
     */
    public GitHubEvent recordEvent(
            String deliveryId,
            String eventType,
            String action,
            Map<String, Object> payload,
            UUID repositoryId,
            UUID projectId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            throw GitHubException.validationFailed("deliveryId must not be blank");
        }

        // Deduplication check
        Optional<GitHubEvent> existing = eventRepository.findByDeliveryId(deliveryId);
        if (existing.isPresent()) {
            return existing.get();
        }

        GitHubEvent event = new GitHubEvent();
        event.setProjectId(projectId);
        event.setRepositoryId(repositoryId);
        event.setDeliveryId(deliveryId);
        event.setEventType(eventType != null ? eventType : "unknown");
        event.setAction(action);
        event.setPayload(payload != null ? payload : Map.of());
        event.setProcessStatus(GitHubEvent.ProcessStatus.RECEIVED);
        event.setAttempts(0);
        event.setReceivedAt(Instant.now());

        try {
            event = eventRepository.save(event);
            eventRepository.flush();
            return event;
        } catch (DataIntegrityViolationException ex) {
            // Concurrent delivery deduplication: fetch existing row
            return eventRepository.findByDeliveryId(deliveryId).orElse(event);
        }
    }

    /**
     * Records a webhook event from a raw JSON string with deduplication.
     */
    public GitHubEvent recordEvent(
            String deliveryId,
            String eventType,
            String action,
            String rawPayloadJson,
            UUID repositoryId,
            UUID projectId) {
        Map<String, Object> payloadMap;
        try {
            payloadMap = rawPayloadJson != null && !rawPayloadJson.isBlank()
                    ? objectMapper.readValue(rawPayloadJson, new TypeReference<Map<String, Object>>() {})
                    : Map.of();
        } catch (Exception ex) {
            payloadMap = Map.of("raw", rawPayloadJson != null ? rawPayloadJson : "");
        }
        return recordEvent(deliveryId, eventType, action, payloadMap, repositoryId, projectId);
    }

    /**
     * Records event by resolving repository and project from github_repo_id.
     * Returns empty if repository is not registered or not active.
     */
    public Optional<GitHubEvent> recordEventByRepoId(
            String deliveryId,
            String eventType,
            String action,
            Map<String, Object> payload,
            Long githubRepoId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            throw GitHubException.validationFailed("deliveryId must not be blank");
        }

        Optional<GitHubEvent> existing = eventRepository.findByDeliveryId(deliveryId);
        if (existing.isPresent()) {
            return existing;
        }

        if (githubRepoId == null) {
            return Optional.empty();
        }

        Optional<GitHubRepository> repoOpt = repositoryRepository.findByGithubRepoIdAndActiveTrue(githubRepoId);
        if (repoOpt.isEmpty()) {
            return Optional.empty();
        }

        GitHubRepository repo = repoOpt.get();
        return Optional.of(recordEvent(deliveryId, eventType, action, payload, repo.getId(), repo.getProjectId()));
    }
}
