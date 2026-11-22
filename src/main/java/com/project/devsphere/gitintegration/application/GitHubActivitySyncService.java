package com.project.devsphere.gitintegration.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.gitintegration.api.dto.GitHubActivityResponse;
import com.project.devsphere.gitintegration.domain.GitHubActivity;
import com.project.devsphere.gitintegration.domain.GitHubEvent;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
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
     * Synchronously processes a GitHub event and maps activity rows.
     * Handles PUSH and PULL_REQUEST event types idempotently.
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
        }

        jdbcTemplate.update(
                "UPDATE github_events SET process_status = 'DONE', processed_at = now(), updated_at = now() WHERE id = ?",
                event.getId()
        );
    }

    @SuppressWarnings("unchecked")
    private void processPushEvent(UUID projectId, UUID repositoryId, Map<String, Object> payload, Instant receivedAt) {
        String ref = (String) payload.get("ref");
        String afterSha = (String) payload.get("after");
        String cleanAfterSha = isValidSha(afterSha) ? afterSha.toLowerCase(Locale.ROOT) : null;

        String senderLogin = null;
        Object senderObj = payload.get("sender");
        if (senderObj instanceof Map<?, ?> senderMap) {
            senderLogin = (String) senderMap.get("login");
        }

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
                    String commitId = (String) commitMap.get("id");
                    if (commitId == null || !isValidSha(commitId)) {
                        continue;
                    }
                    String cleanCommitSha = commitId.toLowerCase(Locale.ROOT);
                    String message = (String) commitMap.get("message");
                    UUID commitWorkItemId = resolveWorkItemId(projectId, message);

                    String commitAuthorLogin = null;
                    Object authorObj = commitMap.get("author");
                    if (authorObj instanceof Map<?, ?> authorMap) {
                        commitAuthorLogin = (String) authorMap.get("username");
                        if (commitAuthorLogin == null || commitAuthorLogin.isBlank()) {
                            commitAuthorLogin = (String) authorMap.get("name");
                        }
                    }
                    if (commitAuthorLogin == null || commitAuthorLogin.isBlank()) {
                        commitAuthorLogin = senderLogin;
                    }

                    Instant commitTimestamp = receivedAt;
                    Object tsObj = commitMap.get("timestamp");
                    if (tsObj instanceof String tsStr && !tsStr.isBlank()) {
                        try {
                            commitTimestamp = Instant.parse(tsStr);
                        } catch (Exception ignored) {
                        }
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

    private void processPullRequestEvent(UUID projectId, UUID repositoryId, Map<String, Object> payload, Instant receivedAt) {
        Object prObj = payload.get("pull_request");
        if (!(prObj instanceof Map<?, ?> prMap)) {
            return;
        }

        Integer prNumber = null;
        Object numObj = prMap.get("number");
        if (numObj instanceof Number n) {
            prNumber = n.intValue();
        } else if (payload.get("number") instanceof Number n) {
            prNumber = n.intValue();
        }

        if (prNumber == null || prNumber <= 0) {
            return;
        }

        String title = (String) prMap.get("title");
        String body = (String) prMap.get("body");
        Boolean merged = Boolean.TRUE.equals(prMap.get("merged"));
        String rawState = (String) prMap.get("state");

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
            headSha = (String) headMap.get("sha");
            headRef = (String) headMap.get("ref");
        }
        String cleanHeadSha = isValidSha(headSha) ? headSha.toLowerCase(Locale.ROOT) : null;

        String actorLogin = null;
        Object userObj = prMap.get("user");
        if (userObj instanceof Map<?, ?> userMap) {
            actorLogin = (String) userMap.get("login");
        }
        if (actorLogin == null || actorLogin.isBlank()) {
            Object senderObj = payload.get("sender");
            if (senderObj instanceof Map<?, ?> senderMap) {
                actorLogin = (String) senderMap.get("login");
            }
        }

        // Resolve work item: check title, then head branch ref, then body
        UUID prWorkItemId = resolveWorkItemId(projectId, title);
        if (prWorkItemId == null && headRef != null) {
            prWorkItemId = resolveWorkItemId(projectId, headRef);
        }
        if (prWorkItemId == null && body != null) {
            prWorkItemId = resolveWorkItemId(projectId, body);
        }

        Instant prHappenedAt = receivedAt;
        Object mergedAtObj = prMap.get("merged_at");
        if (mergedAtObj instanceof String mStr && !mStr.isBlank()) {
            try {
                prHappenedAt = Instant.parse(mStr);
            } catch (Exception ignored) {
            }
        } else {
            Object updatedAtObj = prMap.get("updated_at");
            if (updatedAtObj instanceof String uStr && !uStr.isBlank()) {
                try {
                    prHappenedAt = Instant.parse(uStr);
                } catch (Exception ignored) {
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> castedPrMap = (Map<String, Object>) prMap;

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
                castedPrMap,
                prHappenedAt
        );

        // NOTE: Part H Invariant (FC-04.03):
        // Pull Request merge does NOT complete or advance work item status to DONE.
        // Task lifecycle status remains strictly controlled by PM acceptance.
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

        String payloadJson = "{}";
        if (payload != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (Exception ignored) {
                payloadJson = "{}";
            }
        }

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

    private boolean isValidSha(String sha) {
        return sha != null && SHA_PATTERN.matcher(sha.toLowerCase(Locale.ROOT)).matches();
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
                jdbcTemplate.execute("SELECT set_config('devsphere.actor_id', '" + principal.userId() + "', true)");
            } catch (Exception ignored) {
            }
        }
    }
}
