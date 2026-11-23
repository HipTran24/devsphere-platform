package com.project.devsphere.codequality.application;

import com.project.devsphere.codequality.domain.CiRun;
import com.project.devsphere.codequality.infrastructure.persistence.CiRunRepository;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class CiRunService {

    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-fA-F]{40}$");

    private final CiRunRepository ciRunRepository;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;

    public CiRunService(
            CiRunRepository ciRunRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        this.ciRunRepository = Objects.requireNonNull(ciRunRepository, "ciRunRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /**
     * Records or updates an automated CI run.
     * Enforces database check constraint: status = 'COMPLETED' requires non-null conclusion and completedAt.
     * Idempotent on (repository_id, github_run_id, run_attempt).
     */
    public CiRun recordCiRun(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            RecordCiRunCommand command) {
        setActorId(principal);
        if (principal != null) {
            accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        }

        if (command == null) {
            throw CodeQualityException.validationFailed("RecordCiRunCommand must not be null");
        }
        if (command.headSha() == null || !SHA_PATTERN.matcher(command.headSha().trim()).matches()) {
            throw CodeQualityException.invalidHeadSha("headSha must be a 40-character hex commit hash");
        }
        if (command.githubRunId() == null || command.githubRunId() <= 0) {
            throw CodeQualityException.validationFailed("githubRunId must be a positive integer");
        }
        int runAttempt = command.runAttempt() != null && command.runAttempt() > 0 ? command.runAttempt() : 1;

        CiRun.Status status;
        try {
            status = command.status() != null ? CiRun.Status.valueOf(command.status().trim().toUpperCase()) : CiRun.Status.QUEUED;
        } catch (IllegalArgumentException ex) {
            status = CiRun.Status.QUEUED;
        }

        CiRun.Conclusion conclusion = null;
        if (command.conclusion() != null && !command.conclusion().isBlank()) {
            try {
                conclusion = CiRun.Conclusion.valueOf(command.conclusion().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                conclusion = null;
            }
        }

        Instant completedAt = command.completedAt();
        Instant startedAt = command.startedAt();

        // Enforce CHECK ((status = 'COMPLETED') = (conclusion IS NOT NULL AND completed_at IS NOT NULL))
        if (status == CiRun.Status.COMPLETED) {
            if (conclusion == null) {
                throw CodeQualityException.validationFailed("COMPLETED status requires a non-null conclusion");
            }
            if (completedAt == null) {
                completedAt = Instant.now();
            }
        } else {
            conclusion = null;
            completedAt = null;
        }

        if (startedAt != null && completedAt != null && completedAt.isBefore(startedAt)) {
            throw CodeQualityException.validationFailed("completedAt must be greater than or equal to startedAt");
        }

        // Resolve repository ID
        UUID repositoryId = command.repositoryId();
        if (repositoryId == null) {
            List<UUID> repoIds = jdbcTemplate.query(
                    "SELECT id FROM github_repositories WHERE project_id = ? AND active = true LIMIT 1",
                    (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                    projectId
            );
            if (repoIds.isEmpty()) {
                repoIds = jdbcTemplate.query(
                        "SELECT id FROM github_repositories WHERE project_id = ? LIMIT 1",
                        (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                        projectId
                );
            }
            if (repoIds.isEmpty()) {
                throw CodeQualityException.validationFailed("No GitHub repository registered for project " + projectId);
            }
            repositoryId = repoIds.get(0);
        }

        // Resolve work item ID if not provided but PR number is given
        UUID workItemId = command.workItemId();
        if (workItemId == null && command.prNumber() != null) {
            List<UUID> itemIds = jdbcTemplate.query(
                    "SELECT work_item_id FROM github_activities WHERE repository_id = ? AND pr_number = ? AND kind = 'PULL_REQUEST' AND work_item_id IS NOT NULL ORDER BY happened_at DESC LIMIT 1",
                    (rs, rowNum) -> UUID.fromString(rs.getString("work_item_id")),
                    repositoryId,
                    command.prNumber()
            );
            if (!itemIds.isEmpty()) {
                workItemId = itemIds.get(0);
            }
        }

        String normalizedHeadSha = command.headSha().trim().toLowerCase();

        // Idempotent upsert on (repository_id, github_run_id, run_attempt)
        Optional<CiRun> existing = ciRunRepository.findByRepositoryIdAndGithubRunIdAndRunAttempt(
                repositoryId, command.githubRunId(), runAttempt);

        CiRun ciRun;
        if (existing.isPresent()) {
            ciRun = existing.get();
            ciRun.setHeadSha(normalizedHeadSha);
            ciRun.setPrNumber(command.prNumber());
            ciRun.setStatus(status);
            ciRun.setConclusion(conclusion);
            if (workItemId != null) {
                ciRun.setWorkItemId(workItemId);
            }
            if (command.checks() != null) {
                ciRun.setChecks(command.checks());
            }
            if (command.metrics() != null) {
                ciRun.setMetrics(command.metrics());
            }
            if (startedAt != null) {
                ciRun.setStartedAt(startedAt);
            }
            ciRun.setCompletedAt(completedAt);
        } else {
            ciRun = new CiRun();
            ciRun.setProjectId(projectId);
            ciRun.setRepositoryId(repositoryId);
            ciRun.setWorkItemId(workItemId);
            ciRun.setGithubRunId(command.githubRunId());
            ciRun.setRunAttempt(runAttempt);
            ciRun.setHeadSha(normalizedHeadSha);
            ciRun.setPrNumber(command.prNumber());
            ciRun.setStatus(status);
            ciRun.setConclusion(conclusion);
            ciRun.setChecks(command.checks() != null ? command.checks() : List.of());
            ciRun.setMetrics(command.metrics() != null ? command.metrics() : Map.of("schemaVersion", 1));
            ciRun.setStartedAt(startedAt);
            ciRun.setCompletedAt(completedAt);
        }

        ciRun = ciRunRepository.save(ciRun);
        ciRunRepository.flush();

        return ciRun;
    }

    /**
     * Lists CI runs with optional filters.
     * Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public List<CiRun> listCiRuns(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            Integer prNumber,
            String headSha) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        if (workItemId != null) {
            return ciRunRepository.findByProjectIdAndWorkItemIdOrderByCreatedAtDesc(projectId, workItemId);
        }
        if (prNumber != null) {
            return ciRunRepository.findByProjectIdAndPrNumberOrderByCreatedAtDesc(projectId, prNumber);
        }
        if (headSha != null && !headSha.isBlank()) {
            return ciRunRepository.findByProjectIdAndHeadShaOrderByCreatedAtDesc(projectId, headSha.trim().toLowerCase());
        }
        return ciRunRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * Gets a single CI run by ID.
     * Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public CiRun getCiRun(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID ciRunId) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        return ciRunRepository.findByIdAndProjectId(ciRunId, projectId)
                .orElseThrow(() -> CodeQualityException.ciRunNotFound("CI run not found with ID: " + ciRunId));
    }

    /**
     * Gets the latest completed CI run for the given head SHA.
     */
    @Transactional(readOnly = true)
    public Optional<CiRun> getLatestCiRunForHeadSha(UUID projectId, String headSha) {
        if (headSha == null || headSha.isBlank()) {
            return Optional.empty();
        }
        return ciRunRepository.findFirstByProjectIdAndHeadShaAndStatusOrderByCompletedAtDesc(
                projectId, headSha.trim().toLowerCase(), CiRun.Status.COMPLETED);
    }

    private void setActorId(SessionService.Principal principal) {
        if (principal != null && principal.userId() != null) {
            try {
                jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, principal.userId().toString());
            } catch (Exception ignored) {
            }
        }
    }
}
