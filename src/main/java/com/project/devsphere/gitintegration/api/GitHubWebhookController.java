package com.project.devsphere.gitintegration.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.gitintegration.application.GitHubActivitySyncService;
import com.project.devsphere.gitintegration.application.GitHubException;
import com.project.devsphere.gitintegration.application.GitHubWebhookService;
import com.project.devsphere.gitintegration.domain.GitHubEvent;
import com.project.devsphere.gitintegration.domain.GitHubRepository;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubRepositoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/github")
public class GitHubWebhookController {

    private final GitHubWebhookService webhookService;
    private final GitHubActivitySyncService activitySyncService;
    private final GitHubRepositoryRepository repositoryRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public GitHubWebhookController(
            GitHubWebhookService webhookService,
            GitHubActivitySyncService activitySyncService,
            GitHubRepositoryRepository repositoryRepository,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.webhookService = Objects.requireNonNull(webhookService, "webhookService must not be null");
        this.activitySyncService = Objects.requireNonNull(activitySyncService, "activitySyncService must not be null");
        this.repositoryRepository = Objects.requireNonNull(repositoryRepository, "repositoryRepository must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Webhook ingress for GitHub events.
     * Publicly accessible, secured by HMAC-SHA256 signature verification.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestBody(required = false) byte[] rawPayload) {

        byte[] payloadBytes = rawPayload != null ? rawPayload : new byte[0];

        // 1. Verify HMAC-SHA256 signature
        webhookService.verifySignature(payloadBytes, signatureHeader, null);

        if (deliveryId == null || deliveryId.isBlank()) {
            throw GitHubException.validationFailed("X-GitHub-Delivery header is required");
        }

        // 2. Check delivery deduplication
        if (webhookService.isDuplicateDelivery(deliveryId)) {
            return ResponseEntity.ok(Map.of("status", "duplicate_ignored", "deliveryId", deliveryId));
        }

        // 3. Parse JSON payload
        Map<String, Object> payloadMap;
        try {
            payloadMap = payloadBytes.length > 0
                    ? objectMapper.readValue(payloadBytes, new TypeReference<Map<String, Object>>() {})
                    : Map.of();
        } catch (Exception ex) {
            throw GitHubException.validationFailed("Invalid JSON payload: " + ex.getMessage());
        }

        // 4. Resolve repository from payload
        Object repoObj = payloadMap.get("repository");
        if (!(repoObj instanceof Map<?, ?> repoMap)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no repository in payload"));
        }

        Object repoIdObj = repoMap.get("id");
        if (!(repoIdObj instanceof Number n)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no repository id"));
        }

        Long githubRepoId = n.longValue();
        Optional<GitHubRepository> repoOpt = repositoryRepository.findByGithubRepoIdAndActiveTrue(githubRepoId);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "repository not connected or inactive"));
        }

        GitHubRepository repo = repoOpt.get();

        // 5. Record event and sync activities
        String action = (String) payloadMap.get("action");
        GitHubEvent event = webhookService.recordEvent(
                deliveryId,
                eventType != null ? eventType : "unknown",
                action,
                payloadMap,
                repo.getId(),
                repo.getProjectId()
        );

        if (event.getProcessStatus() != GitHubEvent.ProcessStatus.DONE) {
            activitySyncService.syncEvent(event);
        }

        return ResponseEntity.ok(Map.of("status", "ok", "deliveryId", deliveryId));
    }

    @ExceptionHandler(GitHubException.class)
    public ResponseEntity<ProblemDetail> handleGitHubException(GitHubException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getCode());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus())
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problem.setTitle("INTERNAL_ERROR");
        problem.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}
