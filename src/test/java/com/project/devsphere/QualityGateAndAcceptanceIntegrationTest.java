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

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
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
        "devsphere.auth.mail.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
public class QualityGateAndAcceptanceIntegrationTest {

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

    private UUID createWorkItemViaApi(UUID workspaceId, UUID projectId, String token, String title, String workKind) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "type", "TASK",
                                "title", title,
                                "storyPoints", 3.0,
                                "originalEstimateMinutes", 120
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID itemId = UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());
        if (workKind != null) {
            jdbc.update("UPDATE work_items SET work_kind = ? WHERE id = ?", workKind, itemId);
        }
        return itemId;
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

    private void linkGitHubOAuthAccount(UUID userId, String providerLogin) {
        jdbc.update(
                "INSERT INTO oauth_accounts (id, user_id, provider, provider_subject, provider_login, linked_at, created_at) " +
                        "VALUES (?, ?, 'GITHUB', ?, ?, now(), now())",
                UUID.randomUUID(), userId, "sub-" + providerLogin, providerLogin
        );
    }

    private void transitionToInReview(UUID workspaceId, UUID projectId, UUID workItemId, String devToken, UUID devId) throws Exception {
        jdbc.update("UPDATE work_items SET assignee_id = ? WHERE id = ?", devId, workItemId);
        Long v0 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, workItemId);
        mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + workItemId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", v0))))
                .andExpect(status().isOk());

        Long v1 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, workItemId);
        mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + workItemId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", "IN_REVIEW", "expectedVersion", v1))))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // 1. Quality Gate Evaluation Tests
    // =========================================================================

    @Nested
    @DisplayName("Quality Gate Evaluation Tests")
    class QualityGateEvaluationTests {

        @Test
        @DisplayName("CODE task without linked PR returns PENDING_EVIDENCE")
        void codeTaskWithoutPrReturnsPendingEvidence() throws Exception {
            UUID ownerId = createUser("pm-eval1@example.test", "PM Eval1", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-eval1", "Workspace Eval1");
            UUID projectId = createProject(workspaceId, "QG1", "Project QG1", ownerId);

            UUID taskId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Code Task Without PR", "CODE");

            MvcResult res = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/quality-gate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode node = json.readTree(res.getResponse().getContentAsString());
            assertThat(node.get("status").asText()).isIn("PENDING_EVIDENCE", "FAILED");
            assertThat(node.get("passed").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("CODE task with linked PR and failed CI returns FAILED")
        void codeTaskWithFailedCiReturnsFailed() throws Exception {
            UUID ownerId = createUser("pm-eval2@example.test", "PM Eval2", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-eval2", "Workspace Eval2");
            UUID projectId = createProject(workspaceId, "QG2", "Project QG2", ownerId);

            UUID taskId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Code Task Failed CI", "CODE");
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20002L, "devsphere-org/repo-qg2");

            String headSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

            // 1. PR activity linked to task
            jdbc.update(
                    "INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, pr_number, pr_head_sha, actor_login, state, title, happened_at, synced_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'PULL_REQUEST', '201', 201, ?, 'author-dev', 'OPEN', 'PR Title', now(), now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha
            );

            // 2. CI run on headSha with FAILURE conclusion
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO ci_runs (id, project_id, repository_id, work_item_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, started_at, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 901, 1, ?, 201, 'COMPLETED', 'FAILURE', ?, ?, ?, ?, 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha, now.minusMinutes(10), now, now, now
            );

            MvcResult res = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/quality-gate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode node = json.readTree(res.getResponse().getContentAsString());
            assertThat(node.get("status").asText()).isEqualTo("FAILED");
            assertThat(node.get("passed").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("CODE task with linked PR, successful CI, but missing human review returns PENDING_EVIDENCE")
        void codeTaskWithMissingReviewReturnsPendingEvidence() throws Exception {
            UUID ownerId = createUser("pm-eval3@example.test", "PM Eval3", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-eval3", "Workspace Eval3");
            UUID projectId = createProject(workspaceId, "QG3", "Project QG3", ownerId);

            UUID taskId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Code Task Missing Review", "CODE");
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20003L, "devsphere-org/repo-qg3");

            String headSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

            // PR activity
            jdbc.update(
                    "INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, pr_number, pr_head_sha, actor_login, state, title, happened_at, synced_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'PULL_REQUEST', '202', 202, ?, 'author-dev', 'OPEN', 'PR Title 2', now(), now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha
            );

            // Successful CI run
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO ci_runs (id, project_id, repository_id, work_item_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, started_at, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 902, 1, ?, 202, 'COMPLETED', 'SUCCESS', ?, ?, ?, ?, 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha, now.minusMinutes(10), now, now, now
            );

            MvcResult res = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/quality-gate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode node = json.readTree(res.getResponse().getContentAsString());
            assertThat(node.get("status").asText()).isIn("PENDING_EVIDENCE", "FAILED");
            assertThat(node.get("passed").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("CODE task with linked PR, successful CI, and review FAIL returns FAILED")
        void codeTaskWithFailedReviewReturnsFailed() throws Exception {
            UUID ownerId = createUser("pm-eval4@example.test", "PM Eval4", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID reviewerId = createUser("reviewer-eval4@example.test", "Reviewer Eval4", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "ws-eval4", "Workspace Eval4");
            addWorkspaceMember(workspaceId, reviewerId, "MEMBER");

            UUID projectId = createProject(workspaceId, "QG4", "Project QG4", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, reviewerId, "DEVELOPER", ownerId);
            linkGitHubOAuthAccount(reviewerId, "reviewer-github-login");

            UUID taskId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Code Task Failed Review", "CODE");
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20004L, "devsphere-org/repo-qg4");

            String headSha = "cccccccccccccccccccccccccccccccccccccccc";

            // PR activity (author is 'different-author')
            jdbc.update(
                    "INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, pr_number, pr_head_sha, actor_login, state, title, happened_at, synced_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'PULL_REQUEST', '203', 203, ?, 'different-author', 'OPEN', 'PR Title 3', now(), now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha
            );

            // Successful CI run
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO ci_runs (id, project_id, repository_id, work_item_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, started_at, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 903, 1, ?, 203, 'COMPLETED', 'SUCCESS', ?, ?, ?, ?, 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha, now.minusMinutes(10), now, now, now
            );

            // Review with FAIL status
            Long projectVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
            jdbc.update(
                    "INSERT INTO code_reviews (id, project_id, repository_id, work_item_id, pr_number, head_sha, context_version, scope, reviewer_kind, reviewer_id, status, summary, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 203, ?, ?, 'PR', 'HUMAN', ?, 'FAIL', 'Found critical issues', now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha, projectVersion, reviewerId
            );

            MvcResult res = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/quality-gate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode node = json.readTree(res.getResponse().getContentAsString());
            assertThat(node.get("status").asText()).isEqualTo("FAILED");
            assertThat(node.get("passed").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("CODE task with CI SUCCESS, independent review PASS on current HEAD SHA, and verified criteria returns PASSED")
        void codeTaskWithCompleteEvidenceReturnsPassed() throws Exception {
            UUID ownerId = createUser("pm-eval5@example.test", "PM Eval5", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID reviewerId = createUser("reviewer-eval5@example.test", "Reviewer Eval5", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "ws-eval5", "Workspace Eval5");
            addWorkspaceMember(workspaceId, reviewerId, "MEMBER");

            UUID projectId = createProject(workspaceId, "QG5", "Project QG5", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, reviewerId, "DEVELOPER", ownerId);
            linkGitHubOAuthAccount(reviewerId, "independent-reviewer-5");

            UUID taskId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Complete Evidence Task", "CODE");
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20005L, "devsphere-org/repo-qg5");

            String headSha = "dddddddddddddddddddddddddddddddddddddddd";

            // PR activity
            jdbc.update(
                    "INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, pr_number, pr_head_sha, actor_login, state, title, happened_at, synced_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'PULL_REQUEST', '204', 204, ?, 'original-pr-author', 'OPEN', 'Complete Evidence PR', now(), now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha
            );

            // CI SUCCESS on headSha
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO ci_runs (id, project_id, repository_id, work_item_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, started_at, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 904, 1, ?, 204, 'COMPLETED', 'SUCCESS', ?, ?, ?, ?, 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha, now.minusMinutes(10), now, now, now
            );

            // Acceptance criteria all VERIFIED
            jdbc.update(
                    "INSERT INTO acceptance_criteria (id, project_id, work_item_id, position, description, evidence_kind, weight, status, verified_by, verified_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 1, 'Unit test passes', 'MANUAL', 1.0, 'VERIFIED', ?, now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, taskId, ownerId
            );

            // Independent Review PASS on headSha
            Long projectVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
            jdbc.update(
                    "INSERT INTO code_reviews (id, project_id, repository_id, work_item_id, pr_number, head_sha, context_version, scope, reviewer_kind, reviewer_id, status, summary, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 204, ?, ?, 'PR', 'HUMAN', ?, 'PASS', 'Clean implementation LGTM', now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha, projectVersion, reviewerId
            );

            MvcResult res = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/quality-gate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode node = json.readTree(res.getResponse().getContentAsString());
            assertThat(node.get("status").asText()).isEqualTo("PASSED");
            assertThat(node.get("passed").asBoolean()).isTrue();
        }
    }

    // =========================================================================
    // 2. Evidence-Gated Task Acceptance Tests (Part H3)
    // =========================================================================

    @Nested
    @DisplayName("Evidence-Gated Task Acceptance Tests (Part H3)")
    class EvidenceGatedAcceptanceTests {

        @Test
        @DisplayName("PM accept on CODE task in IN_REVIEW without passing Quality Gate is strictly rejected with 422 QUALITY_GATE_EVIDENCE_MISSING")
        void acceptWithoutPassingQualityGateRejected() throws Exception {
            UUID ownerId = createUser("pm-gate1@example.test", "PM Gate1", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-gate1@example.test", "Dev Gate1", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devId);

            UUID workspaceId = createWorkspace(ownerId, "ws-gate1", "Workspace Gate1");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "GT1", "Project GT1", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            UUID taskId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Incomplete Evidence Task", "CODE");

            // Advance task to IN_REVIEW
            transitionToInReview(workspaceId, projectId, taskId, devToken, devId);
            String statusBefore = jdbc.queryForObject("SELECT status FROM work_items WHERE id = ?", String.class, taskId);
            assertThat(statusBefore).isEqualTo("IN_REVIEW");

            // PM attempts to accept: Quality Gate has no CI or review -> must fail with 422
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("QUALITY_GATE_EVIDENCE_MISSING"));

            // Task remains in IN_REVIEW, NOT DONE
            String statusAfter = jdbc.queryForObject("SELECT status FROM work_items WHERE id = ?", String.class, taskId);
            assertThat(statusAfter).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("PM accept on CODE task in IN_REVIEW with PASSED Quality Gate succeeds (200 OK) and transitions to DONE")
        void acceptWithPassedQualityGateSucceeds() throws Exception {
            UUID ownerId = createUser("pm-gate2@example.test", "PM Gate2", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-gate2@example.test", "Dev Gate2", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devId);
            UUID reviewerId = createUser("reviewer-gate2@example.test", "Reviewer Gate2", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "ws-gate2", "Workspace Gate2");
            addWorkspaceMember(workspaceId, devId, "MEMBER");
            addWorkspaceMember(workspaceId, reviewerId, "MEMBER");

            UUID projectId = createProject(workspaceId, "GT2", "Project GT2", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, reviewerId, "DEVELOPER", ownerId);
            linkGitHubOAuthAccount(reviewerId, "gate2-reviewer-gh");

            UUID taskId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Passing Gate Task", "CODE");
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 30002L, "devsphere-org/repo-gt2");

            // Advance task to IN_REVIEW
            transitionToInReview(workspaceId, projectId, taskId, devToken, devId);

            String headSha = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

            // PR activity
            jdbc.update(
                    "INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, pr_number, pr_head_sha, actor_login, state, title, happened_at, synced_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'PULL_REQUEST', '302', 302, ?, 'dev-author-login', 'OPEN', 'PR GT2', now(), now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha
            );

            // CI run SUCCESS
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO ci_runs (id, project_id, repository_id, work_item_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, started_at, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 910, 1, ?, 302, 'COMPLETED', 'SUCCESS', ?, ?, ?, ?, 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha, now.minusMinutes(10), now, now, now
            );

            // Criteria verified
            jdbc.update(
                    "INSERT INTO acceptance_criteria (id, project_id, work_item_id, position, description, evidence_kind, weight, status, verified_by, verified_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 1, 'Integration tests pass', 'MANUAL', 1.0, 'VERIFIED', ?, now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, taskId, ownerId
            );

            // Independent review PASS
            Long projectVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
            jdbc.update(
                    "INSERT INTO code_reviews (id, project_id, repository_id, work_item_id, pr_number, head_sha, context_version, scope, reviewer_kind, reviewer_id, status, summary, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 302, ?, ?, 'PR', 'HUMAN', ?, 'PASS', 'Ship it', now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, taskId, headSha, projectVersion, reviewerId
            );

            // PM accepts task
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andExpect(jsonPath("$.acceptedBy").value(ownerId.toString()));

            String statusAfter = jdbc.queryForObject("SELECT status FROM work_items WHERE id = ?", String.class, taskId);
            assertThat(statusAfter).isEqualTo("DONE");
        }

        @Test
        @DisplayName("PM accept on NON_CODE task in IN_REVIEW succeeds without requiring CI or PR evidence")
        void acceptNonCodeTaskSucceedsWithoutCiOrPr() throws Exception {
            UUID ownerId = createUser("pm-noncode@example.test", "PM NonCode", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-noncode@example.test", "Dev NonCode", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devId);

            UUID workspaceId = createWorkspace(ownerId, "ws-noncode", "Workspace NonCode");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "NC1", "Project NC1", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            // Create NON_CODE task
            UUID taskId = createWorkItemViaApi(workspaceId, projectId, pmToken, "Design Database Architecture Document", "NON_CODE");

            // Advance task to IN_REVIEW
            transitionToInReview(workspaceId, projectId, taskId, devToken, devId);

            // Acceptance criteria verified manually
            jdbc.update(
                    "INSERT INTO acceptance_criteria (id, project_id, work_item_id, position, description, evidence_kind, weight, status, verified_by, verified_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 1, 'Architecture diagram reviewed and approved by client', 'MANUAL', 1.0, 'VERIFIED', ?, now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, taskId, ownerId
            );

            // PM accepts NON_CODE task without any CI runs or PR reviews
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andExpect(jsonPath("$.acceptedBy").value(ownerId.toString()));

            String statusAfter = jdbc.queryForObject("SELECT status FROM work_items WHERE id = ?", String.class, taskId);
            assertThat(statusAfter).isEqualTo("DONE");
        }
    }
}
