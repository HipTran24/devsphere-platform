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
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
public class CiRunAndCodeReviewIntegrationTest {

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
        sessionKey[0] = 27;
        hmac[0] = 9;
        encryption[0] = 15;
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

    // --- Helper Fixtures ---

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

    private void recordPullRequestActivity(UUID projectId, UUID repositoryId, Integer prNumber, String prHeadSha, String actorLogin, UUID workItemId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, pr_number, pr_head_sha, actor_login, state, title, payload, happened_at, synced_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, 'PULL_REQUEST', ?, ?, ?, ?, 'OPEN', 'Implement Feature', '{}'::jsonb, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), projectId, repositoryId, workItemId, "pr-" + prNumber, prNumber, prHeadSha, actorLogin, now, now, now, now
        );
    }

    // =========================================================================
    // 1. CI Runs Persistence & Constraints Tests
    // =========================================================================

    @Nested
    @DisplayName("1. CI Runs Persistence & Constraints")
    class CiRunPersistenceAndConstraintsTests {

        @Test
        @DisplayName("Record CI run with valid parameters succeeds and persists in PostgreSQL")
        void recordCiRunSuccessfully() throws Exception {
            UUID pmUserId = createUser("pm-ci@example.test", "PM CI", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID workspaceId = createWorkspace(pmUserId, "ws-ci", "Workspace CI");
            UUID projectId = createProject(workspaceId, "CIRUN", "CI Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 10001L, "devsphere-org/ci-repo");

            String headSha = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            var payload = Map.of(
                    "repositoryId", repoId.toString(),
                    "githubRunId", 123456L,
                    "runAttempt", 1,
                    "headSha", headSha,
                    "prNumber", 10,
                    "status", "COMPLETED",
                    "conclusion", "SUCCESS",
                    "startedAt", now.minusMinutes(5).toString(),
                    "completedAt", now.toString()
            );

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ci-runs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.githubRunId").value(123456L))
                    .andExpect(jsonPath("$.runAttempt").value(1))
                    .andExpect(jsonPath("$.headSha").value(headSha))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.conclusion").value("SUCCESS"))
                    .andReturn();

            JsonNode node = json.readTree(result.getResponse().getContentAsString());
            UUID runId = UUID.fromString(node.get("id").asText());

            // Assert in PostgreSQL database
            String dbStatus = jdbc.queryForObject("SELECT status FROM ci_runs WHERE id = ?", String.class, runId);
            String dbConclusion = jdbc.queryForObject("SELECT conclusion FROM ci_runs WHERE id = ?", String.class, runId);
            assertThat(dbStatus).isEqualTo("COMPLETED");
            assertThat(dbConclusion).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("COMPLETED status requires non-null conclusion and completedAt; invalid combinations fail")
        void ciRunCheckConstraintCompletedRequiresConclusionAndCompletedAt() throws Exception {
            UUID pmUserId = createUser("pm-cichk@example.test", "PM CI Check", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID workspaceId = createWorkspace(pmUserId, "ws-cichk", "Workspace CI Check");
            UUID projectId = createProject(workspaceId, "CICHK", "CI Check Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 10002L, "devsphere-org/cichk-repo");

            String headSha = "b2c3d4e5f60718293a4b5c6d7e8f90123456789a";

            // 1. API validation: status = COMPLETED without conclusion fails
            var invalidPayload = Map.of(
                    "repositoryId", repoId.toString(),
                    "githubRunId", 20001L,
                    "runAttempt", 1,
                    "headSha", headSha,
                    "status", "COMPLETED"
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ci-runs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(invalidPayload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            // 2. Direct DB verification: CHECK ((status = 'COMPLETED') = (conclusion IS NOT NULL AND completed_at IS NOT NULL))
            // Attempting direct DB insert with COMPLETED and NULL conclusion fails
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.update(
                        "INSERT INTO ci_runs (id, project_id, repository_id, github_run_id, run_attempt, head_sha, status, conclusion, completed_at, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, 20002, 1, ?, 'COMPLETED', NULL, now(), now(), now(), 0)",
                        UUID.randomUUID(), projectId, repoId, headSha
                );
            })).isInstanceOf(DataIntegrityViolationException.class);

            // Attempting direct DB insert with QUEUED and non-null conclusion fails
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.update(
                        "INSERT INTO ci_runs (id, project_id, repository_id, github_run_id, run_attempt, head_sha, status, conclusion, completed_at, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, 20003, 1, ?, 'QUEUED', 'SUCCESS', now(), now(), now(), 0)",
                        UUID.randomUUID(), projectId, repoId, headSha
                );
            })).isInstanceOf(DataIntegrityViolationException.class);

            // Attempting direct DB insert with completed_at < started_at fails
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.update(
                        "INSERT INTO ci_runs (id, project_id, repository_id, github_run_id, run_attempt, head_sha, status, conclusion, started_at, completed_at, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, 20004, 1, ?, 'COMPLETED', 'SUCCESS', now(), now() - INTERVAL '1 hour', now(), now(), 0)",
                        UUID.randomUUID(), projectId, repoId, headSha
                );
            })).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Idempotent upsert on UNIQUE(repository_id, github_run_id, run_attempt)")
        void idempotentUpsertOnUniqueRunAttempt() throws Exception {
            UUID pmUserId = createUser("pm-upsert@example.test", "PM Upsert", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID workspaceId = createWorkspace(pmUserId, "ws-upsert", "Workspace Upsert");
            UUID projectId = createProject(workspaceId, "UPSRT", "Upsert Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 10003L, "devsphere-org/upsert-repo");

            String headSha = "c3d4e5f60718293a4b5c6d7e8f90123456789a1b";

            // 1st request: CI run started (IN_PROGRESS)
            var startPayload = Map.of(
                    "repositoryId", repoId.toString(),
                    "githubRunId", 30001L,
                    "runAttempt", 1,
                    "headSha", headSha,
                    "prNumber", 12,
                    "status", "IN_PROGRESS",
                    "startedAt", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3).toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ci-runs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(startPayload)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

            // 2nd request: CI run completed (COMPLETED, SUCCESS)
            var completePayload = Map.of(
                    "repositoryId", repoId.toString(),
                    "githubRunId", 30001L,
                    "runAttempt", 1,
                    "headSha", headSha,
                    "prNumber", 12,
                    "status", "COMPLETED",
                    "conclusion", "SUCCESS",
                    "completedAt", OffsetDateTime.now(ZoneOffset.UTC).toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ci-runs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(completePayload)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.conclusion").value("SUCCESS"));

            // Assert database count remains strictly 1 (updated, not duplicated)
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ci_runs WHERE repository_id = ? AND github_run_id = 30001 AND run_attempt = 1",
                    Long.class, repoId);
            assertThat(count).isEqualTo(1L);

            String finalStatus = jdbc.queryForObject(
                    "SELECT status FROM ci_runs WHERE repository_id = ? AND github_run_id = 30001 AND run_attempt = 1",
                    String.class, repoId);
            assertThat(finalStatus).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("Invalid headSha format rejected with 400 INVALID_HEAD_SHA")
        void invalidHeadShaFormatRejectedWith400() throws Exception {
            UUID pmUserId = createUser("pm-badsha@example.test", "PM BadSha", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID workspaceId = createWorkspace(pmUserId, "ws-badsha", "Workspace BadSha");
            UUID projectId = createProject(workspaceId, "BADSHA", "BadSha Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 10004L, "devsphere-org/badsha-repo");

            var payload = Map.of(
                    "repositoryId", repoId.toString(),
                    "githubRunId", 40001L,
                    "runAttempt", 1,
                    "headSha", "not-a-valid-40-hex-sha",
                    "status", "QUEUED"
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ci-runs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_HEAD_SHA"));
        }

        @Test
        @DisplayName("List CI runs with query filters and scoping")
        void listCiRunsWithFiltersAndScoping() throws Exception {
            UUID pmUserId = createUser("pm-listci@example.test", "PM ListCI", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID devUserId = createUser("dev-listci@example.test", "Dev ListCI", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUserId);
            UUID outsiderId = createUser("out-listci@example.test", "Out ListCI", "ACTIVE", "USER", true);
            String outsiderToken = issueAccessToken(outsiderId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-listci", "Workspace ListCI");
            UUID projectId = createProject(workspaceId, "LSTCI", "ListCI Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 10005L, "devsphere-org/listci-repo");

            addWorkspaceMember(workspaceId, devUserId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, devUserId, "DEVELOPER", pmUserId);

            String sha1 = "1111111111111111111111111111111111111111";
            String sha2 = "2222222222222222222222222222222222222222";

            // Insert 2 runs directly
            jdbc.update(
                    "INSERT INTO ci_runs (id, project_id, repository_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 50001, 1, ?, 1, 'COMPLETED', 'SUCCESS', now(), now() - INTERVAL '10 minutes', now(), 0)",
                    UUID.randomUUID(), projectId, repoId, sha1
            );
            jdbc.update(
                    "INSERT INTO ci_runs (id, project_id, repository_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 50002, 1, ?, 2, 'COMPLETED', 'FAILURE', now(), now(), now(), 0)",
                    UUID.randomUUID(), projectId, repoId, sha2
            );

            // Member can query all runs
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ci-runs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));

            // Member can filter by headSha
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ci-runs?headSha=" + sha1)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].headSha").value(sha1));

            // Member can filter by prNumber
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ci-runs?prNumber=2")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].prNumber").value(2));

            // Outsider receives 403 ACCESS_DENIED
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ci-runs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    // =========================================================================
    // 2. Human Code Review & Author Independence Tests
    // =========================================================================

    @Nested
    @DisplayName("2. Human Code Review & Author Independence")
    class HumanCodeReviewTests {

        @Test
        @DisplayName("Independent developer submits review on matching PR head SHA successfully")
        void independentDeveloperSubmitsReviewSuccessfully() throws Exception {
            UUID pmUserId = createUser("pm-rev@example.test", "PM Rev", "ACTIVE", "USER", true);
            UUID authorUserId = createUser("author-rev@example.test", "Author Rev", "ACTIVE", "USER", true);
            UUID reviewerUserId = createUser("reviewer-rev@example.test", "Reviewer Rev", "ACTIVE", "USER", true);
            String reviewerToken = issueAccessToken(reviewerUserId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-rev", "Workspace Rev");
            UUID projectId = createProject(workspaceId, "REV", "Rev Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20001L, "devsphere-org/rev-repo");

            addWorkspaceMember(workspaceId, authorUserId, "MEMBER");
            addWorkspaceMember(workspaceId, reviewerUserId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, authorUserId, "DEVELOPER", pmUserId);
            addProjectMemberWithRole(projectId, workspaceId, reviewerUserId, "DEVELOPER", pmUserId);

            // Link distinct GitHub OAuth accounts for Author and Reviewer
            linkGitHubOAuthAccount(authorUserId, "author-octocat");
            linkGitHubOAuthAccount(reviewerUserId, "reviewer-monalisa");

            String prHeadSha = "4444444444444444444444444444444444444444";
            recordPullRequestActivity(projectId, repoId, 1, prHeadSha, "author-octocat", null);

            var payload = Map.of(
                    "repositoryId", repoId.toString(),
                    "prNumber", 1,
                    "headSha", prHeadSha,
                    "scope", "PR",
                    "status", "PASS",
                    "summary", "LGTM! Verified architecture and tests.",
                    "findings", List.of()
            );

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/code-reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.prNumber").value(1))
                    .andExpect(jsonPath("$.headSha").value(prHeadSha))
                    .andExpect(jsonPath("$.reviewerKind").value("HUMAN"))
                    .andExpect(jsonPath("$.reviewerId").value(reviewerUserId.toString()))
                    .andExpect(jsonPath("$.status").value("PASS"))
                    .andReturn();

            JsonNode node = json.readTree(result.getResponse().getContentAsString());
            UUID reviewId = UUID.fromString(node.get("id").asText());

            // Assert row in code_reviews
            String dbStatus = jdbc.queryForObject("SELECT status FROM code_reviews WHERE id = ?", String.class, reviewId);
            assertThat(dbStatus).isEqualTo("PASS");

            // Assert row is present in current_pr_reviews view
            Long currentReviewCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM current_pr_reviews WHERE id = ?",
                    Long.class, reviewId);
            assertThat(currentReviewCount).isEqualTo(1L);
        }

        @Test
        @DisplayName("Author independence violation: PR author self-review is rejected with 422")
        void authorIndependenceViolationRejectedWith422() throws Exception {
            UUID pmUserId = createUser("pm-self@example.test", "PM Self", "ACTIVE", "USER", true);
            UUID authorUserId = createUser("author-self@example.test", "Author Self", "ACTIVE", "USER", true);
            String authorToken = issueAccessToken(authorUserId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-self", "Workspace Self");
            UUID projectId = createProject(workspaceId, "SELF", "Self Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20002L, "devsphere-org/self-repo");

            addWorkspaceMember(workspaceId, authorUserId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, authorUserId, "DEVELOPER", pmUserId);

            // Author's GitHub login
            linkGitHubOAuthAccount(authorUserId, "author-solo");

            String prHeadSha = "5555555555555555555555555555555555555555";
            recordPullRequestActivity(projectId, repoId, 2, prHeadSha, "author-solo", null);

            // Author attempts to submit approval on their own PR
            var selfReviewPayload = Map.of(
                    "repositoryId", repoId.toString(),
                    "prNumber", 2,
                    "headSha", prHeadSha,
                    "scope", "PR",
                    "status", "PASS",
                    "summary", "Self-approving my own pull request.",
                    "findings", List.of()
            );

            // 1. Rejected at API / Service level with 422 REVIEW_AUTHOR_INDEPENDENCE_VIOLATION
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/code-reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(selfReviewPayload)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("REVIEW_AUTHOR_INDEPENDENCE_VIOLATION"));

            // 2. Direct DB verification: trigger ds_review_evidence() rejects self-approval
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.update(
                        "INSERT INTO code_reviews (id, project_id, repository_id, pr_number, head_sha, context_version, scope, reviewer_kind, reviewer_id, status, findings, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, 2, ?, 0, 'PR', 'HUMAN', ?, 'PASS', '[]'::jsonb, now(), now(), 0)",
                        UUID.randomUUID(), projectId, repoId, prHeadSha, authorUserId
                );
            })).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Human PR approval requires an independent GitHub identity");
        }

        @Test
        @DisplayName("Reviewer without linked GitHub account in oauth_accounts rejected with 422")
        void reviewerWithoutLinkedGitHubAccountRejectedWith422() throws Exception {
            UUID pmUserId = createUser("pm-nooauth@example.test", "PM NoOAuth", "ACTIVE", "USER", true);
            UUID authorUserId = createUser("author-nooauth@example.test", "Author NoOAuth", "ACTIVE", "USER", true);
            UUID unlinkedDevId = createUser("unlinked-dev@example.test", "Unlinked Dev", "ACTIVE", "USER", true);
            String unlinkedToken = issueAccessToken(unlinkedDevId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-nooauth", "Workspace NoOAuth");
            UUID projectId = createProject(workspaceId, "NOAUTH", "NoOAuth Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20003L, "devsphere-org/nooauth-repo");

            addWorkspaceMember(workspaceId, authorUserId, "MEMBER");
            addWorkspaceMember(workspaceId, unlinkedDevId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, authorUserId, "DEVELOPER", pmUserId);
            addProjectMemberWithRole(projectId, workspaceId, unlinkedDevId, "DEVELOPER", pmUserId);

            linkGitHubOAuthAccount(authorUserId, "author-oauth-user");
            // unlinkedDevId deliberately has NO linked oauth_accounts!

            String prHeadSha = "6666666666666666666666666666666666666666";
            recordPullRequestActivity(projectId, repoId, 3, prHeadSha, "author-oauth-user", null);

            var payload = Map.of(
                    "repositoryId", repoId.toString(),
                    "prNumber", 3,
                    "headSha", prHeadSha,
                    "scope", "PR",
                    "status", "PASS",
                    "summary", "Review from dev without GitHub identity.",
                    "findings", List.of()
            );

            // 1. Rejected at API level
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/code-reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + unlinkedToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("REVIEW_AUTHOR_INDEPENDENCE_VIOLATION"));

            // 2. Direct DB verification: trigger ds_review_evidence() rejects approval without oauth account
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.update(
                        "INSERT INTO code_reviews (id, project_id, repository_id, pr_number, head_sha, context_version, scope, reviewer_kind, reviewer_id, status, findings, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, 3, ?, 0, 'PR', 'HUMAN', ?, 'PASS', '[]'::jsonb, now(), now(), 0)",
                        UUID.randomUUID(), projectId, repoId, prHeadSha, unlinkedDevId
                );
            })).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Human PR approval requires an independent GitHub identity");
        }

        @Test
        @DisplayName("Review on stale or mismatched PR head SHA rejected with 409 PR_HEAD_SHA_MISMATCH")
        void prHeadShaMismatchRejectedWith409() throws Exception {
            UUID pmUserId = createUser("pm-shamiss@example.test", "PM ShaMiss", "ACTIVE", "USER", true);
            UUID authorUserId = createUser("author-shamiss@example.test", "Author ShaMiss", "ACTIVE", "USER", true);
            UUID reviewerUserId = createUser("reviewer-shamiss@example.test", "Reviewer ShaMiss", "ACTIVE", "USER", true);
            String reviewerToken = issueAccessToken(reviewerUserId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-shamiss", "Workspace ShaMiss");
            UUID projectId = createProject(workspaceId, "SHAMS", "ShaMiss Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20004L, "devsphere-org/shamiss-repo");

            addWorkspaceMember(workspaceId, authorUserId, "MEMBER");
            addWorkspaceMember(workspaceId, reviewerUserId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, authorUserId, "DEVELOPER", pmUserId);
            addProjectMemberWithRole(projectId, workspaceId, reviewerUserId, "DEVELOPER", pmUserId);

            linkGitHubOAuthAccount(authorUserId, "author-alpha");
            linkGitHubOAuthAccount(reviewerUserId, "reviewer-bravo");

            String currentHeadSha = "7777777777777777777777777777777777777777";
            String staleHeadSha   = "8888888888888888888888888888888888888888";
            recordPullRequestActivity(projectId, repoId, 4, currentHeadSha, "author-alpha", null);

            var payload = Map.of(
                    "repositoryId", repoId.toString(),
                    "prNumber", 4,
                    "headSha", staleHeadSha, // Mismatched SHA
                    "scope", "PR",
                    "status", "PASS",
                    "summary", "Review on stale commit.",
                    "findings", List.of()
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/code-reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PR_HEAD_SHA_MISMATCH"));
        }

        @Test
        @DisplayName("Non-developer cannot submit review, rejected with 403 ACCESS_DENIED")
        void nonDeveloperCannotSubmitReviewRejectedWith403() throws Exception {
            UUID pmUserId = createUser("pm-nondev@example.test", "PM NonDev", "ACTIVE", "USER", true);
            UUID authorUserId = createUser("author-nondev@example.test", "Author NonDev", "ACTIVE", "USER", true);
            UUID viewerUserId = createUser("viewer-nondev@example.test", "Viewer NonDev", "ACTIVE", "USER", true);
            String viewerToken = issueAccessToken(viewerUserId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-nondev", "Workspace NonDev");
            UUID projectId = createProject(workspaceId, "NONDEV", "NonDev Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20005L, "devsphere-org/nondev-repo");

            addWorkspaceMember(workspaceId, authorUserId, "MEMBER");
            addWorkspaceMember(workspaceId, viewerUserId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, authorUserId, "DEVELOPER", pmUserId);
            addProjectMemberWithRole(projectId, workspaceId, viewerUserId, "VIEWER", pmUserId); // Only VIEWER role

            linkGitHubOAuthAccount(authorUserId, "author-charlie");
            linkGitHubOAuthAccount(viewerUserId, "viewer-delta");

            String prHeadSha = "9999999999999999999999999999999999999999";
            recordPullRequestActivity(projectId, repoId, 5, prHeadSha, "author-charlie", null);

            var payload = Map.of(
                    "repositoryId", repoId.toString(),
                    "prNumber", 5,
                    "headSha", prHeadSha,
                    "scope", "PR",
                    "status", "PASS",
                    "summary", "Review by viewer without developer role.",
                    "findings", List.of()
            );

            // 1. Rejected at API level with 403
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/code-reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            // 2. Direct DB verification: trigger ds_review_evidence() rejects PASS review by non-developer
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.update(
                        "INSERT INTO code_reviews (id, project_id, repository_id, pr_number, head_sha, context_version, scope, reviewer_kind, reviewer_id, status, findings, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, 5, ?, 0, 'PR', 'HUMAN', ?, 'PASS', '[]'::jsonb, now(), now(), 0)",
                        UUID.randomUUID(), projectId, repoId, prHeadSha, viewerUserId
                );
            })).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Human reviewer requires Developer role");
        }

        @Test
        @DisplayName("List and get code reviews by ID")
        void listAndGetCodeReviews() throws Exception {
            UUID pmUserId = createUser("pm-getrev@example.test", "PM GetRev", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID devUserId = createUser("dev-getrev@example.test", "Dev GetRev", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUserId);
            UUID outsiderId = createUser("out-getrev@example.test", "Out GetRev", "ACTIVE", "USER", true);
            String outsiderToken = issueAccessToken(outsiderId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-getrev", "Workspace GetRev");
            UUID projectId = createProject(workspaceId, "GETREV", "GetRev Project", pmUserId);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 20006L, "devsphere-org/getrev-repo");

            addWorkspaceMember(workspaceId, devUserId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, devUserId, "DEVELOPER", pmUserId);

            linkGitHubOAuthAccount(devUserId, "dev-oauth");

            UUID reviewId = UUID.randomUUID();
            String sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            jdbc.update(
                    "INSERT INTO code_reviews (id, project_id, repository_id, pr_number, head_sha, context_version, scope, reviewer_kind, reviewer_id, status, summary, findings, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 6, ?, 0, 'PR', 'HUMAN', ?, 'PASS', 'Approved', '[]'::jsonb, now(), now(), 0)",
                    reviewId, projectId, repoId, sha, devUserId
            );

            // Member can list reviews
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/code-reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(reviewId.toString()));

            // Member can get review by ID
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/code-reviews/" + reviewId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(reviewId.toString()))
                    .andExpect(jsonPath("$.headSha").value(sha))
                    .andExpect(jsonPath("$.status").value("PASS"));

            // Outsider receives 403 ACCESS_DENIED
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/code-reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }
}
