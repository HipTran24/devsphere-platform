package com.project.devsphere.codequality.application;

import com.project.devsphere.codequality.domain.CodeReview;
import com.project.devsphere.codequality.infrastructure.persistence.CodeReviewRepository;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class CodeReviewService {

    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-fA-F]{40}$");

    private final CodeReviewRepository codeReviewRepository;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;

    public CodeReviewService(
            CodeReviewRepository codeReviewRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        this.codeReviewRepository = Objects.requireNonNull(codeReviewRepository, "codeReviewRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /**
     * Submits a human code review.
     * Enforces:
     * - Reviewer holds active DEVELOPER or PM role.
     * - Head SHA matches current PR HEAD SHA.
     * - Author Independence Invariant: Reviewer cannot be the author of the PR.
     * - Context version stamped from current project version.
     */
    public CodeReview submitReview(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            SubmitReviewCommand command) {
        setActorId(principal);
        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.DEVELOPER, ProjectRole.PM));

        if (command == null) {
            throw CodeQualityException.validationFailed("SubmitReviewCommand must not be null");
        }
        if (command.headSha() == null || !SHA_PATTERN.matcher(command.headSha().trim()).matches()) {
            throw CodeQualityException.invalidHeadSha("headSha must be a 40-character hex commit hash");
        }

        String normalizedHeadSha = command.headSha().trim().toLowerCase();
        String scopeStr = command.scope() != null ? command.scope().trim().toUpperCase() : "PR";
        CodeReview.Scope scope;
        try {
            scope = CodeReview.Scope.valueOf(scopeStr);
        } catch (IllegalArgumentException ex) {
            scope = CodeReview.Scope.PR;
        }

        if (scope == CodeReview.Scope.PR && (command.prNumber() == null || command.prNumber() <= 0)) {
            throw CodeQualityException.validationFailed("prNumber must be a positive integer for PR scope review");
        }

        CodeReview.Status status;
        try {
            status = command.status() != null ? CodeReview.Status.valueOf(command.status().trim().toUpperCase()) : CodeReview.Status.PASS;
        } catch (IllegalArgumentException ex) {
            status = CodeReview.Status.PASS;
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

        UUID workItemId = command.workItemId();

        // Check PR head SHA and Author Independence for PR reviews
        if (scope == CodeReview.Scope.PR) {
            List<Map<String, Object>> prRows = jdbcTemplate.queryForList(
                    "SELECT pr_head_sha, actor_login, work_item_id FROM github_activities " +
                            "WHERE repository_id = ? AND pr_number = ? AND kind = 'PULL_REQUEST' " +
                            "ORDER BY happened_at DESC LIMIT 1",
                    repositoryId,
                    command.prNumber()
            );

            if (!prRows.isEmpty()) {
                Map<String, Object> prActivity = prRows.get(0);
                String currentPrHeadSha = (String) prActivity.get("pr_head_sha");

                if (currentPrHeadSha != null && !normalizedHeadSha.equalsIgnoreCase(currentPrHeadSha.trim())) {
                    throw CodeQualityException.prHeadShaMismatch(
                            "Review head SHA (" + normalizedHeadSha + ") does not match current PR head SHA (" + currentPrHeadSha.trim().toLowerCase() + ")"
                    );
                }

                // Author Independence Check
                String prAuthorLogin = (String) prActivity.get("actor_login");
                List<String> logins = jdbcTemplate.query(
                        "SELECT provider_login FROM oauth_accounts WHERE user_id = ? AND provider = 'GITHUB'",
                        (rs, rowNum) -> rs.getString("provider_login"),
                        principal.userId()
                );

                if (logins.isEmpty()) {
                    throw CodeQualityException.authorIndependenceViolation(
                            "Human PR approval requires a linked GitHub identity in oauth_accounts"
                    );
                }

                String reviewerGithubLogin = logins.get(0);
                if (prAuthorLogin != null && reviewerGithubLogin != null
                        && prAuthorLogin.trim().equalsIgnoreCase(reviewerGithubLogin.trim())) {
                    throw CodeQualityException.authorIndependenceViolation(
                            "Human PR approval requires an independent GitHub identity: reviewer cannot review their own PR"
                    );
                }

                if (workItemId == null) {
                    workItemId = (UUID) prActivity.get("work_item_id");
                }
            }
        }

        // Context version from project
        Long contextVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM projects WHERE id = ?",
                Long.class,
                projectId
        );
        if (contextVersion == null) {
            contextVersion = 0L;
        }

        CodeReview review = new CodeReview();
        review.setProjectId(projectId);
        review.setRepositoryId(repositoryId);
        review.setWorkItemId(workItemId);
        review.setPrNumber(command.prNumber());
        review.setHeadSha(normalizedHeadSha);
        review.setContextVersion(contextVersion);
        review.setScope(scope);
        review.setReviewerKind(CodeReview.ReviewerKind.HUMAN);
        review.setReviewerId(principal.userId());
        review.setStatus(status);
        review.setSummary(command.summary());
        review.setFindings(command.findings() != null ? command.findings() : List.of());
        review.setCompletedAt(Instant.now());

        try {
            review = codeReviewRepository.save(review);
            codeReviewRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("Human PR approval requires an independent GitHub identity")) {
                throw CodeQualityException.authorIndependenceViolation("Human PR approval requires an independent GitHub identity");
            }
            if (ex.getMessage() != null && ex.getMessage().contains("Human reviewer requires Developer role")) {
                throw CodeQualityException.accessDenied("Human reviewer requires Developer role");
            }
            throw ex;
        }

        return review;
    }

    /**
     * Lists code reviews with optional filters.
     * Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public List<CodeReview> listReviews(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            Integer prNumber,
            String headSha) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        if (workItemId != null) {
            return codeReviewRepository.findByProjectIdAndWorkItemIdOrderByCreatedAtDesc(projectId, workItemId);
        }
        if (prNumber != null) {
            return codeReviewRepository.findByProjectIdAndPrNumberOrderByCreatedAtDesc(projectId, prNumber);
        }
        if (headSha != null && !headSha.isBlank()) {
            return codeReviewRepository.findByProjectIdAndHeadShaOrderByCreatedAtDesc(projectId, headSha.trim().toLowerCase());
        }
        return codeReviewRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * Gets a single review by ID.
     * Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public CodeReview getReview(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID reviewId) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        return codeReviewRepository.findByIdAndProjectId(reviewId, projectId)
                .orElseThrow(() -> CodeQualityException.codeReviewNotFound("Code review not found with ID: " + reviewId));
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
