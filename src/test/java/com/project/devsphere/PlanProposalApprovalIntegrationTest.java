package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.planning.api.dto.ApprovePlanProposalRequest;
import com.project.devsphere.planning.api.dto.DeadlineConfirmationRequest;
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
public class PlanProposalApprovalIntegrationTest {

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

    private UUID createWorkspace(UUID ownerId, String name, String slug) {
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

    private UUID createProject(UUID workspaceId, String projectKey, String name, UUID pmUserId, UUID deadlineApproverId) {
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

            if (deadlineApproverId != null) {
                UUID clientMemberId;
                Integer exists = jdbc.queryForObject(
                        "SELECT count(*) FROM project_members WHERE project_id = ? AND user_id = ?",
                        Integer.class, projectId, deadlineApproverId
                );
                if (exists == null || exists == 0) {
                    clientMemberId = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                                    "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                            clientMemberId, projectId, workspaceId, deadlineApproverId, now, now, now
                    );
                } else {
                    clientMemberId = jdbc.queryForObject(
                            "SELECT id FROM project_members WHERE project_id = ? AND user_id = ?",
                            UUID.class, projectId, deadlineApproverId
                    );
                }

                Integer roleExists = jdbc.queryForObject(
                        "SELECT count(*) FROM project_member_roles WHERE project_id = ? AND project_member_id = ? AND role = 'CLIENT'",
                        Integer.class, projectId, clientMemberId
                );
                if (roleExists == null || roleExists == 0) {
                    jdbc.update(
                            "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                                    "VALUES (?, ?, ?, 'CLIENT', ?, ?, ?, ?)",
                            UUID.randomUUID(), projectId, clientMemberId, pmUserId != null ? pmUserId : deadlineApproverId, now, deadlineApproverId, now
                    );
                }

                jdbc.update(
                        "UPDATE projects SET deadline_approver_id = ? WHERE id = ?",
                        deadlineApproverId, projectId
                );
            }

            return projectId;
        });
    }

    private void addProjectMemberWithRole(UUID projectId, UUID workspaceId, UUID userId, String role, UUID grantedBy) {
        tx.executeWithoutResult(status -> {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
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
            Integer roleCount = jdbc.queryForObject(
                    "SELECT count(*) FROM project_member_roles WHERE project_id = ? AND project_member_id = ? AND role = ?",
                    Integer.class, projectId, memberId, role
            );
            if (roleCount == null || roleCount == 0) {
                jdbc.update(
                        "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), projectId, memberId, role, grantedBy, now, userId, now
                );
            }
        });
    }

    private Long getProjectVersion(UUID projectId) {
        return jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
    }

    private UUID createMilestone(UUID projectId, String name, LocalDate targetDate) {
        UUID milestoneId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String code = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        jdbc.update(
                "INSERT INTO milestones (id, project_id, code, name, due_date, status, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, 'PLANNED', ?, ?, 0)",
                milestoneId, projectId, code, name, targetDate, now, now
        );
        return milestoneId;
    }

    private UUID insertProposal(
            UUID projectId,
            UUID creatorId,
            String status,
            String solverStatus,
            boolean changesCommittedDeadline,
            LocalDate proposedCommittedDeadline,
            UUID approvedBy,
            OffsetDateTime approvedAt) {

        UUID proposalId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Long projectVer = getProjectVersion(projectId);

        String payloadJson = """
                {
                  "schemaVersion": 1,
                  "items": [
                    { "tempKey": "T1", "title": "Task 1", "estimateMinutes": 120 }
                  ]
                }
                """;

        String solverDetailsJson = """
                {
                  "schemaVersion": 1,
                  "solverStatus": "%s",
                  "wallTimeSeconds": 1.25,
                  "numWorkers": 4
                }
                """.formatted(solverStatus);

        jdbc.update(
                "INSERT INTO plan_proposals (id, project_id, kind, base_project_version, status, proposal_payload, solver_status, " +
                        "solver_details, assumptions, changes_committed_deadline, proposed_committed_deadline, created_by, " +
                        "created_at, updated_at, version) " +
                        "VALUES (?, ?, 'MANUAL_REPLAN', ?, 'DRAFT', ?::jsonb, ?, ?::jsonb, '[]'::jsonb, ?, ?, ?, ?, ?, 0)",
                proposalId, projectId, projectVer, payloadJson, solverStatus,
                solverDetailsJson, changesCommittedDeadline, proposedCommittedDeadline, creatorId,
                now, now
        );

        if ("APPROVED".equals(status)) {
            jdbc.update(
                    "UPDATE plan_proposals SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ?",
                    approvedBy, approvedAt != null ? approvedAt : now, proposalId
            );
        }

        return proposalId;
    }

    private Long getProposalVersion(UUID proposalId) {
        return jdbc.queryForObject("SELECT version FROM plan_proposals WHERE id = ?", Long.class, proposalId);
    }

    // =========================================================================
    // 1. PM Approval Tests
    // =========================================================================
    @Nested
    @DisplayName("1. PM Approval of Solved Proposals")
    class ApprovalTests {

        @Test
        @DisplayName("Valid PM approves proposal: status becomes APPROVED, approvedBy set, approvedAt set")
        void testApproveProposal_happyPath() throws Exception {
            UUID pmUser = createUser("pm@example.test", "PM User", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(pmUser, "Approve Workspace", "ws-approve-1");
            UUID projectId = createProject(workspaceId, "PRJAPP1", "Approve Project 1", pmUser, null);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "OPTIMAL", false, null, null, null);
            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String pmToken = issueAccessToken(pmUser);

            ApprovePlanProposalRequest req = new ApprovePlanProposalRequest(projectVer, proposalVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/approve", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(proposalId.toString()))
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.approvedBy").value(pmUser.toString()))
                    .andExpect(jsonPath("$.approvedAt").isNotEmpty());

            // Verify in PostgreSQL
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, approved_by, approved_at FROM plan_proposals WHERE id = ?",
                    proposalId
            );
            assertThat(row.get("status")).isEqualTo("APPROVED");
            assertThat(row.get("approved_by")).isEqualTo(pmUser);
            assertThat(row.get("approved_at")).isNotNull();
        }

        @Test
        @DisplayName("Proposal with FEASIBLE solver status can also be approved")
        void testApproveProposal_feasibleStatusAllowed() throws Exception {
            UUID pmUser = createUser("pm-feas@example.test", "PM Feas User", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(pmUser, "Feas Workspace", "ws-feas-1");
            UUID projectId = createProject(workspaceId, "PRJAPP1", "Feas Project", pmUser, null);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "FEASIBLE", false, null, null, null);
            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String pmToken = issueAccessToken(pmUser);

            ApprovePlanProposalRequest req = new ApprovePlanProposalRequest(projectVer, proposalVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/approve", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }

        @Test
        @DisplayName("Cannot approve proposal with solver status NOT_RUN")
        void testApproveProposal_rejectsNotRun() throws Exception {
            UUID pmUser = createUser("pm-notrun@example.test", "PM NotRun", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(pmUser, "NotRun Workspace", "ws-notrun-1");
            UUID projectId = createProject(workspaceId, "PRJAPP1", "NotRun Project", pmUser, null);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "NOT_RUN", false, null, null, null);
            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String pmToken = issueAccessToken(pmUser);

            ApprovePlanProposalRequest req = new ApprovePlanProposalRequest(projectVer, proposalVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/approve", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_SOLVED"));
        }

        @Test
        @DisplayName("Cannot approve proposal with solver status INFEASIBLE")
        void testApproveProposal_rejectsInfeasible() throws Exception {
            UUID pmUser = createUser("pm-inf@example.test", "PM Inf", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(pmUser, "Inf Workspace", "ws-inf-1");
            UUID projectId = createProject(workspaceId, "PRJAPP1", "Inf Project", pmUser, null);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "INFEASIBLE", false, null, null, null);
            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String pmToken = issueAccessToken(pmUser);

            ApprovePlanProposalRequest req = new ApprovePlanProposalRequest(projectVer, proposalVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/approve", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_SOLVED"));
        }

        @Test
        @DisplayName("Stale expectedProjectVersion throws 409 PLAN_STALE")
        void testApproveProposal_staleProjectVersion() throws Exception {
            UUID pmUser = createUser("pm-stale@example.test", "PM Stale", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(pmUser, "Stale Workspace", "ws-stale-1");
            UUID projectId = createProject(workspaceId, "PRJAPP1", "Stale Project", pmUser, null);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "OPTIMAL", false, null, null, null);
            Long currentProjectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String pmToken = issueAccessToken(pmUser);

            // Send wrong project version (current + 99)
            ApprovePlanProposalRequest req = new ApprovePlanProposalRequest(currentProjectVer + 99, proposalVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/approve", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_STALE"));
        }

        @Test
        @DisplayName("Stale expectedProposalVersion throws 409 PROPOSAL_VERSION_CONFLICT")
        void testApproveProposal_staleProposalVersion() throws Exception {
            UUID pmUser = createUser("pm-ver@example.test", "PM Ver", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(pmUser, "Ver Workspace", "ws-ver-1");
            UUID projectId = createProject(workspaceId, "PRJAPP1", "Ver Project", pmUser, null);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "OPTIMAL", false, null, null, null);
            Long projectVer = getProjectVersion(projectId);
            Long currentProposalVer = getProposalVersion(proposalId);

            String pmToken = issueAccessToken(pmUser);

            // Send wrong proposal version (current + 5)
            ApprovePlanProposalRequest req = new ApprovePlanProposalRequest(projectVer, currentProposalVer + 5);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/approve", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_VERSION_CONFLICT"));
        }
    }

    // =========================================================================
    // 2. AccessPolicy Role Restrictions on Approval
    // =========================================================================
    @Nested
    @DisplayName("2. AccessPolicy Role Restrictions on Approval")
    class ApprovalRoleRestrictionsTests {

        @Test
        @DisplayName("Developer role caller is forbidden from approving proposals (403 ACCESS_DENIED)")
        void testApproveProposal_developerForbidden() throws Exception {
            UUID pmUser = createUser("pm-devtest@example.test", "PM DevTest", "ACTIVE", "USER", true);
            UUID devUser = createUser("dev-test@example.test", "Dev User", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "DevTest Workspace", "ws-devtest-1");
            addWorkspaceMember(workspaceId, devUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJAPP1", "Dev Project", pmUser, null);
            addProjectMemberWithRole(projectId, workspaceId, devUser, "DEVELOPER", pmUser);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "OPTIMAL", false, null, null, null);
            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String devToken = issueAccessToken(devUser);

            ApprovePlanProposalRequest req = new ApprovePlanProposalRequest(projectVer, proposalVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/approve", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("Client role caller is forbidden from approving proposals (403 ACCESS_DENIED)")
        void testApproveProposal_clientForbidden() throws Exception {
            UUID pmUser = createUser("pm-cltest@example.test", "PM ClTest", "ACTIVE", "USER", true);
            UUID clientUser = createUser("cl-test@example.test", "Client User", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "ClTest Workspace", "ws-cltest-1");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJAPP1", "Cl Project", pmUser, null);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "OPTIMAL", false, null, null, null);
            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String clientToken = issueAccessToken(clientUser);

            ApprovePlanProposalRequest req = new ApprovePlanProposalRequest(projectVer, proposalVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/approve", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("Non-project member caller receives 403 ACCESS_DENIED")
        void testApproveProposal_nonMemberForbidden() throws Exception {
            UUID pmUser = createUser("pm-nm@example.test", "PM NM", "ACTIVE", "USER", true);
            UUID outsideUser = createUser("outside@example.test", "Outside User", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "NM Workspace", "ws-nm-1");
            UUID projectId = createProject(workspaceId, "PRJAPP1", "NM Project", pmUser, null);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "OPTIMAL", false, null, null, null);
            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String outsideToken = issueAccessToken(outsideUser);

            ApprovePlanProposalRequest req = new ApprovePlanProposalRequest(projectVer, proposalVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/approve", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsideToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    // =========================================================================
    // 3. Client Deadline Consent Projection Tests
    // =========================================================================
    @Nested
    @DisplayName("3. Client Deadline Consent Projection & Leak Prevention")
    class DeadlineConsentTests {

        @Test
        @DisplayName("GET /deadline-consent exposes safe projection and strictly zeroes internal developer capacity / solver leaks")
        void testGetDeadlineConsent_happyPath_and_zeroLeak() throws Exception {
            UUID pmUser = createUser("pm-dc@example.test", "PM DC", "ACTIVE", "USER", true);
            UUID clientUser = createUser("client-dc@example.test", "Client DC", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "DC Workspace", "ws-dc-1");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER");

            // Project has committed_deadline = 2026-12-31, deadline_approver_id = clientUser
            UUID projectId = createProject(workspaceId, "PRJAPP1", "DC Project", pmUser, clientUser);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);

            // Create milestone with target date 2026-12-31
            createMilestone(projectId, "Milestone Alpha", LocalDate.of(2026, 12, 31));

            // Proposal proposes new deadline 2027-01-15 (delay = 15 days)
            UUID proposalId = insertProposal(
                    projectId, pmUser, "DRAFT", "OPTIMAL", true, LocalDate.of(2027, 1, 15), null, null
            );

            String clientToken = issueAccessToken(clientUser);

            MvcResult result = mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-consent", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.proposalId").value(proposalId.toString()))
                    .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                    .andExpect(jsonPath("$.changesCommittedDeadline").value(true))
                    .andExpect(jsonPath("$.currentCommittedDeadline").value("2026-12-31"))
                    .andExpect(jsonPath("$.proposedCommittedDeadline").value("2027-01-15"))
                    .andExpect(jsonPath("$.deadlineApproverUserId").value(clientUser.toString()))
                    .andExpect(jsonPath("$.isDesignatedApprover").value(true))
                    .andExpect(jsonPath("$.confirmed").value(false))
                    .andExpect(jsonPath("$.milestoneImpacts[0].name").value("Milestone Alpha"))
                    .andExpect(jsonPath("$.milestoneImpacts[0].delayDays").value(15))
                    .andReturn();

            String responseBody = result.getResponse().getContentAsString();
            JsonNode rootNode = json.readTree(responseBody);

            // STRICT ZERO LEAK VERIFICATION:
            // Ensure no internal developer capacities, weekly working slots, time-off, or solver details leaked
            assertThat(responseBody).doesNotContain("developerCapacity");
            assertThat(responseBody).doesNotContain("weeklySlots");
            assertThat(responseBody).doesNotContain("timeOff");
            assertThat(responseBody).doesNotContain("solverDetails");
            assertThat(responseBody).doesNotContain("rawNativeStatus");
            assertThat(responseBody).doesNotContain("wallTimeSeconds");
            assertThat(responseBody).doesNotContain("numBooleans");
            assertThat(responseBody).doesNotContain("numBranches");
            assertThat(responseBody).doesNotContain("numConflicts");

            assertThat(rootNode.has("developerCapacity")).isFalse();
            assertThat(rootNode.has("solverDetails")).isFalse();
        }

        @Test
        @DisplayName("PM can also view /deadline-consent with isDesignatedApprover = false")
        void testGetDeadlineConsent_pmCanView() throws Exception {
            UUID pmUser = createUser("pm-view@example.test", "PM View", "ACTIVE", "USER", true);
            UUID clientUser = createUser("client-view@example.test", "Client View", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "View Workspace", "ws-view-1");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJAPP1", "View Project", pmUser, clientUser);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);

            UUID proposalId = insertProposal(
                    projectId, pmUser, "DRAFT", "OPTIMAL", true, LocalDate.of(2027, 1, 15), null, null
            );

            String pmToken = issueAccessToken(pmUser);

            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-consent", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isDesignatedApprover").value(false));
        }

        @Test
        @DisplayName("Non-project member calling /deadline-consent receives 403 ACCESS_DENIED")
        void testGetDeadlineConsent_nonMemberForbidden() throws Exception {
            UUID pmUser = createUser("pm-nm2@example.test", "PM NM2", "ACTIVE", "USER", true);
            UUID outsideUser = createUser("outside2@example.test", "Outside User 2", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "NM2 Workspace", "ws-nm2-1");
            UUID projectId = createProject(workspaceId, "PRJAPP1", "NM2 Project", pmUser, null);

            UUID proposalId = insertProposal(projectId, pmUser, "DRAFT", "OPTIMAL", true, LocalDate.of(2027, 1, 15), null, null);

            String outsideToken = issueAccessToken(outsideUser);

            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-consent", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsideToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    // =========================================================================
    // 4. Client Deadline Confirmation & Author-Approver Independence
    // =========================================================================
    @Nested
    @DisplayName("4. Client Deadline Confirmation & Author-Approver Independence")
    class DeadlineConfirmationTests {

        @Test
        @DisplayName("Designated CLIENT confirms deadline change successfully")
        void testConfirmDeadline_happyPath() throws Exception {
            UUID pmUser = createUser("pm-conf@example.test", "PM Conf", "ACTIVE", "USER", true);
            UUID clientUser = createUser("client-conf@example.test", "Client Conf", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "Conf Workspace", "ws-conf-1");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJAPP1", "Conf Project", pmUser, clientUser);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            // Proposal APPROVED by PM
            UUID proposalId = insertProposal(
                    projectId, pmUser, "APPROVED", "OPTIMAL", true, LocalDate.of(2027, 1, 15), pmUser, now
            );

            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String clientToken = issueAccessToken(clientUser);

            DeadlineConfirmationRequest req = new DeadlineConfirmationRequest(proposalVer, projectVer, true);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-confirmation", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.confirmed").value(true))
                    .andExpect(jsonPath("$.confirmedBy").value(clientUser.toString()))
                    .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

            // Verify in PostgreSQL
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT deadline_confirmed_by, deadline_confirmed_at, deadline_confirmation_version, deadline_confirmed_project_version FROM plan_proposals WHERE id = ?",
                    proposalId
            );
            assertThat(row.get("deadline_confirmed_by")).isEqualTo(clientUser);
            assertThat(row.get("deadline_confirmed_at")).isNotNull();
            // The deadline_confirmation_version should match the newly bumped version
            Long newProposalVer = getProposalVersion(proposalId);
            assertThat(row.get("deadline_confirmation_version")).isEqualTo(newProposalVer);
            assertThat(row.get("deadline_confirmed_project_version")).isEqualTo(projectVer);
        }

        @Test
        @DisplayName("Author-Approver Independence: PM who approved proposal cannot confirm as client (422 INDEPENDENT_CLIENT_REQUIRED)")
        void testAuthorApproverIndependence_pmCannotConfirmOwnDeadline() throws Exception {
            UUID pmUser = createUser("pm-dual@example.test", "PM Dual Role", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "Dual Workspace", "ws-dual-1");
            // PM is also set as deadline_approver_id and given CLIENT role
            UUID projectId = createProject(workspaceId, "PRJAPP1", "Dual Project", pmUser, pmUser);
            addProjectMemberWithRole(projectId, workspaceId, pmUser, "CLIENT", pmUser);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            // Proposal APPROVED by PM
            UUID proposalId = insertProposal(
                    projectId, pmUser, "APPROVED", "OPTIMAL", true, LocalDate.of(2027, 1, 15), pmUser, now
            );

            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String pmToken = issueAccessToken(pmUser);

            DeadlineConfirmationRequest req = new DeadlineConfirmationRequest(proposalVer, projectVer, true);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-confirmation", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INDEPENDENT_CLIENT_REQUIRED"));
        }

        @Test
        @DisplayName("Non-designated client attempting confirmation is rejected (422 INVALID_DEADLINE_APPROVER)")
        void testConfirmDeadline_nonDesignatedClientRejected() throws Exception {
            UUID pmUser = createUser("pm-ndc@example.test", "PM NDC", "ACTIVE", "USER", true);
            UUID designatedClient = createUser("client-desig@example.test", "Designated Client", "ACTIVE", "USER", true);
            UUID otherClient = createUser("client-other@example.test", "Other Client", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "NDC Workspace", "ws-ndc-1");
            addWorkspaceMember(workspaceId, designatedClient, "MEMBER");
            addWorkspaceMember(workspaceId, otherClient, "MEMBER");

            // Designated approver is designatedClient
            UUID projectId = createProject(workspaceId, "PRJAPP1", "NDC Project", pmUser, designatedClient);
            addProjectMemberWithRole(projectId, workspaceId, designatedClient, "CLIENT", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, otherClient, "CLIENT", pmUser);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID proposalId = insertProposal(
                    projectId, pmUser, "APPROVED", "OPTIMAL", true, LocalDate.of(2027, 1, 15), pmUser, now
            );

            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String otherClientToken = issueAccessToken(otherClient);

            DeadlineConfirmationRequest req = new DeadlineConfirmationRequest(proposalVer, projectVer, true);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-confirmation", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherClientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_DEADLINE_APPROVER"));
        }

        @Test
        @DisplayName("Developer role caller is forbidden from deadline confirmation (403 ACCESS_DENIED)")
        void testConfirmDeadline_developerRoleForbidden() throws Exception {
            UUID pmUser = createUser("pm-devconf@example.test", "PM DevConf", "ACTIVE", "USER", true);
            UUID devUser = createUser("dev-conf@example.test", "Dev Conf", "ACTIVE", "USER", true);
            UUID designatedClient = createUser("client-dconf@example.test", "Desig Client", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "DevConf Workspace", "ws-devconf-1");
            addWorkspaceMember(workspaceId, devUser, "MEMBER");
            addWorkspaceMember(workspaceId, designatedClient, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJAPP1", "DevConf Project", pmUser, designatedClient);
            addProjectMemberWithRole(projectId, workspaceId, devUser, "DEVELOPER", pmUser);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID proposalId = insertProposal(
                    projectId, pmUser, "APPROVED", "OPTIMAL", true, LocalDate.of(2027, 1, 15), pmUser, now
            );

            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String devToken = issueAccessToken(devUser);

            DeadlineConfirmationRequest req = new DeadlineConfirmationRequest(proposalVer, projectVer, true);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-confirmation", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("Cannot confirm deadline when proposal is still in DRAFT status (409 PROPOSAL_NOT_APPROVED)")
        void testConfirmDeadline_proposalNotApproved() throws Exception {
            UUID pmUser = createUser("pm-unapp@example.test", "PM Unapp", "ACTIVE", "USER", true);
            UUID clientUser = createUser("client-unapp@example.test", "Client Unapp", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "Unapp Workspace", "ws-unapp-1");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJAPP1", "Unapp Project", pmUser, clientUser);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);

            // Proposal is still in DRAFT
            UUID proposalId = insertProposal(
                    projectId, pmUser, "DRAFT", "OPTIMAL", true, LocalDate.of(2027, 1, 15), null, null
            );

            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String clientToken = issueAccessToken(clientUser);

            DeadlineConfirmationRequest req = new DeadlineConfirmationRequest(proposalVer, projectVer, true);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-confirmation", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_APPROVED"));
        }

        @Test
        @DisplayName("Stale expectedProposalVersion in confirmation throws 409 PROPOSAL_VERSION_CONFLICT")
        void testConfirmDeadline_staleProposalVersion() throws Exception {
            UUID pmUser = createUser("pm-pver@example.test", "PM PVer", "ACTIVE", "USER", true);
            UUID clientUser = createUser("client-pver@example.test", "Client PVer", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "PVer Workspace", "ws-pver-1");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJAPP1", "PVer Project", pmUser, clientUser);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID proposalId = insertProposal(
                    projectId, pmUser, "APPROVED", "OPTIMAL", true, LocalDate.of(2027, 1, 15), pmUser, now
            );

            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String clientToken = issueAccessToken(clientUser);

            // Stale proposal version
            DeadlineConfirmationRequest req = new DeadlineConfirmationRequest(proposalVer + 10, projectVer, true);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-confirmation", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_VERSION_CONFLICT"));
        }

        @Test
        @DisplayName("Stale expectedProjectVersion in confirmation throws 409 PLAN_STALE")
        void testConfirmDeadline_staleProjectVersion() throws Exception {
            UUID pmUser = createUser("pm-pjver@example.test", "PM PjVer", "ACTIVE", "USER", true);
            UUID clientUser = createUser("client-pjver@example.test", "Client PjVer", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(pmUser, "PjVer Workspace", "ws-pjver-1");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJAPP1", "PjVer Project", pmUser, clientUser);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID proposalId = insertProposal(
                    projectId, pmUser, "APPROVED", "OPTIMAL", true, LocalDate.of(2027, 1, 15), pmUser, now
            );

            Long projectVer = getProjectVersion(projectId);
            Long proposalVer = getProposalVersion(proposalId);

            String clientToken = issueAccessToken(clientUser);

            // Stale project version
            DeadlineConfirmationRequest req = new DeadlineConfirmationRequest(proposalVer, projectVer + 10, true);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-confirmation", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_STALE"));
        }
    }
}
