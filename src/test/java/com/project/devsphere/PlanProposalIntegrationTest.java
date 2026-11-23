package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.planning.api.dto.CreatePlanProposalRequest;
import com.project.devsphere.planning.api.dto.SolveProposalRequest;
import com.project.devsphere.planning.domain.PlanProposal;
import com.project.devsphere.resource.api.PutResourceCapacityRequest;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.WeeklySlot;
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

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
public class PlanProposalIntegrationTest {

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
        sessionKey[0] = 17;
        hmac[0] = 3;
        encryption[0] = 5;
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
                            "VALUES (?, ?, ?, ?, 'Planning Proposal Project', 'Proposal Objective', 'PLANNING', 'Asia/Ho_Chi_Minh', '2026-12-01', 14, ?, ?, 0)",
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

    private Long getProjectVersion(UUID projectId) {
        Long v = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
        return v != null ? v : 0L;
    }

    private List<WeeklySlot> createStandardWeekdaySlots() {
        return List.of(
                new WeeklySlot(1, 540, 1020),
                new WeeklySlot(2, 540, 1020),
                new WeeklySlot(3, 540, 1020),
                new WeeklySlot(4, 540, 1020),
                new WeeklySlot(5, 540, 1020)
        );
    }

    @Nested
    @DisplayName("Role & Scope Guard Tests")
    class RoleAndScopeGuardTests {

        @Test
        @DisplayName("PM can create draft proposal; outsider receives 403 Forbidden")
        void pmCanCreateDraftProposalOutsiderForbidden() throws Exception {
            UUID ownerId = createUser("pm-prop@example.test", "PM Proposal", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID outsiderId = createUser("outsider-prop@example.test", "Outsider", "ACTIVE", "USER", true);
            String outsiderToken = issueAccessToken(outsiderId);

            UUID workspaceId = createWorkspace(ownerId, "ws-prop", "Workspace Proposal");
            UUID projectId = createProject(workspaceId, "PROP1", "Prop Project 1", ownerId);
            Long pVer = getProjectVersion(projectId);

            // 1. PM creates draft proposal -> 201 Created
            CreatePlanProposalRequest req = new CreatePlanProposalRequest(
                    PlanProposal.Kind.MANUAL_REPLAN,
                    pVer,
                    null,
                    null,
                    null,
                    Map.of("schemaVersion", 1, "items", List.of(Map.of("tempKey", "TASK-1", "estimateMinutes", 120))),
                    List.of("PM Assumption"),
                    false,
                    null
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/plan-proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.solverStatus").value("NOT_RUN"))
                    .andExpect(jsonPath("$.kind").value("MANUAL_REPLAN"))
                    .andExpect(jsonPath("$.baseProjectVersion").value(pVer));

            // 2. Outsider attempt -> 403 Forbidden
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/plan-proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Stale project version on createDraftProposal is rejected with 409 PLAN_STALE")
        void staleProjectVersionRejected() throws Exception {
            UUID ownerId = createUser("pm-stale@example.test", "PM Stale", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-stale", "Workspace Stale");
            UUID projectId = createProject(workspaceId, "STALE", "Stale Project", ownerId);

            CreatePlanProposalRequest req = new CreatePlanProposalRequest(
                    PlanProposal.Kind.MANUAL_REPLAN,
                    999L, // Stale version!
                    null,
                    null,
                    null,
                    Map.of("schemaVersion", 1, "items", List.of()),
                    List.of(),
                    false,
                    null
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/plan-proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_STALE"));
        }
    }

    @Nested
    @DisplayName("Two-Phase Solver Update & Database Invariants Tests")
    class TwoPhaseSolverUpdateTests {

        @Test
        @DisplayName("Solving proposal executes mandatory two-phase update; database retains OPTIMAL solver_status")
        void solvingProposalRetainsOptimalStatusInDatabase() throws Exception {
            UUID ownerId = createUser("pm-solve@example.test", "PM Solve", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-solve@example.test", "Dev Solve", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "ws-solve", "Workspace Solve");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "SOLV", "Solve Project", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            // Configure developer capacity
            Long pVer0 = getProjectVersion(projectId);
            PutResourceCapacityRequest capReq = new PutResourceCapacityRequest(
                    devId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("backend", 4), new SkillLevel("database", 3)),
                    createStandardWeekdaySlots(),
                    List.of(),
                    480,
                    new BigDecimal("100.00"),
                    pVer0,
                    null
            );

            mvc.perform(put("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(capReq)))
                    .andExpect(status().isCreated());

            // Create proposal with 2 tasks and 1 dependency: T1 -> T2
            Long pVer1 = getProjectVersion(projectId);
            Map<String, Object> payload = Map.of(
                    "schemaVersion", 1,
                    "items", List.of(
                            Map.of(
                                    "tempKey", "T1",
                                    "title", "Database schema migration",
                                    "estimateMinutes", 120,
                                    "requiredSkills", List.of(Map.of("skill", "database", "level", 3))
                            ),
                            Map.of(
                                    "tempKey", "T2",
                                    "title", "Backend service endpoints",
                                    "estimateMinutes", 240,
                                    "requiredSkills", List.of(Map.of("skill", "backend", "level", 4))
                            )
                    ),
                    "dependencies", List.of(
                            Map.of("predecessorKey", "T1", "successorKey", "T2", "lagMinutes", 0)
                    )
            );

            CreatePlanProposalRequest createReq = new CreatePlanProposalRequest(
                    PlanProposal.Kind.MANUAL_REPLAN,
                    pVer1,
                    null,
                    null,
                    null,
                    payload,
                    List.of("Standard Sprint Assumption"),
                    false,
                    null
            );

            MvcResult createRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/plan-proposals")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            JsonNode createNode = json.readTree(createRes.getResponse().getContentAsString());
            UUID proposalId = UUID.fromString(createNode.get("id").asText());
            Long proposalVer = createNode.get("version").asLong();

            // Execute solve
            Long pVer2 = getProjectVersion(projectId);
            SolveProposalRequest solveReq = new SolveProposalRequest(
                    pVer2,
                    proposalVer,
                    15,
                    LocalDate.of(2026, 12, 1),
                    LocalDate.of(2026, 12, 31)
            );

            MvcResult solveRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/plan-proposals/" + proposalId + "/solve")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(solveReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.solverStatus").value("OPTIMAL"))
                    .andReturn();

            JsonNode solveNode = json.readTree(solveRes.getResponse().getContentAsString());
            assertThat(solveNode.get("solverStatus").asText()).isEqualTo("OPTIMAL");
            assertThat(solveNode.get("proposalPayload").get("items").get(0).has("developerId")).isTrue();
            assertThat(solveNode.get("proposalPayload").get("items").get(1).has("developerId")).isTrue();

            // Crucial verification: Check database state directly via SQL
            // ds_proposal_guard() would have reset solver_status to NOT_RUN if two-phase update was not used!
            Map<String, Object> dbRow = jdbc.queryForMap(
                    "SELECT solver_status, status FROM plan_proposals WHERE id = ?", proposalId
            );
            assertThat(dbRow.get("solver_status").toString()).isEqualTo("OPTIMAL");
            assertThat(dbRow.get("status").toString()).isEqualTo("DRAFT");

            // Verify Diagnostics endpoint
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/plan-proposals/" + proposalId + "/diagnostics")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.solverStatus").value("OPTIMAL"))
                    .andExpect(jsonPath("$.rawNativeStatus").value("OPTIMAL"))
                    .andExpect(jsonPath("$.proposalId").value(proposalId.toString()));

            // Verify Get Proposal endpoint
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/plan-proposals/" + proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(proposalId.toString()))
                    .andExpect(jsonPath("$.solverStatus").value("OPTIMAL"));
        }
    }
}
