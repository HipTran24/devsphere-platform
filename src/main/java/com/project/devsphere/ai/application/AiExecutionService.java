package com.project.devsphere.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.ai.api.dto.AiJobResponse;
import com.project.devsphere.ai.application.AiTaskRouter.ResolvedAiRoute;
import com.project.devsphere.ai.domain.AiJob;
import com.project.devsphere.ai.infrastructure.OpenAiCompatibleClient;
import com.project.devsphere.ai.infrastructure.OpenAiCompatibleClient.ChatPromptMessage;
import com.project.devsphere.ai.infrastructure.OpenAiCompatibleClient.CompletionRequest;
import com.project.devsphere.ai.infrastructure.OpenAiCompatibleClient.CompletionResult;
import com.project.devsphere.ai.infrastructure.persistence.AiJobRepository;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Executes AI jobs against configured OpenAI-compatible providers using a strict split-transaction
 * pattern: short read/claim tx -> HTTP I/O outside any transaction -> short write/validation tx.
 */
@Service
public class AiExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AiExecutionService.class);
    private static final List<String> CHAT_REPLY_KEYS = List.of(
            "reply", "answer", "message", "content", "text", "summary"
    );

    private final AiJobRepository aiJobRepository;
    private final AccessPolicy accessPolicy;
    private final AiTaskRouter aiTaskRouter;
    private final OpenAiCompatibleClient openAiClient;
    private final AiValidationGate aiValidationGate;
    private final TransactionTemplate requiresNewTx;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiExecutionService(
            AiJobRepository aiJobRepository,
            AccessPolicy accessPolicy,
            AiTaskRouter aiTaskRouter,
            OpenAiCompatibleClient openAiClient,
            AiValidationGate aiValidationGate,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this(
                aiJobRepository,
                accessPolicy,
                aiTaskRouter,
                openAiClient,
                aiValidationGate,
                createRequiresNewTemplate(transactionManager),
                objectMapper
        );
    }

    public AiExecutionService(
            AiJobRepository aiJobRepository,
            AccessPolicy accessPolicy,
            AiTaskRouter aiTaskRouter,
            OpenAiCompatibleClient openAiClient,
            AiValidationGate aiValidationGate,
            TransactionTemplate requiresNewTx,
            ObjectMapper objectMapper) {
        this.aiJobRepository = Objects.requireNonNull(aiJobRepository, "aiJobRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.aiTaskRouter = Objects.requireNonNull(aiTaskRouter, "aiTaskRouter must not be null");
        this.openAiClient = Objects.requireNonNull(openAiClient, "openAiClient must not be null");
        this.aiValidationGate = Objects.requireNonNull(aiValidationGate, "aiValidationGate must not be null");
        this.requiresNewTx = Objects.requireNonNull(requiresNewTx, "requiresNewTx must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    private static TransactionTemplate createRequiresNewTemplate(PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Executes an existing {@link AiJob} with external HTTP I/O strictly outside DB transactions.
     */
    public AiJobResponse executeJob(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID jobId) {

        PreparedExecution prepared;
        try {
            prepared = requiresNewTx.execute(status -> claimAndResolveForExecution(principal, workspaceId, projectId, jobId));
        } catch (AiUnavailableBeforeRunException ex) {
            markExistingJobUnavailable(jobId, projectId, ex.getCauseException().getCode());
            throw ex.getCauseException();
        }

        CompletionResult completion;
        try {
            completion = openAiClient.chatCompletion(new CompletionRequest(
                    prepared.route().provider(),
                    prepared.route().baseUrl(),
                    prepared.route().model(),
                    prepared.route().apiKey(),
                    prepared.messages(),
                    0.2d
            ));
        } catch (AiException ex) {
            markExistingJobUnavailable(jobId, projectId, ex.getCode());
            throw ex;
        }

        return requiresNewTx.execute(status -> finalizeJobExecution(
                jobId, projectId, prepared.route().model(), completion));
    }

    /**
     * Best-effort post-commit execution for {@code @ProjectAI} chat prompts.
     * Never throws so a user's committed chat message is never impacted by AI availability.
     */
    public void executeProjectAiChat(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID channelId,
            UUID userMessageId,
            String promptBody,
            ChatReplySaver replySaver) {
        if (principal == null || workspaceId == null || projectId == null || channelId == null
                || userMessageId == null || promptBody == null || replySaver == null) {
            return;
        }

        try {
            PreparedChatExecution prepared = requiresNewTx.execute(status ->
                    createAndClaimChatJob(principal, workspaceId, projectId, channelId, userMessageId, promptBody));
            if (prepared == null) {
                return;
            }

            CompletionResult completion;
            try {
                completion = openAiClient.chatCompletion(new CompletionRequest(
                        prepared.route().provider(),
                        prepared.route().baseUrl(),
                        prepared.route().model(),
                        prepared.route().apiKey(),
                        prepared.messages(),
                        0.2d
                ));
            } catch (AiException ex) {
                markExistingJobUnavailable(prepared.jobId(), projectId, ex.getCode());
                log.warn("@ProjectAI provider call unavailable for job {}: {}", prepared.jobId(), ex.getMessage());
                return;
            }

            requiresNewTx.executeWithoutResult(status -> {
                AiJobResponse completed = finalizeJobExecution(
                        prepared.jobId(), projectId, prepared.route().model(), completion);
                if (AiJob.Status.SUCCESS.name().equals(completed.status())
                        && AiJob.ValidationStatus.VALID.name().equals(completed.validationStatus())) {
                    String replyText = extractChatReplyText(completion.outputJson());
                    replySaver.saveAndPublish(completed.id(), replyText, prepared.route().model());
                } else {
                    log.warn("@ProjectAI job {} failed validation ({})", prepared.jobId(), completed.validationStatus());
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Best-effort @ProjectAI chat execution failed for message {}: {}", userMessageId, ex.getMessage());
        }
    }

    private PreparedExecution claimAndResolveForExecution(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID jobId) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        AiJob job = aiJobRepository.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> AiException.notFound("AI Job not found: " + jobId));

        if (job.getStatus() == AiJob.Status.SUCCESS) {
            throw AiException.unprocessable("AI_JOB_ALREADY_COMPLETED", "AI job already completed: " + jobId);
        }
        if (job.getStatus() == AiJob.Status.RUNNING) {
            throw AiException.conflict("AI_JOB_ALREADY_RUNNING", "AI job is already running: " + jobId);
        }
        if (job.getValidationAttempts() != null && job.getValidationAttempts() >= 2) {
            throw AiException.maxAttemptsExceeded("Maximum validation attempts (2) exceeded for AI job: " + jobId);
        }

        ResolvedAiRoute route;
        try {
            route = aiTaskRouter.resolve(workspaceId, job.getJobType(), job.getConnectionId());
        } catch (AiException ex) {
            throw new AiUnavailableBeforeRunException(ex);
        }

        Instant started = job.getStartedAt() != null ? job.getStartedAt() : Instant.now();
        job.setStartedAt(started);
        job.setStatus(AiJob.Status.RUNNING);
        job.setConnectionId(route.connectionId());
        job.setModelName(route.model());
        job.setRouteSnapshot(buildRouteSnapshot(job.getJobType(), route));
        aiJobRepository.save(job);

        List<ChatPromptMessage> messages = buildPromptMessages(job.getJobType(), job.getInputRefs(), job.getOutputSchemaVersion());
        return new PreparedExecution(route, messages);
    }

    private PreparedChatExecution createAndClaimChatJob(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID channelId,
            UUID userMessageId,
            String promptBody) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        ResolvedAiRoute route;
        try {
            route = aiTaskRouter.resolve(workspaceId, AiJob.JobType.CHAT_ANSWER, null);
        } catch (AiException ex) {
            log.warn("@ProjectAI route unavailable in workspace {}: {}", workspaceId, ex.getMessage());
            return null;
        }

        Map<String, Object> inputRefs = new LinkedHashMap<>();
        inputRefs.put("schemaVersion", 1);
        inputRefs.put("channelId", channelId.toString());
        inputRefs.put("replyToMessageId", userMessageId.toString());
        inputRefs.put("prompt", promptBody);

        Instant now = Instant.now();
        AiJob job = new AiJob();
        job.setWorkspaceId(workspaceId);
        job.setProjectId(projectId);
        job.setRequestedBy(principal.userId());
        job.setJobType(AiJob.JobType.CHAT_ANSWER);
        job.setStatus(AiJob.Status.RUNNING);
        job.setModelName(route.model());
        job.setConnectionId(route.connectionId());
        job.setRouteSnapshot(buildRouteSnapshot(AiJob.JobType.CHAT_ANSWER, route));
        job.setPromptVersion("1.0.0");
        job.setOutputSchemaVersion("1");
        job.setInputRefs(inputRefs);
        job.setValidationStatus(AiJob.ValidationStatus.PENDING);
        job.setValidationAttempts((short) 0);
        job.setStartedAt(now);

        AiJob saved = aiJobRepository.save(job);
        List<ChatPromptMessage> messages = buildPromptMessages(AiJob.JobType.CHAT_ANSWER, inputRefs, "1");
        return new PreparedChatExecution(saved.getId(), route, messages);
    }

    private AiJobResponse finalizeJobExecution(
            UUID jobId,
            UUID projectId,
            String validatorModel,
            CompletionResult completion) {
        AiJob job = aiJobRepository.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> AiException.notFound("AI Job not found: " + jobId));

        short attempts = job.getValidationAttempts() != null
                ? (short) Math.min(2, job.getValidationAttempts() + 1)
                : (short) 1;
        job.setValidationAttempts(attempts);
        job.setOutputJson(completion.outputJson());
        job.setTokensIn(completion.tokensIn() != null ? Math.max(0, completion.tokensIn()) : 0);
        job.setTokensOut(completion.tokensOut() != null ? Math.max(0, completion.tokensOut()) : 0);
        job.setValidatedByModel(validatorModel);

        Instant now = Instant.now();
        if (job.getStartedAt() == null) {
            job.setStartedAt(now);
        }
        job.setFinishedAt(now.isBefore(job.getStartedAt()) ? job.getStartedAt() : now);

        AiValidationGate.ValidationResult result = aiValidationGate.validate(job, completion.outputJson(), projectId);
        job.setValidationReport(result.report());
        job.setValidationStatus(result.status());

        if (result.valid()) {
            job.setStatus(AiJob.Status.SUCCESS);
            job.setErrorCode(null);
        } else {
            job.setStatus(AiJob.Status.FAILED);
            Object err = result.report().get("error");
            String code = err != null ? err.toString() : "VALIDATION_FAILED";
            job.setErrorCode(clampErrorCode(code, "VALIDATION_FAILED"));
        }

        AiJob saved = aiJobRepository.save(job);
        return AiJobResponse.from(saved);
    }

    private void markExistingJobUnavailable(UUID jobId, UUID projectId, String errorCode) {
        try {
            requiresNewTx.executeWithoutResult(status -> aiJobRepository.findByIdAndProjectId(jobId, projectId).ifPresent(job -> {
                Instant now = Instant.now();
                if (job.getStartedAt() == null) {
                    job.setStartedAt(now);
                }
                job.setStatus(AiJob.Status.FAILED);
                job.setErrorCode(clampErrorCode(errorCode, "AI_UNAVAILABLE"));
                job.setFinishedAt(now.isBefore(job.getStartedAt()) ? job.getStartedAt() : now);
                aiJobRepository.save(job);
            }));
        } catch (RuntimeException ex) {
            log.warn("Failed recording AI_UNAVAILABLE status on job {}: {}", jobId, ex.getMessage());
        }
    }

    private List<ChatPromptMessage> buildPromptMessages(
            AiJob.JobType jobType,
            Map<String, Object> inputRefs,
            String outputSchemaVersion) {
        String systemPrompt = "You are DevSphere Project AI. JobType=" + jobType.name()
                + ", schemaVersion=" + (outputSchemaVersion != null ? outputSchemaVersion : "1")
                + ". Respond strictly with a single valid JSON object matching the task schema. "
                + "Never fabricate entity UUIDs not present in inputRefs.";
        String userPrompt;
        try {
            userPrompt = objectMapper.writeValueAsString(inputRefs != null ? inputRefs : Map.of("schemaVersion", 1));
        } catch (JsonProcessingException ex) {
            userPrompt = "{\"schemaVersion\":1}";
        }
        return List.of(
                new ChatPromptMessage("system", systemPrompt),
                new ChatPromptMessage("user", userPrompt)
        );
    }

    private String extractChatReplyText(Map<String, Object> outputJson) {
        if (outputJson != null) {
            for (String key : CHAT_REPLY_KEYS) {
                Object value = outputJson.get(key);
                if (value instanceof String str && !str.isBlank()) {
                    return str.trim();
                }
            }
            try {
                String json = objectMapper.writeValueAsString(outputJson);
                if (json != null && !json.isBlank()) {
                    return json;
                }
            } catch (JsonProcessingException ignored) {
            }
        }
        return "AI response generated.";
    }

    private static Map<String, Object> buildRouteSnapshot(AiJob.JobType jobType, ResolvedAiRoute route) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("route", jobType.name());
        snapshot.put("provider", route.provider().name());
        snapshot.put("model", route.model());
        snapshot.put("connectionId", route.connectionId());
        snapshot.put("privacyPolicy", route.privacyPolicy().name());
        return snapshot;
    }

    private static String clampErrorCode(String raw, String fallback) {
        String value = (raw == null || raw.isBlank()) ? fallback : raw.trim();
        return value.length() > 100 ? value.substring(0, 100) : value;
    }

    @FunctionalInterface
    public interface ChatReplySaver {
        void saveAndPublish(UUID aiJobId, String replyBody, String modelName);
    }

    private record PreparedExecution(ResolvedAiRoute route, List<ChatPromptMessage> messages) {
    }

    private record PreparedChatExecution(UUID jobId, ResolvedAiRoute route, List<ChatPromptMessage> messages) {
    }

    private static final class AiUnavailableBeforeRunException extends RuntimeException {
        private final AiException causeException;

        AiUnavailableBeforeRunException(AiException causeException) {
            super(causeException.getMessage(), causeException);
            this.causeException = causeException;
        }

        AiException getCauseException() {
            return causeException;
        }
    }
}
