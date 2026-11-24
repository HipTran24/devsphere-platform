package com.project.devsphere.gitintegration.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.codequality.domain.CiRun;
import com.project.devsphere.codequality.domain.CodeReview;
import com.project.devsphere.gitintegration.api.dto.GitHubActivityResponse;
import com.project.devsphere.gitintegration.domain.GitHubActivity;
import com.project.devsphere.gitintegration.domain.GitHubEvent;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
public class GitHubActivitySyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubActivitySyncService.class);

    public static final Pattern TASK_KEY_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9]{1,15}-[1-9][0-9]*)\\b");
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{40}$");

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;

    @Autowired
    public GitHubActivitySyncService(
            JdbcTemplate jdbcTemplate,
            AccessPolicy accessPolicy,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Resolves matching work item ID by extracting candidate task keys from input text.
     * Evaluates task keys against active (non-deleted) work items in the specified project.
     */
    public UUID resolveWorkItemId(UUID projectId, String text) {
        if (text == null || text.isBlank() || projectId == null) {
            return null;
        }
        Matcher matcher = TASK_KEY_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidateKey = matcher.group(1);
            UUID workItemId = findWorkItemIdByKey(projectId, candidateKey);
            if (workItemId != null) {
                return workItemId;
            }
        }
        return null;
    }

    private UUID findWorkItemIdByKey(UUID projectId, String itemKey) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM work_items WHERE item_key = ? AND project_id = ? AND deleted_at IS NULL LIMIT 1",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                itemKey, projectId
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Synchronously processes a GitHub event and maps activity, CI run, and review rows.
     * Handles {@code push}, {@code pull_request}, {@code check_run}, {@code workflow_run},
     * and {@code pull_request_review} event types idempotently.
     * Enforces Part H invariant: PR merge does NOT change work item status to DONE.
     */
    public void syncEvent(GitHubEvent event) {
        if (event == null || event.getPayload() == null) {
            return;
        }

        UUID projectId = event.getProjectId();
        UUID repositoryId = event.getRepositoryId();
        String eventType = event.getEventType();
        Map<String, Object> payload = event.getPayload();

        if ("push".equalsIgnoreCase(eventType)) {
            processPushEvent(projectId, repositoryId, payload, event.getReceivedAt());
        } else if ("pull_request".equalsIgnoreCase(eventType)) {
            processPullRequestEvent(projectId, repositoryId, payload, event.getReceivedAt());
        } else if ("check_run".equalsIgnoreCase(eventType)) {
            processCheckRunEvent(projectId, repositoryId, payload, event.getReceivedAt());
        } else if ("workflow_run".equalsIgnoreCase(eventType)) {
            processWorkflowRunEvent(projectId, repositoryId, payload, event.getReceivedAt());
        } else if ("pull_request_review".equalsIgnoreCase(eventType)) {
            processPullRequestReviewEvent(projectId, repositoryId, payload, event.getReceivedAt());
        }

        jdbcTemplate.update(
                "UPDATE github_events SET process_status = 'DONE', processed_at = now(), updated_at = now() WHERE id = ?",
                event.getId()
        );
    }

    /**
     * Persists a single pull request JSON object (from webhook or REST API) into {@code github_activities}.
     * Enforces Part H invariant: never transitions a work item to {@code DONE}.
     */
    public boolean syncPullRequestMap(
            UUID projectId,
            UUID repositoryId,
            Map<String, Object> prMap,
            String fallbackSenderLogin,
            Integer fallbackNumber,
            Instant fallbackHappenedAt) {
        if (prMap == null) {
            return false;
        }

        Integer prNumber = extractPositiveInt(prMap.get("number"));
        if (prNumber == null) {
            prNumber = fallbackNumber != null && fallbackNumber > 0 ? fallbackNumber : null;
        }
        if (prNumber == null || prNumber <= 0) {
            return false;
        }

        String title = asString(prMap.get("title"));
        String body = asString(prMap.get("body"));
        Object mergedAtObj = prMap.get("merged_at");
        boolean merged = Boolean.TRUE.equals(prMap.get("merged"))
                || (mergedAtObj instanceof String ms && !ms.isBlank());
        String rawState = asString(prMap.get("state"));

        String finalState;
        if (merged) {
            finalState = "MERGED";
        } else if ("closed".equalsIgnoreCase(rawState)) {
            finalState = "CLOSED";
        } else {
            finalState = "OPEN";
        }

        String headSha = null;
        String headRef = null;
        Object headObj = prMap.get("head");
        if (headObj instanceof Map<?, ?> headMap) {
            headSha = asString(headMap.get("sha"));
            headRef = asString(headMap.get("ref"));
        }
        String cleanHeadSha = normalizeSha(headSha);

        String actorLogin = extractNestedString(prMap, "user", "login");
        if (actorLogin == null || actorLogin.isBlank()) {
            actorLogin = fallbackSenderLogin;
        }

        UUID prWorkItemId = resolveWorkItemId(projectId, title);
        if (prWorkItemId == null && headRef != null) {
            prWorkItemId = resolveWorkItemId(projectId, headRef);
        }
        if (prWorkItemId == null && body != null) {
            prWorkItemId = resolveWorkItemId(projectId, body);
        }

        Instant prHappenedAt = parseInstantOrNull(mergedAtObj);
        if (prHappenedAt == null) {
            prHappenedAt = parseInstantOrNull(prMap.get("updated_at"));
        }
        if (prHappenedAt == null) {
            prHappenedAt = parseInstantOrNull(prMap.get("created_at"));
        }
        if (prHappenedAt == null) {
            prHappenedAt = fallbackHappenedAt != null ? fallbackHappenedAt : Instant.now();
        }

        upsertActivity(
                projectId,
                repositoryId,
                prWorkItemId,
                GitHubActivity.Kind.PULL_REQUEST,
                String.valueOf(prNumber),
                null,
                prNumber,
                cleanHeadSha,
                actorLogin,
                finalState,
                title,
                prMap,
                prHappenedAt
        );

        // NOTE: Part H Invariant (FC-04.03):
        // Pull Request merge does NOT complete or advance work item status to DONE.
        // Task lifecycle status remains strictly controlled by PM acceptance.
        return true;
    }

    /**
     * Persists a commit returned by the GitHub REST API ({@code GET /repos/{owner}/{repo}/commits}).
     */
    public boolean syncRestCommitMap(
            UUID projectId,
            UUID repositoryId,
            Map<String, Object> commitItem,
            Instant fallbackTime) {
        if (commitItem == null) {
            return false;
        }

        String sha = normalizeSha(asString(commitItem.get("sha")));
        if (sha == null) {
            sha = normalizeSha(asString(commitItem.get("id")));
        }
        if (sha == null) {
            return false;
        }

        String message = null;
        String authorName = null;
        Instant commitTimestamp = null;
        Object commitObj = commitItem.get("commit");
        if (commitObj instanceof Map<?, ?> commitDetails) {
            message = asString(commitDetails.get("message"));
            Object commitAuthorObj = commitDetails.get("author");
            if (commitAuthorObj instanceof Map<?, ?> commitAuthorMap) {
                authorName = asString(commitAuthorMap.get("name"));
                commitTimestamp = parseInstantOrNull(commitAuthorMap.get("date"));
            }
        }
        if (message == null) {
            message = asString(commitItem.get("message"));
        }
        String actorLogin = extractNestedString(commitItem, "author", "login");
        if (actorLogin == null || actorLogin.isBlank()) {
            actorLogin = authorName;
        }
        if (commitTimestamp == null) {
            commitTimestamp = fallbackTime != null ? fallbackTime : Instant.now();
        }

        UUID workItemId = resolveWorkItemId(projectId, message);

        upsertActivity(
                projectId,
                repositoryId,
                workItemId,
                GitHubActivity.Kind.COMMIT,
                sha,
                sha,
                null,
                null,
                actorLogin,
                "COMMITTED",
                message,
                commitItem,
                commitTimestamp
        );
        return true;
    }

    /**
     * Persists a GitHub check run object (from {@code check_run} webhook or REST API) into {@code ci_runs}.
     */
    public boolean syncCheckRunMap(
            UUID projectId,
            UUID repositoryId,
            Map<String, Object> checkRunMap,
            Integer fallbackPrNumber,
            UUID fallbackWorkItemId,
            Instant fallbackTime) {
        if (checkRunMap == null) {
            return false;
        }

        Long githubRunId = extractPositiveLong(checkRunMap.get("id"));
        if (githubRunId == null) {
            return false;
        }

        int runAttempt = extractPositiveIntOrDefault(checkRunMap.get("run_attempt"), 1);

        String headSha = normalizeSha(asString(checkRunMap.get("head_sha")));
        String headBranch = null;
        Object suiteObj = checkRunMap.get("check_suite");
        if (suiteObj instanceof Map<?, ?> suiteMap) {
            if (headSha == null) {
                headSha = normalizeSha(asString(suiteMap.get("head_sha")));
            }
            headBranch = asString(suiteMap.get("head_branch"));
        }
        if (headSha == null) {
            return false;
        }

        Integer prNumber = extractFirstPullRequestNumber(checkRunMap.get("pull_requests"));
        if (prNumber == null && suiteObj instanceof Map<?, ?> suiteMap) {
            prNumber = extractFirstPullRequestNumber(suiteMap.get("pull_requests"));
        }
        if (prNumber == null && fallbackPrNumber != null && fallbackPrNumber > 0) {
            prNumber = fallbackPrNumber;
        }

        String name = asString(checkRunMap.get("name"));
        CiRun.Status status = mapCiStatus(asString(checkRunMap.get("status")));
        CiRun.Conclusion conclusion = mapCiConclusion(asString(checkRunMap.get("conclusion")));
        Instant startedAt = parseInstantOrNull(checkRunMap.get("started_at"));
        Instant completedAt = parseInstantOrNull(checkRunMap.get("completed_at"));

        Map<String, Object> checkEntry = new LinkedHashMap<>();
        checkEntry.put("name", name != null ? name : "check_run");
        checkEntry.put("status", status.name());
        if (conclusion != null) {
            checkEntry.put("conclusion", conclusion.name());
        }
        String htmlUrl = asString(checkRunMap.get("html_url"));
        if (htmlUrl != null && !htmlUrl.isBlank()) {
            checkEntry.put("htmlUrl", htmlUrl);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("schemaVersion", 1);
        metrics.put("source", "check_run");
        if (name != null && !name.isBlank()) {
            metrics.put("name", name);
        }

        UUID workItemId = resolveCiWorkItemId(projectId, repositoryId, prNumber, headSha, fallbackWorkItemId, headBranch, name);
        upsertCiRun(
                projectId,
                repositoryId,
                workItemId,
                githubRunId,
                runAttempt,
                headSha,
                prNumber,
                status,
                conclusion,
                List.of(checkEntry),
                metrics,
                startedAt,
                completedAt,
                fallbackTime
        );
        return true;
    }

    /**
     * Persists a GitHub pull request review (from {@code pull_request_review} webhook or REST API)
     * into {@code code_reviews} and {@code github_activities (HUMAN_REVIEW)} after enforcing:
     * <ul>
     *   <li>Reviewer resolves via {@code oauth_accounts (provider='GITHUB', lower(provider_login)=lower(?))}</li>
     *   <li>Review commit SHA matches current PR {@code pr_head_sha}</li>
     *   <li>Reviewer is not the PR author (author independence / no self-approval)</li>
     *   <li>Reviewer holds an active {@code DEVELOPER} role on the project</li>
     * </ul>
     */
    public boolean syncPullRequestReview(
            UUID projectId,
            UUID repositoryId,
            Integer prNumber,
            String payloadPrHeadSha,
            String payloadPrAuthorLogin,
            UUID fallbackWorkItemId,
            Map<String, Object> reviewMap,
            Instant fallbackTime) {
        if (reviewMap == null || prNumber == null || prNumber <= 0) {
            return false;
        }

        CodeReview.Status mappedStatus = mapGitHubReviewState(asString(reviewMap.get("state")));
        if (mappedStatus == null) {
            return false;
        }

        String reviewerLogin = extractNestedString(reviewMap, "user", "login");
        if (reviewerLogin == null || reviewerLogin.isBlank()) {
            return false;
        }
        String cleanReviewerLogin = reviewerLogin.trim();

        String reviewCommitSha = normalizeSha(asString(reviewMap.get("commit_id")));
        if (reviewCommitSha == null) {
            reviewCommitSha = normalizeSha(payloadPrHeadSha);
        }
        if (reviewCommitSha == null) {
            log.debug("Skipping PR #{} review: missing valid 40-char commit_id", prNumber);
            return false;
        }

        // Load current PR activity state from github_activities if available
        String dbPrHeadSha = null;
        String dbPrAuthorLogin = null;
        UUID dbWorkItemId = null;
        List<Map<String, Object>> prRows = jdbcTemplate.queryForList(
                """
                SELECT pr_head_sha, actor_login, work_item_id
                FROM github_activities
                WHERE repository_id = ? AND pr_number = ? AND kind = 'PULL_REQUEST'
                ORDER BY happened_at DESC
                LIMIT 1
                """,
                repositoryId,
                prNumber
        );
        if (!prRows.isEmpty()) {
            Map<String, Object> prRow = prRows.get(0);
            dbPrHeadSha = normalizeSha(asString(prRow.get("pr_head_sha")));
            dbPrAuthorLogin = asString(prRow.get("actor_login"));
            Object wiObj = prRow.get("work_item_id");
            if (wiObj instanceof UUID uuid) {
                dbWorkItemId = uuid;
            }
        }

        // 1. Enforce head SHA matches current PR head SHA (reject stale reviews)
        String authoritativeHeadSha = dbPrHeadSha != null ? dbPrHeadSha : normalizeSha(payloadPrHeadSha);
        if (authoritativeHeadSha != null && !authoritativeHeadSha.equals(reviewCommitSha)) {
            log.info("Rejecting stale PR #{} review from {}: commit {} does not match current PR head {}",
                    prNumber, cleanReviewerLogin, reviewCommitSha, authoritativeHeadSha);
            return false;
        }

        // 2. Enforce author independence: reject self-approval when reviewer equals PR author
        if ((dbPrAuthorLogin != null && dbPrAuthorLogin.trim().equalsIgnoreCase(cleanReviewerLogin))
                || (payloadPrAuthorLogin != null && payloadPrAuthorLogin.trim().equalsIgnoreCase(cleanReviewerLogin))) {
            log.info("Rejecting self-review on PR #{} by PR author {}", prNumber, cleanReviewerLogin);
            return false;
        }

        // 3. Resolve reviewer user via oauth_accounts (provider='GITHUB', lower(provider_login)=lower(?))
        List<UUID> reviewerUserIds = jdbcTemplate.query(
                """
                SELECT user_id
                FROM oauth_accounts
                WHERE provider = 'GITHUB' AND lower(provider_login) = lower(?)
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getObject("user_id", UUID.class),
                cleanReviewerLogin
        );
        if (reviewerUserIds.isEmpty()) {
            log.debug("Skipping PR #{} review: GitHub login '{}' is not linked in oauth_accounts", prNumber, cleanReviewerLogin);
            return false;
        }
        UUID reviewerId = reviewerUserIds.get(0);

        // 4. Verify reviewer holds active DEVELOPER role on the project (matching V006 ds_has_project_role)
        Boolean hasDeveloperRole = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM project_members pm
                    JOIN project_member_roles pr ON pr.project_member_id = pm.id AND pr.project_id = pm.project_id
                    JOIN projects p0 ON p0.id = pm.project_id AND p0.workspace_id = pm.workspace_id
                    JOIN workspace_members wm ON wm.workspace_id = p0.workspace_id AND wm.user_id = pm.user_id
                    JOIN users usr ON usr.id = pm.user_id
                    WHERE pm.project_id = ?
                      AND pm.user_id = ?
                      AND pm.status = 'ACTIVE'
                      AND wm.status = 'ACTIVE'
                      AND usr.status = 'ACTIVE'
                      AND pr.revoked_at IS NULL
                      AND pr.role = 'DEVELOPER'
                )
                """,
                Boolean.class,
                projectId,
                reviewerId
        );
        if (!Boolean.TRUE.equals(hasDeveloperRole)) {
            log.info("Rejecting PR #{} review from {}: user {} does not hold active DEVELOPER role",
                    prNumber, cleanReviewerLogin, reviewerId);
            return false;
        }

        // 5. Double-check reviewer user is not also linked to the DB PR author login (multi-alias safety)
        if (dbPrAuthorLogin != null && !dbPrAuthorLogin.isBlank()) {
            Boolean sameAuthorAccount = jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM oauth_accounts
                        WHERE provider = 'GITHUB'
                          AND user_id = ?
                          AND lower(provider_login) = lower(?)
                    )
                    """,
                    Boolean.class,
                    reviewerId,
                    dbPrAuthorLogin.trim()
            );
            if (Boolean.TRUE.equals(sameAuthorAccount)) {
                log.info("Rejecting PR #{} self-review via linked GitHub account for user {}", prNumber, reviewerId);
                return false;
            }
        }

        UUID workItemId = dbWorkItemId != null ? dbWorkItemId : fallbackWorkItemId;
        Long contextVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM projects WHERE id = ?",
                Long.class,
                projectId
        );
        if (contextVersion == null) {
            contextVersion = 0L;
        }

        Long githubReviewId = extractPositiveLong(reviewMap.get("id"));
        String reviewBody = asString(reviewMap.get("body"));
        Instant submittedAt = parseInstantOrNull(reviewMap.get("submitted_at"));
        if (submittedAt == null) {
            submittedAt = fallbackTime != null ? fallbackTime : Instant.now();
        }

        Map<String, Object> gateSnapshot = new LinkedHashMap<>();
        gateSnapshot.put("schemaVersion", 1);
        gateSnapshot.put("source", "github");
        gateSnapshot.put("reviewerLogin", cleanReviewerLogin);
        if (githubReviewId != null) {
            gateSnapshot.put("githubReviewId", githubReviewId);
        }
        String gateSnapshotJson = toSafeJson(gateSnapshot, "{\"schemaVersion\":1}");

        // 6. Idempotent upsert into code_reviews guarded by a JDBC SAVEPOINT so PostgreSQL
        //    integrity/trigger rejections (SQLState 23xxx) never abort the outer transaction (25P02).
        UUID existingReviewId = findExistingHumanReviewId(repositoryId, prNumber, reviewCommitSha, reviewerId, githubReviewId);
        boolean reviewPersisted = upsertHumanCodeReviewWithSavepoint(
                existingReviewId,
                projectId,
                repositoryId,
                workItemId,
                prNumber,
                reviewCommitSha,
                contextVersion,
                reviewBody,
                mappedStatus,
                gateSnapshotJson,
                reviewerId,
                submittedAt
        );
        if (!reviewPersisted) {
            return false;
        }

        // 7. Record HUMAN_REVIEW activity in github_activities
        String objectId = githubReviewId != null
                ? "review-" + githubReviewId
                : "review-" + prNumber + "-" + cleanReviewerLogin.toLowerCase(Locale.ROOT) + "-" + reviewCommitSha.substring(0, 12);
        Map<String, Object> activityPayload = new LinkedHashMap<>();
        if (githubReviewId != null) {
            activityPayload.put("reviewId", githubReviewId);
        }
        activityPayload.put("prNumber", prNumber);
        activityPayload.put("commitId", reviewCommitSha);
        activityPayload.put("reviewerLogin", cleanReviewerLogin);
        activityPayload.put("state", mappedStatus.name());
        if (reviewBody != null) {
            activityPayload.put("body", reviewBody);
        }

        upsertActivity(
                projectId,
                repositoryId,
                workItemId,
                GitHubActivity.Kind.HUMAN_REVIEW,
                objectId,
                reviewCommitSha,
                prNumber,
                reviewCommitSha,
                cleanReviewerLogin,
                mappedStatus.name(),
                "PR #" + prNumber + " review (" + mappedStatus.name() + ")",
                activityPayload,
                submittedAt
        );
        return true;
    }

    @SuppressWarnings("unchecked")
    private void processPushEvent(UUID projectId, UUID repositoryId, Map<String, Object> payload, Instant receivedAt) {
        String ref = asString(payload.get("ref"));
        String afterSha = asString(payload.get("after"));
        String cleanAfterSha = normalizeSha(afterSha);

        String senderLogin = extractNestedString(payload, "sender", "login");

        // 1. Process Branch Activity
        if (ref != null && ref.startsWith("refs/heads/")) {
            String branchName = ref.substring("refs/heads/".length());
            UUID branchWorkItemId = resolveWorkItemId(projectId, branchName);

            upsertActivity(
                    projectId,
                    repositoryId,
                    branchWorkItemId,
                    GitHubActivity.Kind.BRANCH,
                    ref,
                    cleanAfterSha,
                    null,
                    null,
                    senderLogin,
                    "ACTIVE",
                    branchName,
                    payload,
                    receivedAt
            );
        }

        // 2. Process Commits
        Object commitsObj = payload.get("commits");
        if (commitsObj instanceof List<?> commitsList) {
            for (Object commitItem : commitsList) {
                if (commitItem instanceof Map<?, ?> commitMap) {
                    String commitId = asString(commitMap.get("id"));
                    String cleanCommitSha = normalizeSha(commitId);
                    if (cleanCommitSha == null) {
                        continue;
                    }
                    String message = asString(commitMap.get("message"));
                    UUID commitWorkItemId = resolveWorkItemId(projectId, message);

                    String commitAuthorLogin = null;
                    Object authorObj = commitMap.get("author");
                    if (authorObj instanceof Map<?, ?> authorMap) {
                        commitAuthorLogin = asString(authorMap.get("username"));
                        if (commitAuthorLogin == null || commitAuthorLogin.isBlank()) {
                            commitAuthorLogin = asString(authorMap.get("name"));
                        }
                    }
                    if (commitAuthorLogin == null || commitAuthorLogin.isBlank()) {
                        commitAuthorLogin = senderLogin;
                    }

                    Instant commitTimestamp = parseInstantOrNull(commitMap.get("timestamp"));
                    if (commitTimestamp == null) {
                        commitTimestamp = receivedAt;
                    }

                    upsertActivity(
                            projectId,
                            repositoryId,
                            commitWorkItemId,
                            GitHubActivity.Kind.COMMIT,
                            cleanCommitSha,
                            cleanCommitSha,
                            null,
                            null,
                            commitAuthorLogin,
                            "COMMITTED",
                            message,
                            (Map<String, Object>) commitMap,
                            commitTimestamp
                    );
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processPullRequestEvent(UUID projectId, UUID repositoryId, Map<String, Object> payload, Instant receivedAt) {
        Object prObj = payload.get("pull_request");
        if (!(prObj instanceof Map<?, ?> prMap)) {
            return;
        }
        String senderLogin = extractNestedString(payload, "sender", "login");
        Integer fallbackNumber = extractPositiveInt(payload.get("number"));
        syncPullRequestMap(projectId, repositoryId, (Map<String, Object>) prMap, senderLogin, fallbackNumber, receivedAt);
    }

    @SuppressWarnings("unchecked")
    private void processCheckRunEvent(UUID projectId, UUID repositoryId, Map<String, Object> payload, Instant receivedAt) {
        Object checkRunObj = payload.get("check_run");
        if (!(checkRunObj instanceof Map<?, ?> checkRunMap)) {
            return;
        }
        syncCheckRunMap(projectId, repositoryId, (Map<String, Object>) checkRunMap, null, null, receivedAt);
    }

    private void processWorkflowRunEvent(UUID projectId, UUID repositoryId, Map<String, Object> payload, Instant receivedAt) {
        Object workflowObj = payload.get("workflow_run");
        if (!(workflowObj instanceof Map<?, ?> workflowMap)) {
            return;
        }

        Long githubRunId = extractPositiveLong(workflowMap.get("id"));
        if (githubRunId == null) {
            return;
        }

        int runAttempt = extractPositiveIntOrDefault(workflowMap.get("run_attempt"), 1);
        String headSha = normalizeSha(asString(workflowMap.get("head_sha")));
        if (headSha == null) {
            return;
        }

        Integer prNumber = extractFirstPullRequestNumber(workflowMap.get("pull_requests"));
        String name = asString(workflowMap.get("name"));
        String displayTitle = asString(workflowMap.get("display_title"));
        String headBranch = asString(workflowMap.get("head_branch"));

        CiRun.Status status = mapCiStatus(asString(workflowMap.get("status")));
        CiRun.Conclusion conclusion = mapCiConclusion(asString(workflowMap.get("conclusion")));

        Instant startedAt = parseInstantOrNull(workflowMap.get("run_started_at"));
        if (startedAt == null) {
            startedAt = parseInstantOrNull(workflowMap.get("created_at"));
        }
        Instant completedAt = parseInstantOrNull(workflowMap.get("completed_at"));
        if (completedAt == null && status == CiRun.Status.COMPLETED) {
            completedAt = parseInstantOrNull(workflowMap.get("updated_at"));
        }

        Map<String, Object> checkEntry = new LinkedHashMap<>();
        checkEntry.put("name", name != null ? name : "workflow_run");
        checkEntry.put("status", status.name());
        if (conclusion != null) {
            checkEntry.put("conclusion", conclusion.name());
        }
        String htmlUrl = asString(workflowMap.get("html_url"));
        if (htmlUrl != null && !htmlUrl.isBlank()) {
            checkEntry.put("htmlUrl", htmlUrl);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("schemaVersion", 1);
        metrics.put("source", "workflow_run");
        if (name != null && !name.isBlank()) {
            metrics.put("name", name);
        }
        if (displayTitle != null && !displayTitle.isBlank()) {
            metrics.put("displayTitle", displayTitle);
        }

        UUID workItemId = resolveCiWorkItemId(projectId, repositoryId, prNumber, headSha, null, headBranch, displayTitle != null ? displayTitle : name);
        upsertCiRun(
                projectId,
                repositoryId,
                workItemId,
                githubRunId,
                runAttempt,
                headSha,
                prNumber,
                status,
                conclusion,
                List.of(checkEntry),
                metrics,
                startedAt,
                completedAt,
                receivedAt
        );
    }

    @SuppressWarnings("unchecked")
    private void processPullRequestReviewEvent(UUID projectId, UUID repositoryId, Map<String, Object> payload, Instant receivedAt) {
        Object reviewObj = payload.get("review");
        Object prObj = payload.get("pull_request");
        if (!(reviewObj instanceof Map<?, ?> reviewMap) || !(prObj instanceof Map<?, ?> prMap)) {
            return;
        }

        Integer prNumber = extractPositiveInt(prMap.get("number"));
        if (prNumber == null) {
            prNumber = extractPositiveInt(payload.get("number"));
        }
        if (prNumber == null) {
            return;
        }

        String prHeadSha = extractNestedString(prMap, "head", "sha");
        String prAuthorLogin = extractNestedString(prMap, "user", "login");
        String prTitle = asString(prMap.get("title"));
        String prHeadRef = extractNestedString(prMap, "head", "ref");
        String prBody = asString(prMap.get("body"));

        UUID fallbackWorkItemId = resolveWorkItemId(projectId, prTitle);
        if (fallbackWorkItemId == null && prHeadRef != null) {
            fallbackWorkItemId = resolveWorkItemId(projectId, prHeadRef);
        }
        if (fallbackWorkItemId == null && prBody != null) {
            fallbackWorkItemId = resolveWorkItemId(projectId, prBody);
        }

        syncPullRequestReview(
                projectId,
                repositoryId,
                prNumber,
                prHeadSha,
                prAuthorLogin,
                fallbackWorkItemId,
                (Map<String, Object>) reviewMap,
                receivedAt
        );
    }

    private void upsertCiRun(
            UUID projectId,
            UUID repositoryId,
            UUID workItemId,
            long githubRunId,
            int runAttempt,
            String headSha,
            Integer prNumber,
            CiRun.Status status,
            CiRun.Conclusion conclusion,
            List<Map<String, Object>> checks,
            Map<String, Object> metrics,
            Instant startedAt,
            Instant completedAt,
            Instant fallbackTime) {

        CiRun.Conclusion effectiveConclusion = conclusion;
        Instant effectiveCompletedAt = completedAt;
        // Enforce V004: CHECK ((status = 'COMPLETED') = (conclusion IS NOT NULL AND completed_at IS NOT NULL))
        if (status == CiRun.Status.COMPLETED) {
            if (effectiveConclusion == null) {
                effectiveConclusion = CiRun.Conclusion.NEUTRAL;
            }
            if (effectiveCompletedAt == null) {
                effectiveCompletedAt = fallbackTime != null ? fallbackTime : Instant.now();
            }
            if (startedAt != null && effectiveCompletedAt.isBefore(startedAt)) {
                effectiveCompletedAt = startedAt;
            }
        } else {
            effectiveConclusion = null;
            effectiveCompletedAt = null;
        }

        String checksJson = toSafeJson(checks, "[]");
        String metricsJson = toSafeJson(metrics, "{\"schemaVersion\":1}");

        String sql = """
                INSERT INTO ci_runs (
                    id, project_id, repository_id, work_item_id, github_run_id, run_attempt,
                    head_sha, pr_number, status, conclusion, checks, metrics,
                    started_at, completed_at, created_at, updated_at, version
                ) VALUES (
                    gen_random_uuid(), ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?::jsonb, ?::jsonb,
                    ?, ?, now(), now(), 0
                )
                ON CONFLICT (repository_id, github_run_id, run_attempt) DO UPDATE SET
                    work_item_id = COALESCE(EXCLUDED.work_item_id, ci_runs.work_item_id),
                    head_sha = EXCLUDED.head_sha,
                    pr_number = COALESCE(EXCLUDED.pr_number, ci_runs.pr_number),
                    status = EXCLUDED.status,
                    conclusion = EXCLUDED.conclusion,
                    checks = EXCLUDED.checks,
                    metrics = EXCLUDED.metrics,
                    started_at = COALESCE(EXCLUDED.started_at, ci_runs.started_at),
                    completed_at = CASE
                        WHEN EXCLUDED.completed_at IS NULL THEN NULL
                        WHEN COALESCE(EXCLUDED.started_at, ci_runs.started_at) IS NOT NULL
                             AND EXCLUDED.completed_at < COALESCE(EXCLUDED.started_at, ci_runs.started_at)
                            THEN COALESCE(EXCLUDED.started_at, ci_runs.started_at)
                        ELSE EXCLUDED.completed_at
                    END,
                    updated_at = now(),
                    version = ci_runs.version + 1
                """;

        jdbcTemplate.update(
                sql,
                projectId,
                repositoryId,
                workItemId,
                githubRunId,
                runAttempt,
                headSha,
                prNumber,
                status.name(),
                effectiveConclusion != null ? effectiveConclusion.name() : null,
                checksJson,
                metricsJson,
                startedAt != null ? Timestamp.from(startedAt) : null,
                effectiveCompletedAt != null ? Timestamp.from(effectiveCompletedAt) : null
        );
    }

    private boolean upsertHumanCodeReviewWithSavepoint(
            UUID existingReviewId,
            UUID projectId,
            UUID repositoryId,
            UUID workItemId,
            int prNumber,
            String reviewCommitSha,
            long contextVersion,
            String reviewBody,
            CodeReview.Status mappedStatus,
            String gateSnapshotJson,
            UUID reviewerId,
            Instant submittedAt) {
        Boolean persisted = jdbcTemplate.execute((ConnectionCallback<Boolean>) con -> {
            Savepoint sp = null;
            if (!con.getAutoCommit()) {
                sp = con.setSavepoint("ds_code_review_upsert");
            }
            try {
                if (existingReviewId != null) {
                    try (PreparedStatement ps = con.prepareStatement("""
                            UPDATE code_reviews
                            SET work_item_id = COALESCE(?, work_item_id),
                                head_sha = ?,
                                context_version = ?,
                                status = ?,
                                summary = ?,
                                gate_snapshot = ?::jsonb,
                                completed_at = ?,
                                updated_at = now(),
                                version = version + 1
                            WHERE id = ?
                            """)) {
                        ps.setObject(1, workItemId, Types.OTHER);
                        ps.setString(2, reviewCommitSha);
                        ps.setLong(3, contextVersion);
                        ps.setString(4, mappedStatus.name());
                        if (reviewBody != null) {
                            ps.setString(5, reviewBody);
                        } else {
                            ps.setNull(5, Types.VARCHAR);
                        }
                        ps.setString(6, gateSnapshotJson);
                        ps.setTimestamp(7, Timestamp.from(submittedAt));
                        ps.setObject(8, existingReviewId, Types.OTHER);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = con.prepareStatement("""
                            INSERT INTO code_reviews (
                                id, project_id, repository_id, work_item_id, pr_number, head_sha,
                                context_version, scope, reviewer_kind, findings, summary, status,
                                gate_snapshot, reviewer_id, completed_at, created_at, updated_at, version
                            ) VALUES (
                                gen_random_uuid(), ?, ?, ?, ?, ?,
                                ?, 'PR', 'HUMAN', '[]'::jsonb, ?, ?,
                                ?::jsonb, ?, ?, now(), now(), 0
                            )
                            """)) {
                        ps.setObject(1, projectId, Types.OTHER);
                        ps.setObject(2, repositoryId, Types.OTHER);
                        ps.setObject(3, workItemId, Types.OTHER);
                        ps.setInt(4, prNumber);
                        ps.setString(5, reviewCommitSha);
                        ps.setLong(6, contextVersion);
                        if (reviewBody != null) {
                            ps.setString(7, reviewBody);
                        } else {
                            ps.setNull(7, Types.VARCHAR);
                        }
                        ps.setString(8, mappedStatus.name());
                        ps.setString(9, gateSnapshotJson);
                        ps.setObject(10, reviewerId, Types.OTHER);
                        ps.setTimestamp(11, Timestamp.from(submittedAt));
                        ps.executeUpdate();
                    }
                }
                if (sp != null) {
                    con.releaseSavepoint(sp);
                }
                return Boolean.TRUE;
            } catch (SQLException ex) {
                String sqlState = ex.getSQLState();
                if (sqlState != null && sqlState.startsWith("23")) {
                    if (sp != null) {
                        con.rollback(sp);
                        con.releaseSavepoint(sp);
                    }
                    log.warn("Code review rejected by database integrity guard on PR #{} (SQLState={}): {}",
                            prNumber, sqlState, ex.getMessage());
                    return Boolean.FALSE;
                }
                throw ex;
            }
        });
        return Boolean.TRUE.equals(persisted);
    }

    private UUID findExistingHumanReviewId(
            UUID repositoryId,
            Integer prNumber,
            String headSha,
            UUID reviewerId,
            Long githubReviewId) {
        if (githubReviewId != null) {
            List<UUID> ids = jdbcTemplate.query(
                    """
                    SELECT id
                    FROM code_reviews
                    WHERE repository_id = ?
                      AND pr_number = ?
                      AND reviewer_id = ?
                      AND reviewer_kind = 'HUMAN'
                      AND scope = 'PR'
                      AND gate_snapshot->>'githubReviewId' = ?
                    LIMIT 1
                    """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    repositoryId,
                    prNumber,
                    reviewerId,
                    String.valueOf(githubReviewId)
            );
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        } else {
            List<UUID> ids = jdbcTemplate.query(
                    """
                    SELECT id
                    FROM code_reviews
                    WHERE repository_id = ?
                      AND pr_number = ?
                      AND head_sha = ?
                      AND reviewer_id = ?
                      AND reviewer_kind = 'HUMAN'
                      AND scope = 'PR'
                    ORDER BY created_at DESC
                    LIMIT 1
                    """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    repositoryId,
                    prNumber,
                    headSha,
                    reviewerId
            );
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        }
        return null;
    }

    private UUID resolveCiWorkItemId(
            UUID projectId,
            UUID repositoryId,
            Integer prNumber,
            String headSha,
            UUID fallbackWorkItemId,
            String branchText,
            String titleText) {
        if (fallbackWorkItemId != null) {
            return fallbackWorkItemId;
        }
        if (prNumber != null && prNumber > 0) {
            List<UUID> ids = jdbcTemplate.query(
                    """
                    SELECT work_item_id
                    FROM github_activities
                    WHERE repository_id = ? AND pr_number = ? AND kind = 'PULL_REQUEST' AND work_item_id IS NOT NULL
                    ORDER BY happened_at DESC
                    LIMIT 1
                    """,
                    (rs, rowNum) -> rs.getObject("work_item_id", UUID.class),
                    repositoryId,
                    prNumber
            );
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        }
        if (headSha != null) {
            List<UUID> ids = jdbcTemplate.query(
                    """
                    SELECT work_item_id
                    FROM github_activities
                    WHERE repository_id = ? AND (pr_head_sha = ? OR commit_sha = ?) AND work_item_id IS NOT NULL
                    ORDER BY happened_at DESC
                    LIMIT 1
                    """,
                    (rs, rowNum) -> rs.getObject("work_item_id", UUID.class),
                    repositoryId,
                    headSha,
                    headSha
            );
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        }
        UUID resolved = resolveWorkItemId(projectId, branchText);
        if (resolved == null) {
            resolved = resolveWorkItemId(projectId, titleText);
        }
        return resolved;
    }

    private void upsertActivity(
            UUID projectId,
            UUID repositoryId,
            UUID workItemId,
            GitHubActivity.Kind kind,
            String githubObjectId,
            String commitSha,
            Integer prNumber,
            String prHeadSha,
            String actorLogin,
            String state,
            String title,
            Map<String, Object> payload,
            Instant happenedAt) {

        if (githubObjectId == null || githubObjectId.isBlank()) {
            return;
        }

        String payloadJson = toSafeJson(payload, "{}");

        String sql = """
                INSERT INTO github_activities (
                    id, project_id, repository_id, work_item_id, kind, github_object_id,
                    commit_sha, pr_number, pr_head_sha, actor_login, state, title,
                    payload, happened_at, synced_at, created_at, updated_at, version
                ) VALUES (
                    gen_random_uuid(), ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?::jsonb, ?, now(), now(), now(), 0
                )
                ON CONFLICT (repository_id, kind, github_object_id) DO UPDATE SET
                    work_item_id = COALESCE(EXCLUDED.work_item_id, github_activities.work_item_id),
                    commit_sha = COALESCE(EXCLUDED.commit_sha, github_activities.commit_sha),
                    pr_number = COALESCE(EXCLUDED.pr_number, github_activities.pr_number),
                    pr_head_sha = COALESCE(EXCLUDED.pr_head_sha, github_activities.pr_head_sha),
                    actor_login = COALESCE(EXCLUDED.actor_login, github_activities.actor_login),
                    state = EXCLUDED.state,
                    title = COALESCE(EXCLUDED.title, github_activities.title),
                    payload = EXCLUDED.payload,
                    happened_at = EXCLUDED.happened_at,
                    synced_at = now(),
                    updated_at = now(),
                    version = github_activities.version + 1
                """;

        jdbcTemplate.update(
                sql,
                projectId,
                repositoryId,
                workItemId,
                kind.name(),
                githubObjectId,
                commitSha,
                prNumber,
                prHeadSha,
                actorLogin,
                state != null ? state : "ACTIVE",
                title,
                payloadJson,
                Timestamp.from(happenedAt != null ? happenedAt : Instant.now())
        );
    }

    private String toSafeJson(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static CiRun.Status mapCiStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return CiRun.Status.QUEUED;
        }
        return switch (rawStatus.trim().toLowerCase(Locale.ROOT)) {
            case "completed" -> CiRun.Status.COMPLETED;
            case "in_progress" -> CiRun.Status.IN_PROGRESS;
            default -> CiRun.Status.QUEUED;
        };
    }

    private static CiRun.Conclusion mapCiConclusion(String rawConclusion) {
        if (rawConclusion == null || rawConclusion.isBlank()) {
            return null;
        }
        try {
            return CiRun.Conclusion.valueOf(rawConclusion.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static CodeReview.Status mapGitHubReviewState(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            return null;
        }
        return switch (rawState.trim().toLowerCase(Locale.ROOT)) {
            case "approved" -> CodeReview.Status.PASS;
            case "changes_requested" -> CodeReview.Status.REQUIRES_CHANGES;
            default -> null;
        };
    }

    private static String normalizeSha(String sha) {
        if (sha == null) {
            return null;
        }
        String trimmed = sha.trim().toLowerCase(Locale.ROOT);
        return SHA_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String extractNestedString(Map<?, ?> map, String outerKey, String innerKey) {
        if (map == null) {
            return null;
        }
        Object outer = map.get(outerKey);
        if (outer instanceof Map<?, ?> inner) {
            return asString(inner.get(innerKey));
        }
        return null;
    }

    private static Integer extractPositiveInt(Object value) {
        if (value instanceof Number n) {
            int i = n.intValue();
            return i > 0 ? i : null;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                int i = Integer.parseInt(s.trim());
                return i > 0 ? i : null;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static int extractPositiveIntOrDefault(Object value, int defaultVal) {
        Integer parsed = extractPositiveInt(value);
        return parsed != null ? parsed : defaultVal;
    }

    private static Long extractPositiveLong(Object value) {
        if (value instanceof Number n) {
            long l = n.longValue();
            return l > 0 ? l : null;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                long l = Long.parseLong(s.trim());
                return l > 0 ? l : null;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Integer extractFirstPullRequestNumber(Object prsObj) {
        if (prsObj instanceof List<?> prsList) {
            for (Object item : prsList) {
                if (item instanceof Map<?, ?> prMap) {
                    Integer num = extractPositiveInt(prMap.get("number"));
                    if (num != null) {
                        return num;
                    }
                }
            }
        }
        return null;
    }

    private static Instant parseInstantOrNull(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Instant.parse(str.trim());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Lists activities for a project ordered by happened_at DESC.
     * Supports filtering by workItemId and kind.
     * Scoped to active project members.
     */
    @Transactional(readOnly = true)
    public List<GitHubActivityResponse> listActivities(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            String kind
    ) {
        setActorId(principal);
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        StringBuilder sql = new StringBuilder("""
                SELECT id, project_id, repository_id, work_item_id, kind, github_object_id,
                       commit_sha, pr_number, pr_head_sha, actor_login, state, title,
                       payload, happened_at, synced_at, created_at, updated_at, version
                FROM github_activities
                WHERE project_id = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(projectId);

        if (workItemId != null) {
            sql.append(" AND work_item_id = ?");
            params.add(workItemId);
        }
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = ?");
            params.add(kind.trim().toUpperCase(Locale.ROOT));
        }

        sql.append(" ORDER BY happened_at DESC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            UUID pId = rs.getObject("project_id", UUID.class);
            UUID rId = rs.getObject("repository_id", UUID.class);
            UUID wId = rs.getObject("work_item_id", UUID.class);
            String k = rs.getString("kind");
            String gObjId = rs.getString("github_object_id");
            String cSha = rs.getString("commit_sha");
            Integer prNum = rs.getObject("pr_number", Integer.class);
            String prSha = rs.getString("pr_head_sha");
            String actor = rs.getString("actor_login");
            String st = rs.getString("state");
            String title = rs.getString("title");

            Map<String, Object> payloadMap = Collections.emptyMap();
            String payloadStr = rs.getString("payload");
            if (payloadStr != null && !payloadStr.isBlank()) {
                try {
                    payloadMap = objectMapper.readValue(payloadStr, new TypeReference<Map<String, Object>>() {});
                } catch (Exception ignored) {
                }
            }

            Timestamp hAtTs = rs.getTimestamp("happened_at");
            Instant hAt = hAtTs != null ? hAtTs.toInstant() : null;

            Timestamp sAtTs = rs.getTimestamp("synced_at");
            Instant sAt = sAtTs != null ? sAtTs.toInstant() : null;

            Timestamp cAtTs = rs.getTimestamp("created_at");
            Instant cAt = cAtTs != null ? cAtTs.toInstant() : null;

            Timestamp uAtTs = rs.getTimestamp("updated_at");
            Instant uAt = uAtTs != null ? uAtTs.toInstant() : null;

            Long version = rs.getLong("version");

            return new GitHubActivityResponse(
                    id, pId, rId, wId, k, gObjId, cSha, prNum, prSha, actor, st, title, payloadMap, hAt, sAt, cAt, uAt, version
            );
        }, params.toArray());
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
