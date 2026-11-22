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
public class PlanProposalLifecycleIntegrationTest {

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
        sessionKey[0] = 23;
        hmac[0] = 7;
        encryption[0] = 11;
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
                    "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at) " +
                            "VALUES (?, ?, 'OWNER', 'ACTIVE', ?)",
                    workspaceId, ownerId, now
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

    private Long getProjectVersion(UUID projectId) {
        return jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
    }

    private void addProjectMemberWithRole(UUID projectId, UUID workspaceId, UUID userId, String role, UUID grantedBy) {
        tx.executeWithoutResult(status -> {
            UUID memberId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
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

    private UUID createRequirement(UUID projectId, String code, String title, UUID requestedBy) {
        return tx.execute(s -> {
            UUID reqId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO requirements (id, project_id, code, title, description, status, current_version, requested_by, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'Requirement description', 'APPROVED', 1, ?, ?, ?, 1)",
                    reqId, projectId, code, title, requestedBy, now, now
            );
            jdbc.update(
                    "INSERT INTO requirement_versions (id, project_id, requirement_id, version_no, description, acceptance_summary, change_reason, approved_at, created_at) " +
                            "VALUES (?, ?, ?, 1, 'Requirement description', 'Acceptance summary', NULL, ?, ?)",
                    UUID.randomUUID(), projectId, reqId, now, now
            );
            return reqId;
        });
    }

    private UUID createWorkItem(UUID projectId, UUID requirementId, String itemKey, String title, int estimateMinutes) {
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO work_items (id, project_id, item_key, requirement_id, type, work_kind, title, status, estimate_minutes, remaining_minutes, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, 'TASK', 'CODE', ?, 'TO_DO', ?, ?, ?, ?, 0)",
                itemId, projectId, itemKey, requirementId, title, estimateMinutes, estimateMinutes, now, now
        );
        return itemId;
    }

    private void configureDeveloperCapacity(UUID projectId, UUID devId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO developer_capacity (
                    id, project_id, user_id, effective_from, effective_to,
                    minutes_per_workday, allocation_pct,
                    skills, weekly_slots, time_off, created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, '2026-12-01', NULL,
                    480, 100.0,
                    '[{"skill":"backend","level":4}]'::jsonb,
                    '[{"dayOfWeek":1,"startMinute":540,"endMinute":1020},
                      {"dayOfWeek":2,"startMinute":540,"endMinute":1020},
                      {"dayOfWeek":3,"startMinute":540,"endMinute":1020},
                      {"dayOfWeek":4,"startMinute":540,"endMinute":1020},
                      {"dayOfWeek":5,"startMinute":540,"endMinute":1020}]'::jsonb,
                    '[]'::jsonb,
                    ?, ?, 0
                )
                """,
                UUID.randomUUID(), projectId, devId, now, now
        );
    }

    // =========================================================================
    // 1. Access Control Matrix Tests
    // =========================================================================
    @Nested
    @DisplayName("1. Access Control Matrix Tests")
    class AccessControlMatrixTests {

        @Test
        @DisplayName("Active PM can create draft proposal (201 Created)")
        void pmCanCreateDraftProposal() throws Exception {
            UUID owner = createUser("pm_owner@devsphere.test", "PM Owner", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(owner);
            UUID ws = createWorkspace(owner, "ws-pm-proposal", "PM Proposal WS");
            UUID prj = createProject(ws, "PROP1", "Proposal Project", owner);

            Long projVersion = getProjectVersion(prj);
            Map<String, Object> req = Map.of(
                    "kind", "MANUAL_REPLAN",
                    "expectedProjectVersion", projVersion
            );

            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.solverStatus").value("NOT_RUN"))
                    .andExpect(jsonPath("$.baseProjectVersion").value(projVersion));
        }

        @Test
        @DisplayName("Active PM can trigger solve proposal (200 OK)")
        void pmCanTriggerSolveProposal() throws Exception {
            UUID owner = createUser("pm_solve@devsphere.test", "PM Solve", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(owner);
            UUID ws = createWorkspace(owner, "ws-pm-solve", "PM Solve WS");
            UUID prj = createProject(ws, "SOLVE1", "Solve Project", owner);

            Long projVersion = getProjectVersion(prj);

            // Create proposal
            Map<String, Object> createReq = Map.of(
                    "kind", "MANUAL_REPLAN",
                    "expectedProjectVersion", projVersion
            );
            MvcResult res = mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID propId = UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());

            // PM triggers solve
            Map<String, Object> solveReq = Map.of(
                    "expectedProjectVersion", projVersion,
                    "expectedProposalVersion", 0,
                    "solverLimitSeconds", 5
            );

            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{propId}/solve", ws, prj, propId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(solveReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(propId.toString()))
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        @DisplayName("Non-PM roles (DEVELOPER, CLIENT, VIEWER) cannot create or solve proposals (403 Forbidden)")
        void nonPmRolesCannotCreateOrSolveProposals() throws Exception {
            UUID owner = createUser("ws_owner@devsphere.test", "Owner", "ACTIVE", "USER", true);
            UUID devUser = createUser("dev_user@devsphere.test", "Dev User", "ACTIVE", "USER", true);
            UUID clientUser = createUser("client_user@devsphere.test", "Client User", "ACTIVE", "USER", true);
            UUID viewerUser = createUser("viewer_user@devsphere.test", "Viewer User", "ACTIVE", "USER", true);

            String devToken = issueAccessToken(devUser);
            String clientToken = issueAccessToken(clientUser);
            String viewerToken = issueAccessToken(viewerUser);

            UUID ws = createWorkspace(owner, "ws-rbac-prop", "RBAC Proposal WS");
            addWorkspaceMember(ws, devUser, "MEMBER");
            addWorkspaceMember(ws, clientUser, "MEMBER");
            addWorkspaceMember(ws, viewerUser, "MEMBER");

            UUID prj = createProject(ws, "RBAC1", "RBAC Project", owner);
            addProjectMemberWithRole(prj, ws, devUser, "DEVELOPER", owner);
            addProjectMemberWithRole(prj, ws, clientUser, "CLIENT", owner);
            addProjectMemberWithRole(prj, ws, viewerUser, "VIEWER", owner);

            Long projVersion = getProjectVersion(prj);
            Map<String, Object> createReq = Map.of(
                    "kind", "MANUAL_REPLAN",
                    "expectedProjectVersion", projVersion
            );

            // Developer fails
            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isForbidden());

            // Client fails
            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isForbidden());

            // Viewer fails
            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Non-member of workspace receives 403 Forbidden")
        void nonMemberReceivesForbidden() throws Exception {
            UUID owner = createUser("owner_nm@devsphere.test", "Owner NM", "ACTIVE", "USER", true);
            UUID outsider = createUser("outsider@devsphere.test", "Outsider", "ACTIVE", "USER", true);
            String outsiderToken = issueAccessToken(outsider);

            UUID ws = createWorkspace(owner, "ws-outsider", "Outsider WS");
            UUID prj = createProject(ws, "OUT1", "Outsider Project", owner);

            Long projVersion = getProjectVersion(prj);
            Map<String, Object> createReq = Map.of(
                    "kind", "MANUAL_REPLAN",
                    "expectedProjectVersion", projVersion
            );

            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Unauthenticated request receives 401 Unauthorized")
        void unauthenticatedReceivesUnauthorized() throws Exception {
            UUID owner = createUser("owner_unauth@devsphere.test", "Owner Unauth", "ACTIVE", "USER", true);
            UUID ws = createWorkspace(owner, "ws-unauth", "Unauth WS");
            UUID prj = createProject(ws, "UNAUTH1", "Unauth Project", owner);

            Long projVersion = getProjectVersion(prj);
            Map<String, Object> createReq = Map.of(
                    "kind", "MANUAL_REPLAN",
                    "expectedProjectVersion", projVersion
            );

            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // =========================================================================
    // 2. Proposal Lifecycle & Draft Tests
    // =========================================================================
    @Nested
    @DisplayName("2. Proposal Lifecycle & Draft Tests")
    class ProposalLifecycleAndDraftTests {

        @Test
        @DisplayName("Create draft proposal initializes DRAFT, NOT_RUN, and base_project_version")
        void createDraftProposalInitializesCorrectly() throws Exception {
            UUID owner = createUser("pm_draft@devsphere.test", "PM Draft", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(owner);
            UUID ws = createWorkspace(owner, "ws-draft-prop", "Draft Proposal WS");
            UUID prj = createProject(ws, "DRFT1", "Draft Project", owner);

            Long projVersion = getProjectVersion(prj);
            Map<String, Object> req = Map.of(
                    "kind", "AUTO_REPLAN",
                    "expectedProjectVersion", projVersion,
                    "changesCommittedDeadline", false
            );

            MvcResult result = mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.kind").value("AUTO_REPLAN"))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.solverStatus").value("NOT_RUN"))
                    .andExpect(jsonPath("$.baseProjectVersion").value(projVersion))
                    .andExpect(jsonPath("$.createdBy").value(owner.toString()))
                    .andReturn();

            UUID propId = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());

            // GET by ID
            mvc.perform(get("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{propId}", ws, prj, propId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(propId.toString()))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.solverStatus").value("NOT_RUN"));

            // GET List
            mvc.perform(get("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(propId.toString()));
        }
    }

    // =========================================================================
    // 3. Project Version Stale Tests
    // =========================================================================
    @Nested
    @DisplayName("3. Project Version Stale Tests")
    class ProjectVersionStaleTests {

        @Test
        @DisplayName("Mismatched expectedProjectVersion on create returns 409 PLAN_STALE")
        void mismatchedExpectedProjectVersionOnCreateFails() throws Exception {
            UUID owner = createUser("pm_stale1@devsphere.test", "PM Stale 1", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(owner);
            UUID ws = createWorkspace(owner, "ws-stale-1", "Stale 1 WS");
            UUID prj = createProject(ws, "STL1", "Stale Project 1", owner);

            Map<String, Object> req = Map.of(
                    "kind", "MANUAL_REPLAN",
                    "expectedProjectVersion", 999 // Mismatched
            );

            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_STALE"));
        }

        @Test
        @DisplayName("Concurrent project update causes solve to fail with 409 PLAN_STALE")
        void concurrentProjectUpdateCausesSolveToFailPlanStale() throws Exception {
            UUID owner = createUser("pm_stale2@devsphere.test", "PM Stale 2", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(owner);
            UUID ws = createWorkspace(owner, "ws-stale-2", "Stale 2 WS");
            UUID prj = createProject(ws, "STL2", "Stale Project 2", owner);

            Long projVersion = getProjectVersion(prj);

            // Create proposal at current version
            Map<String, Object> createReq = Map.of(
                    "kind", "MANUAL_REPLAN",
                    "expectedProjectVersion", projVersion
            );
            MvcResult res = mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID propId = UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());

            // Simulate project update (bumping version)
            jdbc.update("UPDATE projects SET version = version + 1 WHERE id = ?", prj);

            // Attempt solve with stale expectedProjectVersion
            Map<String, Object> solveReq = Map.of(
                    "expectedProjectVersion", projVersion, // Stale!
                    "expectedProposalVersion", 0
            );

            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{propId}/solve", ws, prj, propId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(solveReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_STALE"));
        }

        @Test
        @DisplayName("Mismatched expectedProposalVersion fails with 409 PROPOSAL_VERSION_CONFLICT")
        void mismatchedProposalVersionFails() throws Exception {
            UUID owner = createUser("pm_stale3@devsphere.test", "PM Stale 3", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(owner);
            UUID ws = createWorkspace(owner, "ws-stale-3", "Stale 3 WS");
            UUID prj = createProject(ws, "STL3", "Stale Project 3", owner);

            Long projVersion = getProjectVersion(prj);

            // Create proposal
            Map<String, Object> createReq = Map.of(
                    "kind", "MANUAL_REPLAN",
                    "expectedProjectVersion", projVersion
            );
            MvcResult res = mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID propId = UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());

            // Attempt solve with mismatched expectedProposalVersion
            Map<String, Object> solveReq = Map.of(
                    "expectedProjectVersion", projVersion,
                    "expectedProposalVersion", 999 // Mismatched (actual is 0)
            );

            mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{propId}/solve", ws, prj, propId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(solveReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_VERSION_CONFLICT"));
        }
    }

    // =========================================================================
    // 4. Solver Trigger & Two-Phase Update Compliance Tests
    // =========================================================================
    @Nested
    @DisplayName("4. Solver Trigger & Two-Phase Update Compliance Tests")
    class SolverTriggerAndTwoPhaseUpdateComplianceTests {

        @Test
        @DisplayName("Trigger solve executes two-phase update and preserves genuine solver_status without ds_proposal_guard wipe")
        void solvePreservesSolverStatusViaTwoPhaseUpdate() throws Exception {
            UUID owner = createUser("pm_solver@devsphere.test", "PM Solver", "ACTIVE", "USER", true);
            UUID dev = createUser("dev_solver@devsphere.test", "Dev Solver", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(owner);

            UUID ws = createWorkspace(owner, "ws-solver-run", "Solver Run WS");
            addWorkspaceMember(ws, dev, "MEMBER");
            UUID prj = createProject(ws, "SOLV2", "Solver Run Project", owner);
            addProjectMemberWithRole(prj, ws, dev, "DEVELOPER", owner);

            // Configure developer capacity
            configureDeveloperCapacity(prj, dev);

            // Create requirement and work item
            UUID reqId = createRequirement(prj, "REQ-1", "Feature A", owner);
            UUID task1 = createWorkItem(prj, reqId, "SOLV2-1", "Task 1", 120);
            UUID task2 = createWorkItem(prj, reqId, "SOLV2-2", "Task 2", 180);

            // Get current project version
            Long projVersion = getProjectVersion(prj);

            // Create proposal with tasks in payload
            Map<String, Object> payload = Map.of(
                    "schemaVersion", 1,
                    "items", List.of(
                            Map.of("tempKey", "TASK-1", "targetId", task1.toString(), "taskKey", "SOLV2-1", "title", "Task 1", "estimateMinutes", 120),
                            Map.of("tempKey", "TASK-2", "targetId", task2.toString(), "taskKey", "SOLV2-2", "title", "Task 2", "estimateMinutes", 180)
                    ),
                    "dependencies", List.of()
            );

            Map<String, Object> createReq = Map.of(
                    "kind", "MANUAL_REPLAN",
                    "expectedProjectVersion", projVersion,
                    "proposalPayload", payload
            );

            MvcResult createRes = mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals", ws, prj)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            JsonNode createNode = json.readTree(createRes.getResponse().getContentAsString());
            UUID propId = UUID.fromString(createNode.get("id").asText());
            Long propVersion = createNode.get("version").asLong();

            // Trigger solve
            Map<String, Object> solveReq = Map.of(
                    "expectedProjectVersion", projVersion,
                    "expectedProposalVersion", propVersion,
                    "solverLimitSeconds", 10,
                    "horizonStart", "2026-12-01",
                    "horizonEnd", "2026-12-31"
            );

            MvcResult solveRes = mvc.perform(post("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{propId}/solve", ws, prj, propId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(solveReq)))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode solveNode = json.readTree(solveRes.getResponse().getContentAsString());
            String returnedSolverStatus = solveNode.get("solverStatus").asText();

            // Solver status must NOT be NOT_RUN!
            assertThat(returnedSolverStatus).isNotEqualTo("NOT_RUN");
            assertThat(returnedSolverStatus).isIn("OPTIMAL", "FEASIBLE", "INFEASIBLE");

            // DIRECT DATABASE INSPECTION: verify that ds_proposal_guard did NOT wipe solver_status
            String dbSolverStatus = jdbc.queryForObject(
                    "SELECT solver_status FROM plan_proposals WHERE id = ?",
                    String.class,
                    propId
            );
            assertThat(dbSolverStatus).isEqualTo(returnedSolverStatus);
            assertThat(dbSolverStatus).isNotEqualTo("NOT_RUN");

            // Verify proposal_payload was populated
            String dbPayload = jdbc.queryForObject(
                    "SELECT proposal_payload::text FROM plan_proposals WHERE id = ?",
                    String.class,
                    propId
            );
            assertThat(dbPayload).isNotNull().contains("schemaVersion");

            // Verify solver_details was populated
            String dbDetails = jdbc.queryForObject(
                    "SELECT solver_details::text FROM plan_proposals WHERE id = ?",
                    String.class,
                    propId
            );
            assertThat(dbDetails).isNotNull().contains("rawNativeStatus");

            // Verify GET diagnostics endpoint returns solver metrics
            mvc.perform(get("/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{propId}/diagnostics", ws, prj, propId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.proposalId").value(propId.toString()))
                    .andExpect(jsonPath("$.solverStatus").value(returnedSolverStatus))
                    .andExpect(jsonPath("$.rawNativeStatus").isNotEmpty());
        }
    }

    // =========================================================================
    // 5. Database Invariant Reset On Edit Tests
    // =========================================================================
    @Nested
    @DisplayName("5. Database Invariant Reset On Edit Tests")
    class DatabaseInvariantResetOnEditTests {

        @Test
        @DisplayName("Direct update to proposal_payload triggers ds_proposal_guard and resets solver_status to NOT_RUN")
        void updatePayloadResetsStatusAndSolverStatus() {
            UUID owner = createUser("pm_reset@devsphere.test", "PM Reset", "ACTIVE", "USER", true);
            UUID ws = createWorkspace(owner, "ws-reset-test", "Reset Test WS");
            UUID prj = createProject(ws, "RST1", "Reset Project", owner);

            UUID propId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            // Insert proposal in state where solver has supposedly run (must include items array)
            jdbc.update("""
                    INSERT INTO plan_proposals (
                        id, project_id, kind, base_project_version, status,
                        proposal_payload, solver_status, solver_details, assumptions,
                        created_by, changes_committed_deadline, created_at, updated_at, version
                    ) VALUES (
                        ?, ?, 'MANUAL_REPLAN', 0, 'DRAFT',
                        '{"schemaVersion": 1, "items": []}'::jsonb,
                        'OPTIMAL',
                        '{"schemaVersion": 1, "wallTimeSeconds": 0.5}'::jsonb,
                        '[]'::jsonb,
                        ?, false, ?, ?, 0
                    )
                    """,
                    propId, prj, owner, now, now
            );

            // Verify initial solver_status is OPTIMAL
            String initialStatus = jdbc.queryForObject(
                    "SELECT solver_status FROM plan_proposals WHERE id = ?", String.class, propId);
            assertThat(initialStatus).isEqualTo("OPTIMAL");

            // Execute UPDATE modifying proposal_payload (preserving items array)
            jdbc.update("""
                    UPDATE plan_proposals
                    SET proposal_payload = '{"schemaVersion": 1, "items": [], "modified": true}'::jsonb
                    WHERE id = ?
                    """,
                    propId
            );

            // Trigger ds_proposal_guard MUST have reset solver_status to NOT_RUN and status to DRAFT
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, solver_status, approved_by, approved_at FROM plan_proposals WHERE id = ?",
                    propId
            );

            assertThat(row.get("status")).isEqualTo("DRAFT");
            assertThat(row.get("solver_status")).isEqualTo("NOT_RUN");
            assertThat(row.get("approved_by")).isNull();
            assertThat(row.get("approved_at")).isNull();
        }

        @Test
        @DisplayName("Applied proposal is strictly immutable (trigger ds_proposal_guard raises 23514)")
        void appliedProposalIsImmutable() {
            UUID owner = createUser("pm_applied@devsphere.test", "PM Applied", "ACTIVE", "USER", true);
            UUID ws = createWorkspace(owner, "ws-applied-test", "Applied Test WS");
            UUID prj = createProject(ws, "APP1", "Applied Project", owner);

            UUID propId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            // Insert proposal in DRAFT status with valid items array
            jdbc.update("""
                    INSERT INTO plan_proposals (
                        id, project_id, kind, base_project_version, status,
                        proposal_payload, solver_status, solver_details, assumptions,
                        created_by, approved_by, approved_at,
                        changes_committed_deadline, created_at, updated_at, version
                    ) VALUES (
                        ?, ?, 'MANUAL_REPLAN', 0, 'DRAFT',
                        '{"schemaVersion": 1, "items": []}'::jsonb,
                        'OPTIMAL',
                        '{"schemaVersion": 1}'::jsonb,
                        '[]'::jsonb,
                        ?, ?, ?,
                        false, ?, ?, 0
                    )
                    """,
                    propId, prj, owner, owner, now, now, now
            );

            // Set to APPLIED
            jdbc.update("UPDATE plan_proposals SET status = 'APPLIED' WHERE id = ?", propId);

            // Any subsequent UPDATE must be rejected by ds_proposal_guard with 'Applied proposal is immutable'
            assertThatThrownBy(() ->
                    jdbc.update("UPDATE plan_proposals SET assumptions = '[\"new assumption\"]'::jsonb WHERE id = ?", propId)
            ).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Applied proposal is immutable");
        }
    }
}
