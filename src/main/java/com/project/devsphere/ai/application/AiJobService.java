package com.project.devsphere.ai.application;

import com.project.devsphere.ai.api.dto.AiJobResponse;
import com.project.devsphere.ai.api.dto.CompleteAiJobRequest;
import com.project.devsphere.ai.api.dto.CreateAiJobRequest;
import com.project.devsphere.ai.domain.AiJob;
import com.project.devsphere.ai.infrastructure.persistence.AiJobRepository;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class AiJobService {

    private final AiJobRepository aiJobRepository;
    private final AccessPolicy accessPolicy;
    private final AiValidationGate aiValidationGate;

    public AiJobService(
            AiJobRepository aiJobRepository,
            AccessPolicy accessPolicy,
            AiValidationGate aiValidationGate) {
        this.aiJobRepository = Objects.requireNonNull(aiJobRepository, "aiJobRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.aiValidationGate = Objects.requireNonNull(aiValidationGate, "aiValidationGate must not be null");
    }

    public AiJobResponse createJob(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            CreateAiJobRequest request) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        if (request == null || request.jobType() == null || request.jobType().isBlank()) {
            throw AiException.invalidInput("jobType must not be blank");
        }

        AiJob.JobType jobType;
        try {
            jobType = AiJob.JobType.valueOf(request.jobType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AiException.invalidInput("Invalid jobType: " + request.jobType() + ". Allowed: " + Arrays.toString(AiJob.JobType.values()));
        }

        String modelName = request.modelName() != null && !request.modelName().isBlank()
                ? request.modelName().trim() : "gemini-3.8-flash-high";
        String connectionId = request.connectionId() != null && !request.connectionId().isBlank()
                ? request.connectionId().trim() : "default-ai-connection";
        String promptVersion = request.promptVersion() != null && !request.promptVersion().isBlank()
                ? request.promptVersion().trim() : "1.0.0";
        String outputSchemaVersion = request.outputSchemaVersion() != null && !request.outputSchemaVersion().isBlank()
                ? request.outputSchemaVersion().trim() : "1";

        Map<String, Object> inputRefs = request.inputRefs() != null
                ? new LinkedHashMap<>(request.inputRefs())
                : new LinkedHashMap<>(Map.of("schemaVersion", 1));

        Map<String, Object> routeSnapshot = new LinkedHashMap<>();
        routeSnapshot.put("schemaVersion", 1);
        routeSnapshot.put("route", jobType.name());
        routeSnapshot.put("model", modelName);
        routeSnapshot.put("connectionId", connectionId);

        AiJob job = new AiJob();
        job.setWorkspaceId(workspaceId);
        job.setProjectId(projectId);
        job.setRequestedBy(principal.userId());
        job.setJobType(jobType);
        job.setStatus(AiJob.Status.QUEUED);
        job.setModelName(modelName);
        job.setConnectionId(connectionId);
        job.setRouteSnapshot(routeSnapshot);
        job.setPromptVersion(promptVersion);
        job.setOutputSchemaVersion(outputSchemaVersion);
        job.setInputRefs(inputRefs);
        job.setValidationStatus(AiJob.ValidationStatus.PENDING);
        job.setValidationAttempts((short) 0);
        job.setStartedAt(Instant.now());

        if (request.outputJson() != null && !request.outputJson().isEmpty()) {
            executeValidation(job, request.outputJson(), request.tokensIn(), request.tokensOut(), modelName, projectId);
        }

        AiJob saved = aiJobRepository.save(job);
        return AiJobResponse.from(saved);
    }

    public AiJobResponse completeJob(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID jobId,
            CompleteAiJobRequest request) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        AiJob job = aiJobRepository.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> AiException.notFound("AI Job not found: " + jobId));

        if (request == null || request.outputJson() == null) {
            throw AiException.invalidInput("outputJson must not be null");
        }

        if (job.getValidationAttempts() != null && job.getValidationAttempts() >= 2) {
            throw AiException.maxAttemptsExceeded("Maximum validation attempts (2) exceeded for AI job: " + jobId);
        }

        String validatorModel = request.validatedByModel() != null && !request.validatedByModel().isBlank()
                ? request.validatedByModel().trim() : job.getModelName();

        executeValidation(job, request.outputJson(), request.tokensIn(), request.tokensOut(), validatorModel, projectId);

        AiJob saved = aiJobRepository.save(job);
        return AiJobResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public AiJobResponse getJob(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID jobId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        AiJob job = aiJobRepository.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> AiException.notFound("AI Job not found: " + jobId));

        return AiJobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public List<AiJobResponse> listJobs(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        return aiJobRepository.findAllByProjectId(projectId, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(AiJobResponse::from)
                .toList();
    }

    private void executeValidation(
            AiJob job,
            Map<String, Object> outputJson,
            Integer tokensIn,
            Integer tokensOut,
            String validatorModel,
            UUID projectId) {

        short attempts = job.getValidationAttempts() != null ? (short) (job.getValidationAttempts() + 1) : 1;
        job.setValidationAttempts(attempts);

        job.setOutputJson(outputJson);
        job.setTokensIn(tokensIn != null ? tokensIn : 0);
        job.setTokensOut(tokensOut != null ? tokensOut : 0);
        job.setValidatedByModel(validatorModel);
        if (job.getStartedAt() == null) {
            job.setStartedAt(Instant.now());
        }
        job.setFinishedAt(Instant.now());

        AiValidationGate.ValidationResult result = aiValidationGate.validate(job, outputJson, projectId);

        job.setValidationReport(result.report());
        job.setValidationStatus(result.status());

        if (result.valid()) {
            job.setStatus(AiJob.Status.SUCCESS);
            job.setErrorCode(null);
        } else {
            job.setStatus(AiJob.Status.FAILED);
            job.setErrorCode(result.report().get("error") != null ? result.report().get("error").toString() : "VALIDATION_FAILED");
        }
    }
}
