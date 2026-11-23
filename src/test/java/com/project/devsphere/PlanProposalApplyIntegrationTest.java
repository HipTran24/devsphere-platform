package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.planning.api.dto.ApplyPlanRequest;
import com.project.devsphere.planning.api.dto.DeadlineConfirmationRequest;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
public class PlanProposalApplyIntegrationTest {

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
                Integer wsExists = jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?", Integer.class, workspaceId, pmUserId);
                if (wsExists == null || wsExists == 0) {
                    addWorkspaceMember(workspaceId, pmUserId, "MEMBER");
                }
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
                Integer wsExists = jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?", Integer.class, workspaceId, deadlineApproverId);
                if (wsExists == null || wsExists == 0) {
                    addWorkspaceMember(workspaceId, deadlineApproverId, "MEMBER");
                }
                UUID clientMemberId = UUID.randomUUID();
                jdbc.update(
                        "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                        clientMemberId, projectId, workspaceId, deadlineApproverId, now, now, now
                );
                jdbc.update(
                        "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                                "VALUES (?, ?, ?, 'CLIENT', ?, ?, ?, ?)",
                        UUID.randomUUID(), projectId, clientMemberId, pmUserId != null ? pmUserId : deadlineApproverId, now, deadlineApproverId, now
                );
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
            Integer wsExists = jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?", Integer.class, workspaceId, userId);
            if (wsExists == null || wsExists == 0) {
                addWorkspaceMember(workspaceId, userId, "MEMBER");
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
            UUID memberId;
            Integer pmExists = jdbc.queryForObject("SELECT count(*) FROM project_members WHERE project_id = ? AND user_id = ?", Integer.class, projectId, userId);
            if (pmExists == null || pmExists == 0) {
                memberId = UUID.randomUUID();
                jdbc.update(
                        "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                        memberId, projectId, workspaceId, userId, now, now, now
                );
            } else {
                memberId = jdbc.queryForObject("SELECT id FROM project_members WHERE project_id = ? AND user_id = ?", UUID.class, projectId, userId);
            }

            Integer roleExists = jdbc.queryForObject("SELECT count(*) FROM project_member_roles WHERE project_id = ? AND project_member_id = ? AND role = ?", Integer.class, projectId, memberId, role);
            if (roleExists == null || roleExists == 0) {
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

    private Long getProposalVersion(UUID proposalId) {
        return jdbc.queryForObject("SELECT version FROM plan_proposals WHERE id = ?", Long.class, proposalId);
    }

    private UUID createWorkItem(UUID projectId, String title, String status, int estimateMinutes, UUID assigneeId) {
        UUID workItemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO work_items (
                    id, project_id, type, work_kind, title, status, estimate_minutes, remaining_minutes,
                    assignee_id, created_at, updated_at, version
                ) VALUES (?, ?, 'TASK', 'CODE', ?, 'TO_DO', ?, ?, ?, ?, ?, 0)
                """,
                workItemId, projectId, title, estimateMinutes, estimateMinutes, assigneeId, now, now
        );
        if ("IN_PROGRESS".equals(status)) {
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS' WHERE id = ?", workItemId);
        } else if ("IN_REVIEW".equals(status)) {
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS' WHERE id = ?", workItemId);
            jdbc.update("UPDATE work_items SET status = 'IN_REVIEW' WHERE id = ?", workItemId);
        } else if ("DONE".equals(status)) {
            UUID pmId = jdbc.queryForObject(
                    "SELECT pm.user_id FROM project_members pm JOIN project_member_roles pmr ON pmr.project_member_id = pm.id WHERE pm.project_id = ? AND pmr.role = 'PM' LIMIT 1",
                    UUID.class, projectId
            );
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS' WHERE id = ?", workItemId);
            jdbc.update("UPDATE work_items SET status = 'IN_REVIEW' WHERE id = ?", workItemId);
            jdbc.update("UPDATE work_items SET status = 'DONE', accepted_by = ?, completed_at = ? WHERE id = ?", pmId, now, workItemId);
        } else if (!"TO_DO".equals(status)) {
            jdbc.update("UPDATE work_items SET status = ? WHERE id = ?", status, workItemId);
        }
        return workItemId;
    }

    private UUID insertProposal(
            UUID projectId,
            UUID creatorId,
            String status,
            String solverStatus,
            String payloadJson,
            boolean changesCommittedDeadline,
            LocalDate proposedCommittedDeadline,
            UUID approvedBy,
            OffsetDateTime approvedAt) {

        UUID proposalId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Long projectVer = getProjectVersion(projectId);

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

    // =========================================================================
    // 1. Apply Without Deadline Change
    // =========================================================================
    @Nested
    @DisplayName("Apply Without Deadline Change")
    class ApplyWithoutDeadlineChangeTests {

        @Test
        @DisplayName("Valid PM applies approved proposal successfully with new and updated tasks")
        void validPmAppliesApprovedProposalSuccessfully() throws Exception {
            UUID ownerId = createUser("owner@example.test", "Owner", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm@example.test", "PM User", "ACTIVE", "USER", true);
            UUID devId = createUser("dev@example.test", "Developer", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace", "ws-apply-1");
            UUID projectId = createProject(workspaceId, "APL1", "Apply Project 1", pmId, null);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            // Create an existing task to be updated
            UUID existingTaskId = createWorkItem(projectId, "Existing Task", "TO_DO", 60, null);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Instant startTask1 = Instant.parse("2026-12-05T09:00:00Z");
            Instant finishTask1 = Instant.parse("2026-12-05T11:00:00Z");
            Instant startTask2 = Instant.parse("2026-12-05T13:00:00Z");
            Instant finishTask2 = Instant.parse("2026-12-05T15:00:00Z");

            String payload = """
                    {
                      "schemaVersion": 1,
                      "items": [
                        {
                          "tempKey": "TASK-EXISTING",
                          "targetId": "%s",
                          "title": "Existing Task",
                          "estimateMinutes": 120,
                          "developerId": "%s",
                          "startAt": "%s",
                          "finishAt": "%s"
                        },
                        {
                          "tempKey": "TASK-NEW",
                          "title": "New Materialized Task",
                          "estimateMinutes": 120,
                          "developerId": "%s",
                          "startAt": "%s",
                          "finishAt": "%s"
                        }
                      ],
                      "dependencies": [
                        {
                          "predecessorKey": "TASK-EXISTING",
                          "successorKey": "TASK-NEW",
                          "lagMinutes": 0
                        }
                      ]
                    }
                    """.formatted(
                    existingTaskId, devId, startTask1, finishTask1,
                    devId, startTask2, finishTask2
            );

            UUID proposalId = insertProposal(
                    projectId, pmId, "APPROVED", "OPTIMAL", payload,
                    false, null, pmId, now
            );

            String pmToken = issueAccessToken(pmId);
            Long expectedProjVer = getProjectVersion(projectId);
            Long expectedPropVer = getProposalVersion(proposalId);

            ApplyPlanRequest request = new ApplyPlanRequest(expectedProjVer, expectedPropVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.proposalId").value(proposalId.toString()))
                    .andExpect(jsonPath("$.scheduleVersionId").isNotEmpty())
                    .andExpect(jsonPath("$.versionNo").value(1))
                    .andExpect(jsonPath("$.createdWorkItems").value(1))
                    .andExpect(jsonPath("$.updatedWorkItems").value(1))
                    .andExpect(jsonPath("$.appliedAt").isNotEmpty());

            // Verify proposal transitioned to APPLIED
            String propStatus = jdbc.queryForObject("SELECT status FROM plan_proposals WHERE id = ?", String.class, proposalId);
            assertThat(propStatus).isEqualTo("APPLIED");

            // Verify project active schedule version id is set
            UUID activeScheduleVersionId = jdbc.queryForObject("SELECT active_schedule_version_id FROM projects WHERE id = ?", UUID.class, projectId);
            assertThat(activeScheduleVersionId).isNotNull();

            // Verify schedule version record exists and is linked
            Integer scheduleVersionNo = jdbc.queryForObject("SELECT version_no FROM schedule_versions WHERE id = ?", Integer.class, activeScheduleVersionId);
            assertThat(scheduleVersionNo).isEqualTo(1);

            // Verify schedule entries materialized
            Integer entryCount = jdbc.queryForObject("SELECT count(*) FROM schedule_entries WHERE schedule_version_id = ?", Integer.class, activeScheduleVersionId);
            assertThat(entryCount).isEqualTo(2);

            // Verify existing task was updated with assignee
            UUID updatedAssignee = jdbc.queryForObject("SELECT assignee_id FROM work_items WHERE id = ?", UUID.class, existingTaskId);
            assertThat(updatedAssignee).isEqualTo(devId);

            // Verify new work item was materialized
            Integer totalTasks = jdbc.queryForObject("SELECT count(*) FROM work_items WHERE project_id = ?", Integer.class, projectId);
            assertThat(totalTasks).isEqualTo(2);

            // Verify dependency was created
            Integer depCount = jdbc.queryForObject("SELECT count(*) FROM work_item_dependencies WHERE project_id = ?", Integer.class, projectId);
            assertThat(depCount).isEqualTo(1);
        }

        @Test
        @DisplayName("Non-PM roles (DEVELOPER, CLIENT, VIEWER) cannot apply proposal")
        void nonPmRolesCannotApplyProposal() throws Exception {
            UUID ownerId = createUser("owner2@example.test", "Owner 2", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm2@example.test", "PM User 2", "ACTIVE", "USER", true);
            UUID devId = createUser("dev2@example.test", "Developer 2", "ACTIVE", "USER", true);
            UUID clientId = createUser("client2@example.test", "Client 2", "ACTIVE", "USER", true);
            UUID viewerId = createUser("viewer2@example.test", "Viewer 2", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 2", "ws-apply-2");
            UUID projectId = createProject(workspaceId, "APL2", "Apply Project 2", pmId, null);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);
            addProjectMemberWithRole(projectId, workspaceId, clientId, "CLIENT", pmId);
            addProjectMemberWithRole(projectId, workspaceId, viewerId, "VIEWER", pmId);

            String payload = """
                    {
                      "schemaVersion": 1,
                      "items": [
                        { "tempKey": "T1", "title": "Task 1", "estimateMinutes": 60 }
                      ]
                    }
                    """;

            UUID proposalId = insertProposal(
                    projectId, pmId, "APPROVED", "OPTIMAL", payload,
                    false, null, pmId, OffsetDateTime.now(ZoneOffset.UTC)
            );

            Long projVer = getProjectVersion(projectId);
            Long propVer = getProposalVersion(proposalId);
            ApplyPlanRequest request = new ApplyPlanRequest(projVer, propVer);

            // DEVELOPER denied
            String devToken = issueAccessToken(devId);
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            // CLIENT denied
            String clientToken = issueAccessToken(clientId);
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            // VIEWER denied
            String viewerToken = issueAccessToken(viewerId);
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            // Unauthenticated denied
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Stale project or proposal version is rejected with 409 Conflict")
        void staleVersionIsRejected() throws Exception {
            UUID ownerId = createUser("owner3@example.test", "Owner 3", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm3@example.test", "PM User 3", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 3", "ws-apply-3");
            UUID projectId = createProject(workspaceId, "APL3", "Apply Project 3", pmId, null);

            String payload = """
                    {
                      "schemaVersion": 1,
                      "items": [
                        { "tempKey": "T1", "title": "Task 1", "estimateMinutes": 60 }
                      ]
                    }
                    """;

            UUID proposalId = insertProposal(
                    projectId, pmId, "APPROVED", "OPTIMAL", payload,
                    false, null, pmId, OffsetDateTime.now(ZoneOffset.UTC)
            );

            String pmToken = issueAccessToken(pmId);
            Long actualProjVer = getProjectVersion(projectId);
            Long actualPropVer = getProposalVersion(proposalId);

            // Wrong project version
            ApplyPlanRequest badProjReq = new ApplyPlanRequest(actualProjVer + 99L, actualPropVer);
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(badProjReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_STALE"));

            // Wrong proposal version
            ApplyPlanRequest badPropReq = new ApplyPlanRequest(actualProjVer, actualPropVer + 99L);
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(badPropReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_VERSION_CONFLICT"));
        }

        @Test
        @DisplayName("Unapproved proposal cannot be applied (fails with 409 PROPOSAL_NOT_APPROVED)")
        void unapprovedProposalCannotBeApplied() throws Exception {
            UUID ownerId = createUser("owner4@example.test", "Owner 4", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm4@example.test", "PM User 4", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 4", "ws-apply-4");
            UUID projectId = createProject(workspaceId, "APL4", "Apply Project 4", pmId, null);

            String payload = """
                    {
                      "schemaVersion": 1,
                      "items": [
                        { "tempKey": "T1", "title": "Task 1", "estimateMinutes": 60 }
                      ]
                    }
                    """;

            // Proposal in DRAFT status
            UUID draftProposalId = insertProposal(
                    projectId, pmId, "DRAFT", "OPTIMAL", payload,
                    false, null, null, null
            );

            String pmToken = issueAccessToken(pmId);
            Long projVer = getProjectVersion(projectId);
            Long propVer = getProposalVersion(draftProposalId);
            ApplyPlanRequest request = new ApplyPlanRequest(projVer, propVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, draftProposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_APPROVED"));
        }
    }

    // =========================================================================
    // 2. Apply With Deadline Change
    // =========================================================================
    @Nested
    @DisplayName("Apply With Deadline Change")
    class ApplyWithDeadlineChangeTests {

        @Test
        @DisplayName("Apply without client confirmation fails with 409 CLIENT_DEADLINE_CONFIRMATION_REQUIRED")
        void applyWithoutClientConfirmationFails() throws Exception {
            UUID ownerId = createUser("owner5@example.test", "Owner 5", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm5@example.test", "PM User 5", "ACTIVE", "USER", true);
            UUID clientId = createUser("client5@example.test", "Client 5", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 5", "ws-apply-5");
            UUID projectId = createProject(workspaceId, "APL5", "Apply Project 5", pmId, clientId);
            addProjectMemberWithRole(projectId, workspaceId, clientId, "CLIENT", pmId);

            String payload = """
                    {
                      "schemaVersion": 1,
                      "items": [
                        { "tempKey": "T1", "title": "Task 1", "estimateMinutes": 60 }
                      ]
                    }
                    """;

            LocalDate newDeadline = LocalDate.parse("2027-01-31");
            UUID proposalId = insertProposal(
                    projectId, pmId, "APPROVED", "OPTIMAL", payload,
                    true, newDeadline, pmId, OffsetDateTime.now(ZoneOffset.UTC)
            );

            String pmToken = issueAccessToken(pmId);
            Long projVer = getProjectVersion(projectId);
            Long propVer = getProposalVersion(proposalId);
            ApplyPlanRequest request = new ApplyPlanRequest(projVer, propVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CLIENT_DEADLINE_CONFIRMATION_REQUIRED"));
        }

        @Test
        @DisplayName("Apply with confirmed client deadline succeeds and updates project committed_deadline")
        void applyWithClientConfirmationSucceeds() throws Exception {
            UUID ownerId = createUser("owner6@example.test", "Owner 6", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm6@example.test", "PM User 6", "ACTIVE", "USER", true);
            UUID clientId = createUser("client6@example.test", "Client 6", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 6", "ws-apply-6");
            UUID projectId = createProject(workspaceId, "APL6", "Apply Project 6", pmId, clientId);
            addProjectMemberWithRole(projectId, workspaceId, clientId, "CLIENT", pmId);

            String payload = """
                    {
                      "schemaVersion": 1,
                      "items": [
                        { "tempKey": "T1", "title": "Task 1", "estimateMinutes": 60 }
                      ]
                    }
                    """;

            LocalDate newDeadline = LocalDate.parse("2027-02-15");
            UUID proposalId = insertProposal(
                    projectId, pmId, "APPROVED", "OPTIMAL", payload,
                    true, newDeadline, pmId, OffsetDateTime.now(ZoneOffset.UTC)
            );

            // Step 1: Client confirms the proposed deadline
            String clientToken = issueAccessToken(clientId);
            Long projVer = getProjectVersion(projectId);
            Long propVer = getProposalVersion(proposalId);

            DeadlineConfirmationRequest confirmReq = new DeadlineConfirmationRequest(propVer, projVer, true);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/deadline-confirmation", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(confirmReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.confirmed").value(true));

            // Step 2: PM applies the proposal
            String pmToken = issueAccessToken(pmId);
            Long latestProjVer = getProjectVersion(projectId);
            Long latestPropVer = getProposalVersion(proposalId);

            ApplyPlanRequest applyReq = new ApplyPlanRequest(latestProjVer, latestPropVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(applyReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.committedDeadline").value("2027-02-15"))
                    .andExpect(jsonPath("$.versionNo").value(1));

            // Verify project committed_deadline was updated
            LocalDate actualDeadline = jdbc.queryForObject("SELECT committed_deadline FROM projects WHERE id = ?", LocalDate.class, projectId);
            assertThat(actualDeadline).isEqualTo(newDeadline);

            // Verify proposal status is APPLIED
            String propStatus = jdbc.queryForObject("SELECT status FROM plan_proposals WHERE id = ?", String.class, proposalId);
            assertThat(propStatus).isEqualTo("APPLIED");
        }
    }

    // =========================================================================
    // 3. Baseline Invariant Checks
    // =========================================================================
    @Nested
    @DisplayName("Baseline Invariant Checks")
    class BaselineInvariantChecksTests {

        @Test
        @DisplayName("Frozen and DONE schedule entries from prior baseline are copied identically into new baseline")
        void frozenAndDoneEntriesPreservedInNewBaseline() throws Exception {
            UUID ownerId = createUser("owner7@example.test", "Owner 7", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm7@example.test", "PM User 7", "ACTIVE", "USER", true);
            UUID devId = createUser("dev7@example.test", "Developer 7", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 7", "ws-apply-7");
            UUID projectId = createProject(workspaceId, "APL7", "Apply Project 7", pmId, null);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            // Create Task 1 (DONE) and Task 2 (frozen = true)
            UUID task1Id = createWorkItem(projectId, "Task 1 Done", "DONE", 60, devId);
            UUID task2Id = createWorkItem(projectId, "Task 2 Frozen", "IN_PROGRESS", 120, devId);

            Instant t1Start = Instant.parse("2026-12-01T08:00:00Z");
            Instant t1Finish = Instant.parse("2026-12-01T09:00:00Z");
            Instant t2Start = Instant.parse("2026-12-01T09:00:00Z");
            Instant t2Finish = Instant.parse("2026-12-01T11:00:00Z");

            // Seed initial baseline version 1
            UUID initialScheduleVersionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO schedule_versions (id, project_id, version_no, source, approved_by, approved_at, planned_finish, forecast_finish, milestone_delays, baseline_snapshot, created_at)
                    VALUES (?, ?, 1, 'MANUAL', ?, now(), '2026-12-31', '2026-12-31', '{"schemaVersion":1}'::jsonb, '{"schemaVersion":1}'::jsonb, now())
                    """,
                    initialScheduleVersionId, projectId, pmId
            );

            jdbc.update("""
                    INSERT INTO schedule_entries (id, project_id, schedule_version_id, work_item_id, assignee_id, start_at, finish_at, remaining_minutes_snapshot, frozen, created_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 0, false, now()),
                           (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 120, true, now())
                    """,
                    projectId, initialScheduleVersionId, task1Id, devId, Timestamp.from(t1Start), Timestamp.from(t1Finish),
                    projectId, initialScheduleVersionId, task2Id, devId, Timestamp.from(t2Start), Timestamp.from(t2Finish)
            );

            // Set active schedule version to initial baseline
            jdbc.update("UPDATE projects SET active_schedule_version_id = ? WHERE id = ?", initialScheduleVersionId, projectId);

            // Proposal introduces Task 3 scheduled after Task 2
            Instant t3Start = Instant.parse("2026-12-01T11:00:00Z");
            Instant t3Finish = Instant.parse("2026-12-01T13:00:00Z");

            String payload = """
                    {
                      "schemaVersion": 1,
                      "items": [
                        {
                          "tempKey": "TASK-3",
                          "title": "Task 3 New",
                          "estimateMinutes": 120,
                          "developerId": "%s",
                          "startAt": "%s",
                          "finishAt": "%s"
                        }
                      ]
                    }
                    """.formatted(devId, t3Start, t3Finish);

            UUID proposalId = insertProposal(
                    projectId, pmId, "APPROVED", "OPTIMAL", payload,
                    false, null, pmId, OffsetDateTime.now(ZoneOffset.UTC)
            );

            String pmToken = issueAccessToken(pmId);
            Long projVer = getProjectVersion(projectId);
            Long propVer = getProposalVersion(proposalId);
            ApplyPlanRequest request = new ApplyPlanRequest(projVer, propVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.versionNo").value(2));

            UUID newScheduleVersionId = jdbc.queryForObject("SELECT active_schedule_version_id FROM projects WHERE id = ?", UUID.class, projectId);
            assertThat(newScheduleVersionId).isNotNull().isNotEqualTo(initialScheduleVersionId);

            // Verify that new baseline contains all 3 entries
            Integer newEntryCount = jdbc.queryForObject("SELECT count(*) FROM schedule_entries WHERE schedule_version_id = ?", Integer.class, newScheduleVersionId);
            assertThat(newEntryCount).isEqualTo(3);

            // Verify task 1 (DONE) was copied with exact same start/finish
            Instant copiedT1Start = jdbc.queryForObject(
                    "SELECT start_at FROM schedule_entries WHERE schedule_version_id = ? AND work_item_id = ?",
                    Timestamp.class, newScheduleVersionId, task1Id
            ).toInstant();
            assertThat(copiedT1Start).isEqualTo(t1Start);

            // Verify task 2 (frozen) was copied with frozen=true and exact start/finish
            Boolean copiedT2Frozen = jdbc.queryForObject(
                    "SELECT frozen FROM schedule_entries WHERE schedule_version_id = ? AND work_item_id = ?",
                    Boolean.class, newScheduleVersionId, task2Id
            );
            assertThat(copiedT2Frozen).isTrue();
        }

        @Test
        @DisplayName("Overlapping schedule entries for same assignee are rejected by ex_schedule_assignee_overlap constraint")
        void assigneeOverlapConstraintIsEnforcedByDatabase() {
            UUID ownerId = createUser("owner8@example.test", "Owner 8", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm8@example.test", "PM User 8", "ACTIVE", "USER", true);
            UUID devId = createUser("dev8@example.test", "Developer 8", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 8", "ws-apply-8");
            UUID projectId = createProject(workspaceId, "APL8", "Apply Project 8", pmId, null);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            UUID task1Id = createWorkItem(projectId, "Task 1", "TO_DO", 60, devId);
            UUID task2Id = createWorkItem(projectId, "Task 2", "TO_DO", 60, devId);

            UUID scheduleVersionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO schedule_versions (id, project_id, version_no, source, approved_by, approved_at, planned_finish, forecast_finish, milestone_delays, baseline_snapshot, created_at)
                    VALUES (?, ?, 1, 'MANUAL', ?, now(), '2026-12-31', '2026-12-31', '{"schemaVersion":1}'::jsonb, '{"schemaVersion":1}'::jsonb, now())
                    """,
                    scheduleVersionId, projectId, pmId
            );

            Instant start1 = Instant.parse("2026-12-10T09:00:00Z");
            Instant finish1 = Instant.parse("2026-12-10T11:00:00Z");

            // Insert entry 1: 09:00 to 11:00
            jdbc.update("""
                    INSERT INTO schedule_entries (id, project_id, schedule_version_id, work_item_id, assignee_id, start_at, finish_at, remaining_minutes_snapshot, frozen, created_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 60, false, now())
                    """,
                    projectId, scheduleVersionId, task1Id, devId, Timestamp.from(start1), Timestamp.from(finish1)
            );

            // Attempt to insert entry 2 overlapping: 10:00 to 12:00 for the SAME developer
            Instant start2 = Instant.parse("2026-12-10T10:00:00Z");
            Instant finish2 = Instant.parse("2026-12-10T12:00:00Z");

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO schedule_entries (id, project_id, schedule_version_id, work_item_id, assignee_id, start_at, finish_at, remaining_minutes_snapshot, frozen, created_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 60, false, now())
                    """,
                    projectId, scheduleVersionId, task2Id, devId, Timestamp.from(start2), Timestamp.from(finish2)
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ex_schedule_assignee_overlap");
        }
    }

    // =========================================================================
    // 4. Idempotency & Reapplication
    // =========================================================================
    @Nested
    @DisplayName("Idempotency & Reapplication")
    class IdempotencyAndReapplicationTests {

        @Test
        @DisplayName("Reapplying an already APPLIED proposal is rejected with 409 PROPOSAL_ALREADY_APPLIED")
        void alreadyAppliedProposalCannotBeReapplied() throws Exception {
            UUID ownerId = createUser("owner9@example.test", "Owner 9", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm9@example.test", "PM User 9", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 9", "ws-apply-9");
            UUID projectId = createProject(workspaceId, "APL9", "Apply Project 9", pmId, null);

            String payload = """
                    {
                      "schemaVersion": 1,
                      "items": [
                        { "tempKey": "T1", "title": "Task 1", "estimateMinutes": 60 }
                      ]
                    }
                    """;

            UUID proposalId = insertProposal(
                    projectId, pmId, "APPROVED", "OPTIMAL", payload,
                    false, null, pmId, OffsetDateTime.now(ZoneOffset.UTC)
            );

            String pmToken = issueAccessToken(pmId);
            Long projVer = getProjectVersion(projectId);
            Long propVer = getProposalVersion(proposalId);
            ApplyPlanRequest request = new ApplyPlanRequest(projVer, propVer);

            // First apply: 200 OK
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk());

            // Second apply attempt: 409 PROPOSAL_ALREADY_APPLIED
            Long nextProjVer = getProjectVersion(projectId);
            Long nextPropVer = getProposalVersion(proposalId);
            ApplyPlanRequest retryRequest = new ApplyPlanRequest(nextProjVer, nextPropVer);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/plan-proposals/{id}/apply", workspaceId, projectId, proposalId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(retryRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_ALREADY_APPLIED"));
        }

        @Test
        @DisplayName("Direct database update on APPLIED proposal is rejected by trigger ds_proposal_guard")
        void appliedProposalIsImmutableInDatabase() {
            UUID ownerId = createUser("owner10@example.test", "Owner 10", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm10@example.test", "PM User 10", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 10", "ws-apply-10");
            UUID projectId = createProject(workspaceId, "APL10", "Apply Project 10", pmId, null);

            String payload = """
                    {
                      "schemaVersion": 1,
                      "items": [
                        { "tempKey": "T1", "title": "Task 1", "estimateMinutes": 60 }
                      ]
                    }
                    """;

            UUID proposalId = insertProposal(
                    projectId, pmId, "APPROVED", "OPTIMAL", payload,
                    false, null, pmId, OffsetDateTime.now(ZoneOffset.UTC)
            );

            // Mark proposal as APPLIED directly
            jdbc.update("UPDATE plan_proposals SET status = 'APPLIED' WHERE id = ?", proposalId);

            // Any subsequent UPDATE on the APPLIED proposal must be rejected by ds_proposal_guard()
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE plan_proposals SET status = 'DRAFT' WHERE id = ?", proposalId
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Applied proposal is immutable");
        }
    }
}
