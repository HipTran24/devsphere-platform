package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.monitoring.api.dto.RescheduleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
public class RescheduleTriggerIntegrationTest {

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
            return projectId;
        });
    }

    private void addProjectMemberWithRole(UUID projectId, UUID workspaceId, UUID userId, String role, UUID grantedBy) {
        tx.executeWithoutResult(status -> {
            Integer wsExists = jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?", Integer.class, workspaceId, userId);
            if (wsExists == null || wsExists == 0) {
                addWorkspaceMember(workspaceId, userId, "MEMBER");
            }
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

    private UUID createWorkItem(UUID projectId, String title, String status, int estimateMinutes, UUID assigneeId, UUID pmId) {
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
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS', started_at = now() WHERE id = ?", workItemId);
        } else if ("IN_REVIEW".equals(status)) {
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS', started_at = now() WHERE id = ?", workItemId);
            jdbc.update("UPDATE work_items SET status = 'IN_REVIEW' WHERE id = ?", workItemId);
        } else if ("DONE".equals(status)) {
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS', started_at = now() WHERE id = ?", workItemId);
            jdbc.update("UPDATE work_items SET status = 'IN_REVIEW' WHERE id = ?", workItemId);
            jdbc.update("UPDATE work_items SET status = 'DONE', completed_at = now(), accepted_by = ? WHERE id = ?", pmId, workItemId);
        }
        return workItemId;
    }

    private void addDeveloperCapacity(UUID projectId, UUID developerId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO developer_capacity (
                    id, project_id, user_id, minutes_per_workday, allocation_pct,
                    skills, weekly_slots, time_off,
                    effective_from, effective_to, created_at, updated_at, version
                ) VALUES (
                    gen_random_uuid(), ?, ?, 480, 100,
                    '[{"skill":"backend","level":4}]'::jsonb,
                    '[{"dayOfWeek":1,"startMinute":540,"endMinute":1020},
                      {"dayOfWeek":2,"startMinute":540,"endMinute":1020},
                      {"dayOfWeek":3,"startMinute":540,"endMinute":1020},
                      {"dayOfWeek":4,"startMinute":540,"endMinute":1020},
                      {"dayOfWeek":5,"startMinute":540,"endMinute":1020}]'::jsonb,
                    '[]'::jsonb,
                    '2026-11-01', '2027-06-01', ?, ?, 0
                )
                """, projectId, developerId, now, now);
    }

    // =========================================================================
    // Reschedule Trigger Integration Tests
    // =========================================================================

    @Test
    @DisplayName("POST /reschedule creates AUTO_REPLAN proposal in DRAFT status, executes solver via Two-Phase Update, and preserves baseline")
    void rescheduleTriggerCreatesDraftProposalPreservingBaseline() throws Exception {
        UUID ownerId = createUser("owner@example.test", "Owner", "ACTIVE", "USER", true);
        UUID pmId = createUser("pm@example.test", "PM User", "ACTIVE", "USER", true);
        UUID devId = createUser("dev@example.test", "Developer", "ACTIVE", "USER", true);

        UUID workspaceId = createWorkspace(ownerId, "Workspace", "ws-resched-1");
        UUID projectId = createProject(workspaceId, "RES1", "Reschedule Project 1", pmId);
        addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);
        addDeveloperCapacity(projectId, devId);

        // Task 1: DONE in active baseline
        UUID task1Id = createWorkItem(projectId, "Task 1 Done", "DONE", 60, devId, pmId);
        // Task 2: Frozen in active baseline
        UUID task2Id = createWorkItem(projectId, "Task 2 Frozen", "IN_PROGRESS", 120, devId, pmId);
        // Task 3: Unscheduled new task in backlog
        UUID task3Id = createWorkItem(projectId, "Task 3 Open", "TO_DO", 90, null, pmId);

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

        LocalDate committedDeadlineBefore = jdbc.queryForObject("SELECT committed_deadline FROM projects WHERE id = ?", LocalDate.class, projectId);

        // PM triggers reschedule
        String pmToken = issueAccessToken(pmId);

        MvcResult result = mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reschedule", workspaceId, projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalStatus").value("DRAFT"))
                .andExpect(jsonPath("$.totalTasks").value(3))
                .andExpect(jsonPath("$.frozenTasks").value(2))
                .andExpect(jsonPath("$.proposalId").isNotEmpty())
                .andReturn();

        RescheduleResponse response = json.readValue(result.getResponse().getContentAsString(), RescheduleResponse.class);
        UUID proposalId = response.proposalId();

        // 1. Verify proposal persisted in database
        String kind = jdbc.queryForObject("SELECT kind FROM plan_proposals WHERE id = ?", String.class, proposalId);
        String status = jdbc.queryForObject("SELECT status FROM plan_proposals WHERE id = ?", String.class, proposalId);
        String solverStatus = jdbc.queryForObject("SELECT solver_status FROM plan_proposals WHERE id = ?", String.class, proposalId);

        assertThat(kind).isEqualTo("AUTO_REPLAN");
        assertThat(status).isEqualTo("DRAFT");
        // Genuine solver status from CP-SAT preserved via Two-Phase Update (ds_proposal_guard)
        assertThat(solverStatus).isIn("OPTIMAL", "FEASIBLE");

        // 2. Invariant Check: Active schedule version and committed deadline MUST NOT be changed automatically
        UUID activeScheduleAfter = jdbc.queryForObject("SELECT active_schedule_version_id FROM projects WHERE id = ?", UUID.class, projectId);
        LocalDate committedDeadlineAfter = jdbc.queryForObject("SELECT committed_deadline FROM projects WHERE id = ?", LocalDate.class, projectId);

        assertThat(activeScheduleAfter).isEqualTo(initialScheduleVersionId);
        assertThat(committedDeadlineAfter).isEqualTo(committedDeadlineBefore);

        // 3. Verify that schedule_versions count did NOT increase
        Integer scheduleVersionCount = jdbc.queryForObject("SELECT count(*) FROM schedule_versions WHERE project_id = ?", Integer.class, projectId);
        assertThat(scheduleVersionCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Non-PM roles cannot trigger reschedule (403 Forbidden)")
    void nonPmRolesCannotTriggerReschedule() throws Exception {
        UUID ownerId = createUser("owner2@example.test", "Owner 2", "ACTIVE", "USER", true);
        UUID pmId = createUser("pm2@example.test", "PM User 2", "ACTIVE", "USER", true);
        UUID devId = createUser("dev2@example.test", "Developer 2", "ACTIVE", "USER", true);
        UUID clientId = createUser("client2@example.test", "Client 2", "ACTIVE", "USER", true);

        UUID workspaceId = createWorkspace(ownerId, "Workspace 2", "ws-resched-2");
        UUID projectId = createProject(workspaceId, "RES2", "Reschedule Project 2", pmId);
        addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);
        addProjectMemberWithRole(projectId, workspaceId, clientId, "CLIENT", pmId);

        String devToken = issueAccessToken(devId);
        String clientToken = issueAccessToken(clientId);

        // DEVELOPER denied
        mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reschedule", workspaceId, projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                .andExpect(status().isForbidden());

        // CLIENT denied
        mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reschedule", workspaceId, projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                .andExpect(status().isForbidden());

        // Unauthenticated denied
        mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reschedule", workspaceId, projectId))
                .andExpect(status().isUnauthorized());
    }
}
