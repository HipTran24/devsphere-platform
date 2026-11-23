package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.monitoring.api.dto.ImpactAnalysisRequest;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
public class ControlTowerHttpIntegrationTest {

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
        sessionKey[0] = 31;
        hmac[0] = 17;
        encryption[0] = 41;
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
                    "INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, committed_deadline, sprint_length_days, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'Project description', 'Project objective', 'PLANNING', 'Asia/Ho_Chi_Minh', '2026-12-01', '2026-12-31', 14, ?, ?, 0)",
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
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID memberId;
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM project_members WHERE project_id = ? AND user_id = ?",
                    Integer.class, projectId, userId
            );
            if (count == null || count == 0) {
                memberId = UUID.randomUUID();
                jdbc.update(
                        "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                        memberId, projectId, workspaceId, userId, now, now, now
                );
            } else {
                memberId = jdbc.queryForObject(
                        "SELECT id FROM project_members WHERE project_id = ? AND user_id = ?",
                        UUID.class, projectId, userId
                );
            }
            jdbc.update(
                    "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), projectId, memberId, role, grantedBy, now, userId, now
            );
        });
    }

    private UUID createWorkItem(
            UUID projectId,
            String itemKey,
            String title,
            String status,
            String workKind,
            int estimateMinutes,
            int remainingMinutes,
            BigDecimal claimedProgress,
            UUID assigneeId) {

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO work_items (
                    id, project_id, type, item_key, title, description, status, work_kind, priority,
                    estimate_minutes, remaining_minutes, claimed_progress, assignee_id,
                    created_at, updated_at, version
                ) VALUES (
                    ?, ?, 'TASK', ?, ?, 'Task description', 'TO_DO', ?, 'MEDIUM',
                    ?, ?, ?, ?,
                    now(), now(), 0
                )
                """,
                id, projectId, itemKey, title, workKind,
                estimateMinutes, remainingMinutes, claimedProgress, assigneeId
        );

        if ("IN_PROGRESS".equalsIgnoreCase(status)) {
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS' WHERE id = ?", id);
        } else if ("IN_REVIEW".equalsIgnoreCase(status)) {
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS' WHERE id = ?", id);
            jdbc.update("UPDATE work_items SET status = 'IN_REVIEW' WHERE id = ?", id);
        } else if ("DONE".equalsIgnoreCase(status)) {
            UUID pmUser = jdbc.queryForObject(
                    "SELECT user_id FROM project_members m JOIN project_member_roles r ON r.project_member_id = m.id WHERE m.project_id = ? AND r.role = 'PM' LIMIT 1",
                    UUID.class, projectId
            );
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS' WHERE id = ?", id);
            jdbc.update("UPDATE work_items SET status = 'IN_REVIEW' WHERE id = ?", id);
            jdbc.update("UPDATE work_items SET status = 'DONE', completed_at = now(), accepted_by = ? WHERE id = ?", pmUser, id);
        }

        return id;
    }

    private void createDependency(UUID projectId, UUID predId, UUID succId, int lagMinutes) {
        jdbc.update("""
                INSERT INTO work_item_dependencies (
                    id, project_id, predecessor_id, successor_id, link_type, lag_minutes, created_at
                ) VALUES (
                    gen_random_uuid(), ?, ?, ?, 'FINISH_TO_START', ?, now()
                )
                """,
                projectId, predId, succId, lagMinutes
        );
    }

    private UUID createCriterion(
            UUID projectId,
            UUID workItemId,
            int position,
            String description,
            String evidenceKind,
            double weight,
            String status,
            UUID verifiedBy) {

        UUID id = UUID.randomUUID();
        OffsetDateTime verifiedAt = "VERIFIED".equalsIgnoreCase(status) ? OffsetDateTime.now(ZoneOffset.UTC) : null;
        UUID verifier = "VERIFIED".equalsIgnoreCase(status) ? verifiedBy : null;

        jdbc.update("""
                INSERT INTO acceptance_criteria (
                    id, project_id, work_item_id, position, description, evidence_kind, weight, status, evidence, verified_by, verified_at, created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, '[]'::jsonb, ?, ?, now(), now(), 0
                )
                """,
                id, projectId, workItemId, position, description, evidenceKind, weight, status, verifier, verifiedAt
        );
        return id;
    }

    private UUID connectRepositoryDirectly(UUID workspaceId, UUID projectId, Long githubRepoId, String fullName) {
        UUID repoId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO github_repositories (id, workspace_id, project_id, github_installation_id, github_repo_id, full_name, default_branch, private, active, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 66666, ?, ?, 'main', true, true, ?, ?, 0)",
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
                        "VALUES (?, ?, ?, ?, 'PULL_REQUEST', ?, ?, ?, ?, 'OPEN', 'PR Title', '{}'::jsonb, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), projectId, repositoryId, workItemId, "pr-" + prNumber, prNumber, prHeadSha, actorLogin, now, now, now, now
        );
    }

    private void recordCiRun(UUID projectId, UUID repositoryId, String headSha, Integer prNumber, String conclusion) {
        jdbc.update(
                "INSERT INTO ci_runs (id, project_id, repository_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, completed_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 60001, 1, ?, ?, 'COMPLETED', ?, now(), now(), now(), 0)",
                UUID.randomUUID(), projectId, repositoryId, headSha, prNumber, conclusion
        );
    }

    private void recordCodeReview(UUID projectId, UUID repositoryId, UUID reviewerId, String headSha, Integer prNumber, String reviewStatus) {
        jdbc.update(
                "INSERT INTO code_reviews (id, project_id, repository_id, reviewer_id, reviewer_kind, scope, head_sha, pr_number, status, summary, findings, completed_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, 'HUMAN', 'PR', ?, ?, ?, 'LGTM', '[]'::jsonb, now(), now(), now(), 0)",
                UUID.randomUUID(), projectId, repositoryId, reviewerId, headSha, prNumber, reviewStatus
        );
    }

    // =========================================================================
    // 1. Control Tower Graph Tests
    // =========================================================================
    @Nested
    @DisplayName("1. Control Tower Graph API Tests")
    class ControlTowerGraphTests {

        @Test
        @DisplayName("PM can retrieve control tower graph with computed makespan, critical path, and slack")
        void testGetGraph_pmCanAccess_returnsCalculatedGraph() throws Exception {
            UUID pmUser = createUser("pm-ct1@example.test", "PM CT1", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-ct1", "Workspace CT1");
            UUID projectId = createProject(workspaceId, "CT1", "Project CT1", pmUser);

            // A (100) -> B (200) -> C (150) => Makespan = 450 (Critical path)
            // Parallel: D (100) -> C (150) => D finishes at 100, C starts at 300 (D slack = 200)
            UUID taskA = createWorkItem(projectId, "CT1-1", "Task A", "TO_DO", "NON_CODE", 100, 100, BigDecimal.ZERO, null);
            UUID taskB = createWorkItem(projectId, "CT1-2", "Task B", "TO_DO", "NON_CODE", 200, 200, BigDecimal.ZERO, null);
            UUID taskC = createWorkItem(projectId, "CT1-3", "Task C", "TO_DO", "NON_CODE", 150, 150, BigDecimal.ZERO, null);
            UUID taskD = createWorkItem(projectId, "CT1-4", "Task D", "TO_DO", "NON_CODE", 100, 100, BigDecimal.ZERO, null);

            createDependency(projectId, taskA, taskB, 0);
            createDependency(projectId, taskB, taskC, 0);
            createDependency(projectId, taskD, taskC, 0);

            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/control-tower/graph", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                    .andExpect(jsonPath("$.projectMakespanMinutes").value(450))
                    .andExpect(jsonPath("$.totalTasks").value(4))
                    .andExpect(jsonPath("$.criticalTasksCount").value(3)) // A, B, C
                    .andExpect(jsonPath("$.criticalPathItemIds[0]").value(taskA.toString()))
                    .andExpect(jsonPath("$.criticalPathItemIds[1]").value(taskB.toString()))
                    .andExpect(jsonPath("$.criticalPathItemIds[2]").value(taskC.toString()))
                    .andExpect(jsonPath("$.nodes.length()").value(4))
                    .andExpect(jsonPath("$.edges.length()").value(3));
        }

        @Test
        @DisplayName("DEVELOPER role can also access control tower graph")
        void testGetGraph_developerCanAccess() throws Exception {
            UUID pmUser = createUser("pm-ct2@example.test", "PM CT2", "ACTIVE", "USER", true);
            UUID devUser = createUser("dev-ct2@example.test", "Dev CT2", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-ct2", "Workspace CT2");
            addWorkspaceMember(workspaceId, devUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "CT2", "Project CT2", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, devUser, "DEVELOPER", pmUser);

            createWorkItem(projectId, "CT2-1", "Task 1", "TO_DO", "NON_CODE", 60, 60, BigDecimal.ZERO, null);

            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/control-tower/graph", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalTasks").value(1))
                    .andExpect(jsonPath("$.projectMakespanMinutes").value(60));
        }

        @Test
        @DisplayName("CLIENT role caller receives 403 Forbidden (internal control tower graph shielded)")
        void testGetGraph_clientRoleForbidden() throws Exception {
            UUID pmUser = createUser("pm-ct3@example.test", "PM CT3", "ACTIVE", "USER", true);
            UUID clientUser = createUser("cl-ct3@example.test", "Client CT3", "ACTIVE", "USER", true);
            String clientToken = issueAccessToken(clientUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-ct3", "Workspace CT3");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "CT3", "Project CT3", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);

            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/control-tower/graph", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("Non-project member caller receives 403 Forbidden")
        void testGetGraph_nonMemberForbidden() throws Exception {
            UUID pmUser = createUser("pm-ct4@example.test", "PM CT4", "ACTIVE", "USER", true);
            UUID outsideUser = createUser("out-ct4@example.test", "Outside CT4", "ACTIVE", "USER", true);
            String outsideToken = issueAccessToken(outsideUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-ct4", "Workspace CT4");
            UUID projectId = createProject(workspaceId, "CT4", "Project CT4", pmUser);

            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/control-tower/graph", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsideToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("Dependency cycle in database triggers 422 DEPENDENCY_CYCLE_DETECTED")
        void testGetGraph_cycleDetected_returns422() throws Exception {
            UUID pmUser = createUser("pm-ct5@example.test", "PM CT5", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-ct5", "Workspace CT5");
            UUID projectId = createProject(workspaceId, "CT5", "Project CT5", pmUser);

            UUID tA = createWorkItem(projectId, "CT5-1", "Task A", "TO_DO", "NON_CODE", 50, 50, BigDecimal.ZERO, null);
            UUID tB = createWorkItem(projectId, "CT5-2", "Task B", "TO_DO", "NON_CODE", 50, 50, BigDecimal.ZERO, null);
            UUID tC = createWorkItem(projectId, "CT5-3", "Task C", "TO_DO", "NON_CODE", 50, 50, BigDecimal.ZERO, null);

            // Cycle: A -> B -> C -> A
            // Temporarily bypass trigger to create synthetic cycle for defense-in-depth API test
            jdbc.execute("SET session_replication_role = 'replica'");
            try {
                createDependency(projectId, tA, tB, 0);
                createDependency(projectId, tB, tC, 0);
                createDependency(projectId, tC, tA, 0);
            } finally {
                jdbc.execute("SET session_replication_role = 'origin'");
            }

            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/control-tower/graph", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_CYCLE_DETECTED"));
        }
    }

    // =========================================================================
    // 2. Impact Analysis Tests
    // =========================================================================
    @Nested
    @DisplayName("2. Impact Analysis API Tests")
    class ImpactAnalysisTests {

        @Test
        @DisplayName("PM runs impact analysis on critical task: correctly identifies project ETA shift and downstream tasks")
        void testImpactAnalysis_criticalTask_pushesDownstreamAndEta() throws Exception {
            UUID pmUser = createUser("pm-ia1@example.test", "PM IA1", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-ia1", "Workspace IA1");
            UUID projectId = createProject(workspaceId, "IA1", "Project IA1", pmUser);

            UUID taskA = createWorkItem(projectId, "IA1-1", "Task A", "TO_DO", "NON_CODE", 100, 100, BigDecimal.ZERO, null);
            UUID taskB = createWorkItem(projectId, "IA1-2", "Task B", "TO_DO", "NON_CODE", 200, 200, BigDecimal.ZERO, null);
            createDependency(projectId, taskA, taskB, 0);

            // Delay Task A by 60 minutes
            ImpactAnalysisRequest request = new ImpactAnalysisRequest(taskA, 60, "Blocked by vendor API outage");

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/impact-analyses", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                    .andExpect(jsonPath("$.targetWorkItemId").value(taskA.toString()))
                    .andExpect(jsonPath("$.delayMinutes").value(60))
                    .andExpect(jsonPath("$.targetTotalSlackMinutes").value(0))
                    .andExpect(jsonPath("$.projectEtaDelayMinutes").value(60))
                    .andExpect(jsonPath("$.shiftsProjectDeadline").value(true))
                    .andExpect(jsonPath("$.affectedTasks.length()").value(1))
                    .andExpect(jsonPath("$.affectedTasks[0].workItemId").value(taskB.toString()))
                    .andExpect(jsonPath("$.affectedTasks[0].delayMinutes").value(60));
        }

        @Test
        @DisplayName("Impact analysis on task with slack: delay absorbed without shifting project ETA")
        void testImpactAnalysis_taskWithSlack_absorbsDelay() throws Exception {
            UUID pmUser = createUser("pm-ia2@example.test", "PM IA2", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-ia2", "Workspace IA2");
            UUID projectId = createProject(workspaceId, "IA2", "Project IA2", pmUser);

            // Diamond DAG:
            // A (100) -> B (300) -> D (100) => Makespan = 500
            // A (100) -> C (100) -> D (100) => C has slack 200
            UUID tA = createWorkItem(projectId, "IA2-1", "Task A", "TO_DO", "NON_CODE", 100, 100, BigDecimal.ZERO, null);
            UUID tB = createWorkItem(projectId, "IA2-2", "Task B", "TO_DO", "NON_CODE", 300, 300, BigDecimal.ZERO, null);
            UUID tC = createWorkItem(projectId, "IA2-3", "Task C", "TO_DO", "NON_CODE", 100, 100, BigDecimal.ZERO, null);
            UUID tD = createWorkItem(projectId, "IA2-4", "Task D", "TO_DO", "NON_CODE", 100, 100, BigDecimal.ZERO, null);

            createDependency(projectId, tA, tB, 0);
            createDependency(projectId, tA, tC, 0);
            createDependency(projectId, tB, tD, 0);
            createDependency(projectId, tC, tD, 0);

            // Delay C by 50 minutes (slack is 200)
            ImpactAnalysisRequest request = new ImpactAnalysisRequest(tC, 50, "Minor refactoring delay");

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/impact-analyses", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.targetWorkItemId").value(tC.toString()))
                    .andExpect(jsonPath("$.targetTotalSlackMinutes").value(200))
                    .andExpect(jsonPath("$.delayMinutes").value(50))
                    .andExpect(jsonPath("$.projectEtaDelayMinutes").value(0))
                    .andExpect(jsonPath("$.shiftsProjectDeadline").value(false))
                    .andExpect(jsonPath("$.affectedTasks").isEmpty());
        }

        @Test
        @DisplayName("DEVELOPER role caller is forbidden from impact analysis (PM only)")
        void testImpactAnalysis_developerForbidden() throws Exception {
            UUID pmUser = createUser("pm-ia3@example.test", "PM IA3", "ACTIVE", "USER", true);
            UUID devUser = createUser("dev-ia3@example.test", "Dev IA3", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-ia3", "Workspace IA3");
            addWorkspaceMember(workspaceId, devUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "IA3", "Project IA3", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, devUser, "DEVELOPER", pmUser);

            UUID taskA = createWorkItem(projectId, "IA3-1", "Task A", "TO_DO", "NON_CODE", 100, 100, BigDecimal.ZERO, null);

            ImpactAnalysisRequest request = new ImpactAnalysisRequest(taskA, 30, "Developer delay");

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/impact-analyses", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("Non-existent work item in impact analysis returns 404 RESOURCE_NOT_FOUND")
        void testImpactAnalysis_nonExistentWorkItem_returns404() throws Exception {
            UUID pmUser = createUser("pm-ia4@example.test", "PM IA4", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-ia4", "Workspace IA4");
            UUID projectId = createProject(workspaceId, "IA4", "Project IA4", pmUser);

            ImpactAnalysisRequest request = new ImpactAnalysisRequest(UUID.randomUUID(), 30, "Non-existent delay");

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/impact-analyses", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    // =========================================================================
    // 3. Progress Evaluation Tests
    // =========================================================================
    @Nested
    @DisplayName("3. Progress Evaluation API Tests")
    class ProgressEvaluationTests {

        @Test
        @DisplayName("Evaluate progress on work item with weighted criteria computes verified ratio")
        void testEvaluateProgress_weightedCriteria_calculatesVerifiedPercentage() throws Exception {
            UUID pmUser = createUser("pm-pe1@example.test", "PM PE1", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-pe1", "Workspace PE1");
            UUID projectId = createProject(workspaceId, "PE1", "Project PE1", pmUser);

            // Work item with claimedProgress = 50%
            UUID workItemId = createWorkItem(projectId, "PE1-1", "Design API", "IN_PROGRESS", "NON_CODE", 120, 60, new BigDecimal("50.00"), null);

            // Criteria: C1 (weight 2, VERIFIED), C2 (weight 3, VERIFIED), C3 (weight 5, NOT_MET) => Total 10, Verified 5 = 50.00%
            createCriterion(projectId, workItemId, 1, "Spec draft", "MANUAL", 2.0, "VERIFIED", pmUser);
            createCriterion(projectId, workItemId, 2, "Review notes", "MANUAL", 3.0, "VERIFIED", pmUser);
            createCriterion(projectId, workItemId, 3, "Client sign-off", "MANUAL", 5.0, "NOT_MET", null);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/work-items/{id}/evaluate-progress", workspaceId, projectId, workItemId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workItemId").value(workItemId.toString()))
                    .andExpect(jsonPath("$.claimedProgress").value(50.00))
                    .andExpect(jsonPath("$.verifiedProgress").value(50.00))
                    .andExpect(jsonPath("$.evaluationStatus").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.discrepancyDetected").value(false))
                    .andExpect(jsonPath("$.totalCriteriaCount").value(3))
                    .andExpect(jsonPath("$.verifiedCriteriaCount").value(2));
        }

        @Test
        @DisplayName("Progress discrepancy detected when claimed progress exceeds verified progress by 25% or more")
        void testEvaluateProgress_discrepancyDetected() throws Exception {
            UUID pmUser = createUser("pm-pe2@example.test", "PM PE2", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-pe2", "Workspace PE2");
            UUID projectId = createProject(workspaceId, "PE2", "Project PE2", pmUser);

            // Claimed 85%
            UUID workItemId = createWorkItem(projectId, "PE2-1", "Build Engine", "IN_PROGRESS", "NON_CODE", 120, 20, new BigDecimal("85.00"), null);

            // 1 verified (wt 1), 1 not met (wt 1) => Verified = 50.00% (delta = 35% >= 25%)
            createCriterion(projectId, workItemId, 1, "Unit tests", "MANUAL", 1.0, "VERIFIED", pmUser);
            createCriterion(projectId, workItemId, 2, "Integration tests", "MANUAL", 1.0, "NOT_MET", null);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/work-items/{id}/evaluate-progress", workspaceId, projectId, workItemId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verifiedProgress").value(50.00))
                    .andExpect(jsonPath("$.discrepancyDetected").value(true))
                    .andExpect(jsonPath("$.evaluationStatus").value("DISCREPANCY_DETECTED"));
        }

        @Test
        @DisplayName("CODE task with criteria 100% verified and complete CI/PR/Review evidence returns VERIFIED")
        void testEvaluateProgress_codeTask_withCompleteEvidence_verified() throws Exception {
            UUID pmUser = createUser("pm-pe3@example.test", "PM PE3", "ACTIVE", "USER", true);
            UUID devUser = createUser("dev-pe3@example.test", "Dev PE3", "ACTIVE", "USER", true);
            UUID reviewerUser = createUser("rev-pe3@example.test", "Rev PE3", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-pe3", "Workspace PE3");
            addWorkspaceMember(workspaceId, devUser, "MEMBER");
            addWorkspaceMember(workspaceId, reviewerUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "PE3", "Project PE3", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, devUser, "DEVELOPER", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, reviewerUser, "DEVELOPER", pmUser);

            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 30001L, "devsphere-org/pe3-repo");

            // CODE task assigned to devUser
            UUID workItemId = createWorkItem(projectId, "PE3-1", "Payment Feature", "IN_REVIEW", "CODE", 120, 0, new BigDecimal("100.00"), devUser);
            createCriterion(projectId, workItemId, 1, "Stripe Webhook Spec", "CI_TEST", 1.0, "VERIFIED", pmUser);

            String headSha = "9999999999999999999999999999999999999999";
            linkGitHubOAuthAccount(reviewerUser, "reviewer-pe3");
            recordPullRequestActivity(projectId, repoId, 42, headSha, "dev-pe3", workItemId);
            recordCiRun(projectId, repoId, headSha, 42, "SUCCESS");
            recordCodeReview(projectId, repoId, reviewerUser, headSha, 42, "PASS");

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/work-items/{id}/evaluate-progress", workspaceId, projectId, workItemId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verifiedProgress").value(100.00))
                    .andExpect(jsonPath("$.evaluationStatus").value("VERIFIED"))
                    .andExpect(jsonPath("$.codeEvidence.prLinked").value(true))
                    .andExpect(jsonPath("$.codeEvidence.ciSuccess").value(true))
                    .andExpect(jsonPath("$.codeEvidence.codeReviewPass").value(true))
                    .andExpect(jsonPath("$.codeEvidence.independentReviewer").value(true));
        }

        @Test
        @DisplayName("Observational Invariant: Evaluate progress NEVER automatically changes work item status to DONE")
        void testEvaluateProgress_observationalInvariant_neverMutatesStatusToDone() throws Exception {
            UUID pmUser = createUser("pm-pe4@example.test", "PM PE4", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-pe4", "Workspace PE4");
            UUID projectId = createProject(workspaceId, "PE4", "Project PE4", pmUser);

            // Work item starts in IN_REVIEW
            UUID workItemId = createWorkItem(projectId, "PE4-1", "Critical Task", "IN_REVIEW", "NON_CODE", 60, 0, new BigDecimal("100.00"), null);
            createCriterion(projectId, workItemId, 1, "Acceptance Step 1", "MANUAL", 1.0, "VERIFIED", pmUser);

            // Evaluate progress
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/work-items/{id}/evaluate-progress", workspaceId, projectId, workItemId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verifiedProgress").value(100.00))
                    .andExpect(jsonPath("$.evaluationStatus").value("VERIFIED"));

            // Database Assertion:
            // verified_progress column was updated, BUT status is STILL 'IN_REVIEW', NOT 'DONE'
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, verified_progress FROM work_items WHERE id = ?",
                    workItemId
            );
            assertThat(row.get("status")).isEqualTo("IN_REVIEW");
            assertThat(((BigDecimal) row.get("verified_progress"))).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("Non-project member caller receives 403 Forbidden")
        void testEvaluateProgress_nonMemberForbidden() throws Exception {
            UUID pmUser = createUser("pm-pe5@example.test", "PM PE5", "ACTIVE", "USER", true);
            UUID outsideUser = createUser("out-pe5@example.test", "Out PE5", "ACTIVE", "USER", true);
            String outsideToken = issueAccessToken(outsideUser);

            UUID workspaceId = createWorkspace(pmUser, "ws-pe5", "Workspace PE5");
            UUID projectId = createProject(workspaceId, "PE5", "Project PE5", pmUser);

            UUID workItemId = createWorkItem(projectId, "PE5-1", "Task", "IN_PROGRESS", "NON_CODE", 60, 30, BigDecimal.ZERO, null);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/work-items/{id}/evaluate-progress", workspaceId, projectId, workItemId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsideToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }
}
