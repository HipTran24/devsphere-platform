package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.domain.SessionTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "devsphere.github.webhook-secret=test-webhook-secret-key-9999"
})
@AutoConfigureMockMvc
public class GitHubActivitySyncIntegrationTest {

    private static final String WEBHOOK_SECRET = "test-webhook-secret-key-9999";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @MockitoBean private JavaMailSender mailSender;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SecureRandom random = new SecureRandom();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] sessionKey = new byte[32];
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        sessionKey[0] = 13;
        hmac[0] = 1;
        encryption[0] = 2;
        registry.add("devsphere.auth.session.signing-key", () -> Base64.getEncoder().encodeToString(sessionKey));
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
        registry.add("devsphere.github.webhook-secret", () -> WEBHOOK_SECRET);
    }

    @BeforeEach
    void cleanDatabase() {
        var tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class
        );
        jdbc.execute("TRUNCATE TABLE " + String.join(",", tables) + " CASCADE");
    }

    // --- Helper Methods ---

    private String computeHmacSignature(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body);
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }

    private UUID createUser(String email, String displayName, String status, String systemRole, boolean emailVerified) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime verifiedAt = emailVerified ? now : null;
        jdbc.update(
                "INSERT INTO users (id, email, display_name, password_hash, system_role, status, email_verified_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'argon2_dummy_hash', ?, ?, ?, ?, ?, 0)",
                id, email, displayName, systemRole, status, verifiedAt, now, now
        );
        return id;
    }

    private String issueAccessToken(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusDays(7);
        byte[] refreshHashBytes = new byte[32];
        random.nextBytes(refreshHashBytes);
        String refreshHash = Base64.getEncoder().encodeToString(refreshHashBytes);

        jdbc.update(
                "INSERT INTO auth_sessions (id, user_id, refresh_hash, issued_at, expires_at, session_family_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessionId, userId, refreshHash, now, expiresAt, familyId, now
        );

        return sessionTokens.issueAccess(userId, sessionId).value();
    }

    private UUID createWorkspace(UUID ownerId, String slug, String name) {
        return tx.execute(s -> {
            UUID workspaceId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO workspaces (id, slug, name, owner_id, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
                    workspaceId, slug, name, ownerId, now, now
            );
            jdbc.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'OWNER', 'ACTIVE', ?, ?, ?, 0)",
                    workspaceId, ownerId, now, now, now
            );
            return workspaceId;
        });
    }

    private void addWorkspaceMember(UUID workspaceId, UUID userId, String role) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                workspaceId, userId, role, now, now, now
        );
    }

    private UUID createProject(UUID workspaceId, String projectKey, String name, UUID pmUserId) {
        return tx.execute(s -> {
            UUID projectId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, sprint_length_days, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'Project description', 'Project objective', 'PLANNING', 'Asia/Ho_Chi_Minh', '2026-12-01', 14, ?, ?, 0)",
                    projectId, workspaceId, projectKey, name, now, now
            );

            if (pmUserId != null) {
                UUID memberId = UUID.randomUUID();
                jdbc.update(
                        "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                        memberId, projectId, workspaceId, pmUserId, now, now, now
                );
                jdbc.update(
                        "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                                "VALUES (?, ?, ?, 'PM', ?, ?, ?, ?)",
                        UUID.randomUUID(), projectId, memberId, pmUserId, now, pmUserId, now
                );
            }
            return projectId;
        });
    }

    private void addProjectMemberWithRole(UUID projectId, UUID workspaceId, UUID userId, String role, UUID grantedBy) {
        tx.executeWithoutResult(status -> {
            UUID memberId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                    memberId, projectId, workspaceId, userId, now, now, now
            );
            jdbc.update(
                    "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), projectId, memberId, role, grantedBy, now, userId, now
            );
        });
    }

    private UUID createWorkItemViaApi(UUID workspaceId, UUID projectId, String token, String title) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "type", "STORY",
                                "title", title,
                                "storyPoints", 3.0,
                                "originalEstimateMinutes", 120
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID connectRepositoryDirectly(UUID workspaceId, UUID projectId, Long githubRepoId, String fullName) {
        UUID repoId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO github_repositories (id, workspace_id, project_id, github_installation_id, github_repo_id, full_name, default_branch, private, active, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 55555, ?, ?, 'main', true, true, ?, ?, 0)",
                repoId, workspaceId, projectId, githubRepoId, fullName, now, now
        );
        return repoId;
    }

    // =========================================================================
    // 1. Activity Sync & Task Key Extraction: Push Events
    // =========================================================================

    @Nested
    @DisplayName("Push Event & Task Key Extraction Tests")
    class PushEventTests {

        @Test
        @DisplayName("Push event extracts task keys from branch and commits, linking to work items")
        void pushEventExtractsKeysFromBranchAndCommits() throws Exception {
            UUID ownerId = createUser("pm-push@example.test", "PM Push", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-push", "Workspace Push");
            UUID projectId = createProject(workspaceId, "ALPHA", "Alpha Project", ownerId);

            // Create 2 work items: item1 -> ALPHA-1, item2 -> ALPHA-2
            UUID item1Id = createWorkItemViaApi(workspaceId, projectId, pmToken, "Implement OAuth Login");
            UUID item2Id = createWorkItemViaApi(workspaceId, projectId, pmToken, "Implement Password Reset");

            String key1 = jdbc.queryForObject("SELECT item_key FROM work_items WHERE id = ?", String.class, item1Id);
            String key2 = jdbc.queryForObject("SELECT item_key FROM work_items WHERE id = ?", String.class, item2Id);
            assertThat(key1).isEqualTo("ALPHA-1");
            assertThat(key2).isEqualTo("ALPHA-2");

            Long repoIdLong = 10001L;
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, repoIdLong, "devsphere-org/alpha-repo");

            String commitSha1 = "1111111111111111111111111111111111111111";
            String commitSha2 = "2222222222222222222222222222222222222222";
            String commitSha3 = "3333333333333333333333333333333333333333";

            // Push payload: branch contains ALPHA-1; commit 1 has ALPHA-1, commit 2 has ALPHA-2, commit 3 has no key
            Map<String, Object> pushPayload = Map.of(
                    "ref", "refs/heads/feature/ALPHA-1-oauth-flow",
                    "after", commitSha1,
                    "repository", Map.of("id", repoIdLong, "full_name", "devsphere-org/alpha-repo"),
                    "sender", Map.of("login", "octocat"),
                    "commits", List.of(
                            Map.of(
                                    "id", commitSha1,
                                    "message", "ALPHA-1: Initial oauth login flow",
                                    "timestamp", "2026-11-23T01:00:00Z",
                                    "author", Map.of("name", "Octo Cat", "username", "octocat")
                            ),
                            Map.of(
                                    "id", commitSha2,
                                    "message", "ALPHA-2: Fix reset token expiration handling",
                                    "timestamp", "2026-11-23T01:05:00Z",
                                    "author", Map.of("name", "Octo Cat", "username", "octocat")
                            ),
                            Map.of(
                                    "id", commitSha3,
                                    "message", "docs: update contributor guidelines",
                                    "timestamp", "2026-11-23T01:10:00Z",
                                    "author", Map.of("name", "Octo Cat", "username", "octocat")
                            )
                    )
            );

            byte[] bodyBytes = json.writeValueAsBytes(pushPayload);
            String signature = computeHmacSignature(bodyBytes);
            String deliveryId = UUID.randomUUID().toString();

            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "push")
                            .header("X-GitHub-Delivery", deliveryId)
                            .header("X-Hub-Signature-256", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());

            // 1. Check BRANCH activity linked to ALPHA-1
            List<Map<String, Object>> branchActivities = jdbc.queryForList(
                    "SELECT * FROM github_activities WHERE repository_id = ? AND kind = 'BRANCH'",
                    repoId
            );
            assertThat(branchActivities).hasSize(1);
            assertThat(branchActivities.get(0).get("work_item_id")).isEqualTo(item1Id);
            assertThat(branchActivities.get(0).get("commit_sha")).isEqualTo(commitSha1);
            assertThat(branchActivities.get(0).get("actor_login")).isEqualTo("octocat");

            // 2. Check COMMIT activity for commitSha1 linked to ALPHA-1
            Map<String, Object> commit1Act = jdbc.queryForMap(
                    "SELECT * FROM github_activities WHERE repository_id = ? AND kind = 'COMMIT' AND github_object_id = ?",
                    repoId, commitSha1
            );
            assertThat(commit1Act.get("work_item_id")).isEqualTo(item1Id);
            assertThat(commit1Act.get("title").toString()).contains("ALPHA-1: Initial oauth login flow");

            // 3. Check COMMIT activity for commitSha2 linked to ALPHA-2
            Map<String, Object> commit2Act = jdbc.queryForMap(
                    "SELECT * FROM github_activities WHERE repository_id = ? AND kind = 'COMMIT' AND github_object_id = ?",
                    repoId, commitSha2
            );
            assertThat(commit2Act.get("work_item_id")).isEqualTo(item2Id);
            assertThat(commit2Act.get("title").toString()).contains("ALPHA-2: Fix reset token expiration");

            // 4. Check COMMIT activity for commitSha3: work_item_id is NULL (no task key)
            Map<String, Object> commit3Act = jdbc.queryForMap(
                    "SELECT * FROM github_activities WHERE repository_id = ? AND kind = 'COMMIT' AND github_object_id = ?",
                    repoId, commitSha3
            );
            assertThat(commit3Act.get("work_item_id")).isNull();
            assertThat(commit3Act.get("title").toString()).contains("docs: update contributor guidelines");
        }
    }

    // =========================================================================
    // 2. Pull Request Event & Task Mapping Tests
    // =========================================================================

    @Nested
    @DisplayName("Pull Request Event & Task Mapping Tests")
    class PullRequestEventTests {

        @Test
        @DisplayName("PR opened event extracts task key from title and creates linked PULL_REQUEST activity")
        void prOpenedCreatesLinkedActivity() throws Exception {
            UUID ownerId = createUser("pm-pr@example.test", "PM PR", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-pr", "Workspace PR");
            UUID projectId = createProject(workspaceId, "BETA", "Beta Project", ownerId);

            UUID itemId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Implement Feature Beta");
            String key = jdbc.queryForObject("SELECT item_key FROM work_items WHERE id = ?", String.class, itemId);
            assertThat(key).isEqualTo("BETA-1");

            Long repoIdLong = 10002L;
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, repoIdLong, "devsphere-org/beta-repo");

            String prHeadSha = "4444444444444444444444444444444444444444";
            Map<String, Object> prPayload = Map.of(
                    "action", "opened",
                    "number", 55,
                    "pull_request", Map.of(
                            "number", 55,
                            "id", 998877,
                            "state", "open",
                            "title", "BETA-1: Implement Feature Beta complete overhaul",
                            "body", "Detailed description for BETA-1",
                            "merged", false,
                            "user", Map.of("login", "pr-contributor"),
                            "head", Map.of("ref", "feature/BETA-1-overhaul", "sha", prHeadSha),
                            "base", Map.of("ref", "main")
                    ),
                    "repository", Map.of("id", repoIdLong, "full_name", "devsphere-org/beta-repo"),
                    "sender", Map.of("login", "pr-contributor")
            );

            byte[] bodyBytes = json.writeValueAsBytes(prPayload);
            String signature = computeHmacSignature(bodyBytes);
            String deliveryId = UUID.randomUUID().toString();

            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "pull_request")
                            .header("X-GitHub-Delivery", deliveryId)
                            .header("X-Hub-Signature-256", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());

            Map<String, Object> prActivity = jdbc.queryForMap(
                    "SELECT * FROM github_activities WHERE repository_id = ? AND kind = 'PULL_REQUEST' AND pr_number = 55",
                    repoId
            );
            assertThat(prActivity.get("work_item_id")).isEqualTo(itemId);
            assertThat(prActivity.get("state")).isEqualTo("OPEN");
            assertThat(prActivity.get("pr_head_sha")).isEqualTo(prHeadSha);
            assertThat(prActivity.get("actor_login")).isEqualTo("pr-contributor");
            assertThat(prActivity.get("title").toString()).contains("BETA-1: Implement Feature Beta");
        }

        @Test
        @DisplayName("PR synchronize event updates pr_head_sha on existing activity")
        void prSynchronizeUpdatesHeadSha() throws Exception {
            UUID ownerId = createUser("pm-sync@example.test", "PM Sync", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-sync", "Workspace Sync");
            UUID projectId = createProject(workspaceId, "GAMMA", "Gamma Project", ownerId);

            UUID itemId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Implement Gamma Fix");
            Long repoIdLong = 10003L;
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, repoIdLong, "devsphere-org/gamma-repo");

            String initialSha = "5555555555555555555555555555555555555555";
            String updatedSha = "6666666666666666666666666666666666666666";

            // 1. Open PR
            Map<String, Object> openPayload = Map.of(
                    "action", "opened",
                    "number", 77,
                    "pull_request", Map.of(
                            "number", 77,
                            "id", 123456,
                            "state", "open",
                            "title", "GAMMA-1: Fix gamma pipeline",
                            "merged", false,
                            "user", Map.of("login", "gamma-dev"),
                            "head", Map.of("ref", "feature/GAMMA-1", "sha", initialSha),
                            "base", Map.of("ref", "main")
                    ),
                    "repository", Map.of("id", repoIdLong, "full_name", "devsphere-org/gamma-repo"),
                    "sender", Map.of("login", "gamma-dev")
            );
            byte[] openBytes = json.writeValueAsBytes(openPayload);
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "pull_request")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .header("X-Hub-Signature-256", computeHmacSignature(openBytes))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(openBytes))
                    .andExpect(status().isOk());

            // 2. Synchronize PR with updated commit SHA
            Map<String, Object> syncPayload = Map.of(
                    "action", "synchronize",
                    "number", 77,
                    "pull_request", Map.of(
                            "number", 77,
                            "id", 123456,
                            "state", "open",
                            "title", "GAMMA-1: Fix gamma pipeline",
                            "merged", false,
                            "user", Map.of("login", "gamma-dev"),
                            "head", Map.of("ref", "feature/GAMMA-1", "sha", updatedSha),
                            "base", Map.of("ref", "main")
                    ),
                    "repository", Map.of("id", repoIdLong, "full_name", "devsphere-org/gamma-repo"),
                    "sender", Map.of("login", "gamma-dev")
            );
            byte[] syncBytes = json.writeValueAsBytes(syncPayload);
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "pull_request")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .header("X-Hub-Signature-256", computeHmacSignature(syncBytes))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(syncBytes))
                    .andExpect(status().isOk());

            // Check head SHA is updated
            Map<String, Object> prActivity = jdbc.queryForMap(
                    "SELECT * FROM github_activities WHERE repository_id = ? AND kind = 'PULL_REQUEST' AND pr_number = 77",
                    repoId
            );
            assertThat(prActivity.get("pr_head_sha")).isEqualTo(updatedSha);
        }
    }

    // =========================================================================
    // 3. Part H Invariant: PR Merge Does NOT Auto-Complete Tasks
    // =========================================================================

    @Nested
    @DisplayName("Part H Invariant: PR Merge Does NOT Auto-Complete Tasks")
    class PartHInvariantTests {

        @Test
        @DisplayName("PR merged event records MERGED activity state but leaves work item status UNCHANGED (never auto-DONE)")
        void prMergeDoesNotAutoCompleteTask() throws Exception {
            UUID ownerId = createUser("pm-parth@example.test", "PM PartH", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-parth@example.test", "Dev PartH", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devId);

            UUID workspaceId = createWorkspace(ownerId, "ws-parth", "Workspace PartH");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "DELTA", "Delta Project", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            // Create work item DELTA-1
            UUID itemId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Critical Delta Feature");
            // Assign to developer and advance to IN_PROGRESS
            jdbc.update("UPDATE work_items SET assignee_id = ? WHERE id = ?", devId, itemId);
            Long v0 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, itemId);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + itemId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", v0))))
                    .andExpect(status().isOk());

            String statusBeforeMerge = jdbc.queryForObject("SELECT status FROM work_items WHERE id = ?", String.class, itemId);
            assertThat(statusBeforeMerge).isEqualTo("IN_PROGRESS");

            Long repoIdLong = 10004L;
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, repoIdLong, "devsphere-org/delta-repo");

            String mergeSha = "7777777777777777777777777777777777777777";
            Map<String, Object> mergePayload = Map.of(
                    "action", "closed",
                    "number", 88,
                    "pull_request", Map.of(
                            "number", 88,
                            "id", 778899,
                            "state", "closed",
                            "title", "DELTA-1: Feature completion PR",
                            "merged", true,
                            "merged_at", "2026-11-23T01:30:00Z",
                            "user", Map.of("login", "octocat"),
                            "head", Map.of("ref", "feature/DELTA-1", "sha", mergeSha),
                            "base", Map.of("ref", "main")
                    ),
                    "repository", Map.of("id", repoIdLong, "full_name", "devsphere-org/delta-repo"),
                    "sender", Map.of("login", "octocat")
            );

            byte[] bodyBytes = json.writeValueAsBytes(mergePayload);
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "pull_request")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .header("X-Hub-Signature-256", computeHmacSignature(bodyBytes))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());

            // 1. Activity state recorded as MERGED
            Map<String, Object> prActivity = jdbc.queryForMap(
                    "SELECT * FROM github_activities WHERE repository_id = ? AND kind = 'PULL_REQUEST' AND pr_number = 88",
                    repoId
            );
            assertThat(prActivity.get("state")).isEqualTo("MERGED");
            assertThat(prActivity.get("work_item_id")).isEqualTo(itemId);

            // 2. CRITICAL PART H INVARIANT: Work item status MUST REMAIN IN_PROGRESS and NOT DONE!
            String statusAfterMerge = jdbc.queryForObject("SELECT status FROM work_items WHERE id = ?", String.class, itemId);
            assertThat(statusAfterMerge)
                    .as("Part H Invariant: PR merge MUST NOT automatically mark task as DONE; PM acceptance is mandatory")
                    .isEqualTo("IN_PROGRESS");
            assertThat(statusAfterMerge).isNotEqualTo("DONE");
        }
    }

    // =========================================================================
    // 4. Idempotent Replay Handling Tests
    // =========================================================================

    @Nested
    @DisplayName("Idempotent Replay Handling Tests")
    class IdempotentReplayTests {

        @Test
        @DisplayName("Re-delivering webhook event updates activity row without unique constraint violation")
        void duplicateDeliveryUpdatesActivityGracefully() throws Exception {
            UUID ownerId = createUser("pm-replay@example.test", "PM Replay", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-replay", "Workspace Replay");
            UUID projectId = createProject(workspaceId, "EPSILON", "Epsilon Project", ownerId);

            UUID itemId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Replay Invariant Task");
            Long repoIdLong = 10005L;
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, repoIdLong, "devsphere-org/epsilon-repo");

            String commitSha = "8888888888888888888888888888888888888888";
            Map<String, Object> pushPayload = Map.of(
                    "ref", "refs/heads/feature/EPSILON-1-test",
                    "after", commitSha,
                    "repository", Map.of("id", repoIdLong, "full_name", "devsphere-org/epsilon-repo"),
                    "sender", Map.of("login", "octocat"),
                    "commits", List.of(
                            Map.of(
                                    "id", commitSha,
                                    "message", "EPSILON-1: idempotent commit replay test",
                                    "timestamp", "2026-11-23T02:00:00Z",
                                    "author", Map.of("name", "Octo Cat", "username", "octocat")
                            )
                    )
            );

            byte[] bodyBytes = json.writeValueAsBytes(pushPayload);
            String signature = computeHmacSignature(bodyBytes);
            String delivery1 = UUID.randomUUID().toString();
            String delivery2 = UUID.randomUUID().toString();

            // 1st delivery
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "push")
                            .header("X-GitHub-Delivery", delivery1)
                            .header("X-Hub-Signature-256", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());

            int activitiesAfterFirst = jdbc.queryForObject(
                    "SELECT count(*) FROM github_activities WHERE repository_id = ?",
                    Integer.class, repoId
            );

            // 2nd delivery with new delivery ID (replay of identical git activity)
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "push")
                            .header("X-GitHub-Delivery", delivery2)
                            .header("X-Hub-Signature-256", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());

            int activitiesAfterSecond = jdbc.queryForObject(
                    "SELECT count(*) FROM github_activities WHERE repository_id = ?",
                    Integer.class, repoId
            );

            // Unique constraint on (repository_id, kind, github_object_id) is NOT violated; activity count remains equal
            assertThat(activitiesAfterSecond).isEqualTo(activitiesAfterFirst);

            // Also redelivering with identical deliveryId is handled gracefully (idempotent ignore)
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "push")
                            .header("X-GitHub-Delivery", delivery1)
                            .header("X-Hub-Signature-256", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());
        }
    }

    // =========================================================================
    // 5. Activity Listing Query Tests
    // =========================================================================

    @Nested
    @DisplayName("Activity Listing Query Tests")
    class ActivityListingQueryTests {

        @Test
        @DisplayName("GET /activities returns activities ordered by happened_at DESC and supports workItemId and kind filters")
        void queryActivitiesOrderedAndFiltered() throws Exception {
            UUID ownerId = createUser("pm-query@example.test", "PM Query", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID otherUserId = createUser("other-query@example.test", "Other Query", "ACTIVE", "USER", true);
            String otherToken = issueAccessToken(otherUserId);

            UUID workspaceId = createWorkspace(ownerId, "ws-query", "Workspace Query");
            UUID projectId = createProject(workspaceId, "ZETA", "Zeta Project", ownerId);

            UUID item1 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Item Zeta 1");
            UUID item2 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Item Zeta 2");

            Long repoIdLong = 10006L;
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, repoIdLong, "devsphere-org/zeta-repo");

            OffsetDateTime t1 = OffsetDateTime.parse("2026-11-23T03:00:00Z");
            OffsetDateTime t2 = OffsetDateTime.parse("2026-11-23T03:10:00Z");
            OffsetDateTime t3 = OffsetDateTime.parse("2026-11-23T03:20:00Z");

            // Insert 3 activities directly into DB:
            // a1: BRANCH for item1 at t1
            jdbc.update(
                    "INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, state, title, happened_at, synced_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'BRANCH', 'refs/heads/feature/ZETA-1', 'ACTIVE', 'feature/ZETA-1', ?, now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, item1, t1
            );

            // a2: COMMIT for item1 at t2
            jdbc.update(
                    "INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, commit_sha, state, title, happened_at, synced_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'COMMIT', '9999999999999999999999999999999999999999', '9999999999999999999999999999999999999999', 'COMMITTED', 'ZETA-1: commit msg', ?, now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, item1, t2
            );

            // a3: PULL_REQUEST for item2 at t3
            jdbc.update(
                    "INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, pr_number, state, title, happened_at, synced_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'PULL_REQUEST', '99', 99, 'OPEN', 'ZETA-2: PR title', ?, now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, item2, t3
            );

            // 1. Query all activities: ordered by happened_at DESC (t3, t2, t1)
            MvcResult resAll = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/activities")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode allNode = json.readTree(resAll.getResponse().getContentAsString());
            assertThat(allNode.isArray()).isTrue();
            assertThat(allNode).hasSize(3);
            assertThat(allNode.get(0).get("kind").asText()).isEqualTo("PULL_REQUEST"); // t3
            assertThat(allNode.get(1).get("kind").asText()).isEqualTo("COMMIT");       // t2
            assertThat(allNode.get(2).get("kind").asText()).isEqualTo("BRANCH");       // t1

            // 2. Filter by workItemId=item1 -> returns 2 activities (t2, t1)
            MvcResult resItem1 = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/activities")
                            .param("workItemId", item1.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode item1Node = json.readTree(resItem1.getResponse().getContentAsString());
            assertThat(item1Node).hasSize(2);
            assertThat(item1Node.get(0).get("kind").asText()).isEqualTo("COMMIT");
            assertThat(item1Node.get(1).get("kind").asText()).isEqualTo("BRANCH");

            // 3. Filter by kind=PULL_REQUEST -> returns 1 activity (t3)
            MvcResult resKind = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/activities")
                            .param("kind", "PULL_REQUEST")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode kindNode = json.readTree(resKind.getResponse().getContentAsString());
            assertThat(kindNode).hasSize(1);
            assertThat(kindNode.get(0).get("kind").asText()).isEqualTo("PULL_REQUEST");
            assertThat(kindNode.get(0).get("prNumber").asInt()).isEqualTo(99);

            // 4. Access Control: User without project membership receives 403 Forbidden
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/activities")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                    .andExpect(status().isForbidden());
        }
    }
}
