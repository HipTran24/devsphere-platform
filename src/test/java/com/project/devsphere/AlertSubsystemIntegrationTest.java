package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.monitoring.api.dto.UpdateAlertStatusRequest;
import com.project.devsphere.monitoring.application.AlertService;
import com.project.devsphere.monitoring.domain.Alert;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
public class AlertSubsystemIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @Autowired private AlertService alertService;
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

    private UUID createWorkItem(UUID projectId, String title, String status, int estimateMinutes, UUID assigneeId) {
        UUID workItemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO work_items (
                    id, project_id, type, work_kind, title, status, estimate_minutes, remaining_minutes,
                    assignee_id, created_at, updated_at, version
                ) VALUES (?, ?, 'TASK', 'CODE', ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                workItemId, projectId, title, status, estimateMinutes, estimateMinutes, assigneeId, now, now
        );
        return workItemId;
    }

    // =========================================================================
    // 1. Deduplication & Unique Open Alert Fingerprint Tests
    // =========================================================================
    @Nested
    @DisplayName("Deduplication & Open Alert Fingerprint Tests")
    class DeduplicationTests {

        @Test
        @DisplayName("createOrUpdateAlert updates evidence and impact of open alert without creating duplicate")
        void deduplicatesOpenAlertInService() {
            UUID ownerId = createUser("owner@example.test", "Owner", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm@example.test", "PM User", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "Workspace", "ws-dedup-1");
            UUID projectId = createProject(workspaceId, "DED1", "Dedup Project 1", pmId);
            UUID workItemId = createWorkItem(projectId, "Task 1", "TO_DO", 60, null);

            // First detection
            Alert first = alertService.createOrUpdateAlert(
                    projectId, workItemId, "TASK_BLOCKED", Alert.Severity.MEDIUM,
                    "Task 1 is blocked", Map.of("reason", "waiting on API"), Map.of("delayDays", 2)
            );

            assertThat(first).isNotNull();
            assertThat(first.getStatus()).isEqualTo(Alert.Status.NEW);
            assertThat(first.getSeverity()).isEqualTo(Alert.Severity.MEDIUM);

            // Second detection for the same issue (same projectId, alertCode, workItemId -> same fingerprint)
            Alert second = alertService.createOrUpdateAlert(
                    projectId, workItemId, "TASK_BLOCKED", Alert.Severity.HIGH,
                    "Task 1 is still blocked", Map.of("reason", "waiting on vendor"), Map.of("delayDays", 4)
            );

            // Must update the existing alert in place (same ID)
            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(second.getSeverity()).isEqualTo(Alert.Severity.HIGH); // Escalated
            assertThat(second.getEvidence()).containsEntry("reason", "waiting on vendor");

            // Database count must still be exactly 1
            Integer count = jdbc.queryForObject("SELECT count(*) FROM alerts WHERE project_id = ?", Integer.class, projectId);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("Direct SQL insertion of two open alerts with same fingerprint violates uq_open_alert_fingerprint")
        void databaseEnforcesUqOpenAlertFingerprint() {
            UUID ownerId = createUser("owner2@example.test", "Owner 2", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm2@example.test", "PM User 2", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "Workspace 2", "ws-dedup-2");
            UUID projectId = createProject(workspaceId, "DED2", "Dedup Project 2", pmId);

            String fingerprint = alertService.computeFingerprint(projectId, "TASK_BLOCKED", null);

            // Insert open alert 1 (status = 'NEW')
            jdbc.update("""
                    INSERT INTO alerts (id, project_id, fingerprint, alert_code, severity, status, title, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, ?, 'TASK_BLOCKED', 'MEDIUM', 'NEW', 'Alert 1', now(), now(), 0)
                    """, projectId, fingerprint);

            // Insert open alert 2 with SAME fingerprint (status = 'ACKNOWLEDGED')
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO alerts (id, project_id, fingerprint, alert_code, severity, status, title, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, ?, 'TASK_BLOCKED', 'HIGH', 'ACKNOWLEDGED', 'Alert 2', now(), now(), 0)
                    """, projectId, fingerprint))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_open_alert_fingerprint");
        }
    }

    // =========================================================================
    // 2. Alert Status Lifecycle & Constraints Tests
    // =========================================================================
    @Nested
    @DisplayName("Alert Lifecycle & Constraints Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Valid lifecycle progression: NEW -> ACKNOWLEDGED -> IN_PROGRESS -> RESOLVED")
        void validLifecycleProgression() throws Exception {
            UUID ownerId = createUser("owner3@example.test", "Owner 3", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm3@example.test", "PM User 3", "ACTIVE", "USER", true);
            UUID devId = createUser("dev3@example.test", "Developer 3", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 3", "ws-life-3");
            UUID projectId = createProject(workspaceId, "LIF3", "Life Project 3", pmId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            Alert alert = alertService.createOrUpdateAlert(
                    projectId, null, "SCHEDULE_OVERDUE", Alert.Severity.HIGH,
                    "Schedule overdue", Map.of("overdueHours", 12), Map.of()
            );

            String pmToken = issueAccessToken(pmId);

            // 1. Transition to ACKNOWLEDGED
            UpdateAlertStatusRequest ackReq = new UpdateAlertStatusRequest(Alert.Status.ACKNOWLEDGED, null, 0L);
            mvc.perform(patch("/api/v1/workspaces/{w}/projects/{p}/alerts/{id}/status", workspaceId, projectId, alert.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(ackReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                    .andExpect(jsonPath("$.acknowledgedBy").value(pmId.toString()))
                    .andExpect(jsonPath("$.resolvedAt").isEmpty());

            // 2. Transition to IN_PROGRESS (assign to developer)
            UpdateAlertStatusRequest inProgReq = new UpdateAlertStatusRequest(Alert.Status.IN_PROGRESS, devId, 1L);
            mvc.perform(patch("/api/v1/workspaces/{w}/projects/{p}/alerts/{id}/status", workspaceId, projectId, alert.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(inProgReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.assignedTo").value(devId.toString()));

            // 3. Transition to RESOLVED
            UpdateAlertStatusRequest resReq = new UpdateAlertStatusRequest(Alert.Status.RESOLVED, null, 2L);
            mvc.perform(patch("/api/v1/workspaces/{w}/projects/{p}/alerts/{id}/status", workspaceId, projectId, alert.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(resReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RESOLVED"))
                    .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

            // 4. Attempting to reopen a RESOLVED alert fails with 409 INVALID_ALERT_TRANSITION
            UpdateAlertStatusRequest reopenReq = new UpdateAlertStatusRequest(Alert.Status.NEW, null, 3L);
            mvc.perform(patch("/api/v1/workspaces/{w}/projects/{p}/alerts/{id}/status", workspaceId, projectId, alert.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(reopenReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_ALERT_TRANSITION"));
        }

        @Test
        @DisplayName("Database check constraint enforces (status = 'RESOLVED') = (resolved_at IS NOT NULL)")
        void databaseEnforcesResolvedAtConstraint() {
            UUID ownerId = createUser("owner4@example.test", "Owner 4", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm4@example.test", "PM User 4", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "Workspace 4", "ws-life-4");
            UUID projectId = createProject(workspaceId, "LIF4", "Life Project 4", pmId);

            String fp1 = alertService.computeFingerprint(projectId, "RULE1", null);

            // Attempt to insert status='RESOLVED' with resolved_at = NULL -> VIOLATION
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO alerts (id, project_id, fingerprint, alert_code, severity, status, title, resolved_at, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, ?, 'RULE1', 'LOW', 'RESOLVED', 'Alert', NULL, now(), now(), 0)
                    """, projectId, fp1))
                    .isInstanceOf(DataIntegrityViolationException.class);

            String fp2 = alertService.computeFingerprint(projectId, "RULE2", null);

            // Attempt to insert status='NEW' with resolved_at NOT NULL -> VIOLATION
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO alerts (id, project_id, fingerprint, alert_code, severity, status, title, resolved_at, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, ?, 'RULE2', 'LOW', 'NEW', 'Alert', now(), now(), now(), 0)
                    """, projectId, fp2))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 3. Recurrence Chaining (previous_alert_id) Tests
    // =========================================================================
    @Nested
    @DisplayName("Recurrence Chaining Tests")
    class RecurrenceTests {

        @Test
        @DisplayName("When condition re-occurs after resolve, new alert links previous_alert_id to resolved alert")
        void recurrenceLinksPreviousAlertId() throws Exception {
            UUID ownerId = createUser("owner5@example.test", "Owner 5", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm5@example.test", "PM User 5", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "Workspace 5", "ws-recur-5");
            UUID projectId = createProject(workspaceId, "REC5", "Recur Project 5", pmId);
            UUID workItemId = createWorkItem(projectId, "Task 5", "TO_DO", 120, null);

            // Cycle 1: Create alert and resolve it
            Alert alert1 = alertService.createOrUpdateAlert(
                    projectId, workItemId, "DEADLINE_BREACH_RISK", Alert.Severity.CRITICAL,
                    "Breach risk 1", Map.of("overrunDays", 3), Map.of()
            );

            String pmToken = issueAccessToken(pmId);
            UpdateAlertStatusRequest resReq = new UpdateAlertStatusRequest(Alert.Status.RESOLVED, null, 0L);
            mvc.perform(patch("/api/v1/workspaces/{w}/projects/{p}/alerts/{id}/status", workspaceId, projectId, alert1.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(resReq)))
                    .andExpect(status().isOk());

            // Cycle 2: Same condition occurs again
            Alert alert2 = alertService.createOrUpdateAlert(
                    projectId, workItemId, "DEADLINE_BREACH_RISK", Alert.Severity.CRITICAL,
                    "Breach risk 2", Map.of("overrunDays", 5), Map.of()
            );

            assertThat(alert2.getId()).isNotEqualTo(alert1.getId());
            assertThat(alert2.getPreviousAlertId()).isEqualTo(alert1.getId());
            assertThat(alert2.getStatus()).isEqualTo(Alert.Status.NEW);

            // Verify chained recurrence history endpoint
            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/alerts/{id}/history", workspaceId, projectId, alert2.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value(alert2.getId().toString()))
                    .andExpect(jsonPath("$[1].id").value(alert1.getId().toString()));
        }

        @Test
        @DisplayName("Self-referencing previous_alert_id is rejected by CHECK (previous_alert_id IS DISTINCT FROM id)")
        void selfReferencingAlertIsRejected() {
            UUID ownerId = createUser("owner6@example.test", "Owner 6", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm6@example.test", "PM User 6", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "Workspace 6", "ws-recur-6");
            UUID projectId = createProject(workspaceId, "REC6", "Recur Project 6", pmId);

            UUID alertId = UUID.randomUUID();
            String fp = alertService.computeFingerprint(projectId, "SELF_REF", null);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO alerts (id, project_id, previous_alert_id, fingerprint, alert_code, severity, status, title, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, 'SELF_REF', 'LOW', 'NEW', 'Self ref alert', now(), now(), 0)
                    """, alertId, projectId, alertId, fp))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 4. Tenant Scope Foreign Key Constraints Tests
    // =========================================================================
    @Nested
    @DisplayName("Tenant Isolation & Foreign Key Scope Tests")
    class TenantIsolationTests {

        @Test
        @DisplayName("Cross-project work_item_id is rejected by fk_alerts_work_item_id_scope")
        void crossProjectWorkItemIdIsRejected() {
            UUID ownerId = createUser("owner7@example.test", "Owner 7", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm7@example.test", "PM User 7", "ACTIVE", "USER", true);
            UUID workspaceA = createWorkspace(ownerId, "Workspace 7A", "ws-scope-7a");
            UUID workspaceB = createWorkspace(ownerId, "Workspace 7B", "ws-scope-7b");

            UUID projectA = createProject(workspaceA, "PRJA", "Project A", pmId);
            UUID projectB = createProject(workspaceB, "PRJB", "Project B", pmId);

            // Work item belongs to Project A
            UUID taskInProjA = createWorkItem(projectA, "Task in A", "TO_DO", 60, null);

            String fp = alertService.computeFingerprint(projectB, "CROSS_SCOPE", taskInProjA);

            // Attempt to insert alert in Project B referencing task in Project A
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO alerts (id, project_id, work_item_id, fingerprint, alert_code, severity, status, title, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, ?, ?, 'CROSS_SCOPE', 'LOW', 'NEW', 'Cross alert', now(), now(), 0)
                    """, projectB, taskInProjA, fp))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_alerts_work_item_id_scope");
        }

        @Test
        @DisplayName("Cross-project previous_alert_id is rejected by fk_alerts_previous_alert_id_scope")
        void crossProjectPreviousAlertIdIsRejected() {
            UUID ownerId = createUser("owner8@example.test", "Owner 8", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm8@example.test", "PM User 8", "ACTIVE", "USER", true);
            UUID workspaceA = createWorkspace(ownerId, "Workspace 8A", "ws-scope-8a");
            UUID workspaceB = createWorkspace(ownerId, "Workspace 8B", "ws-scope-8b");

            UUID projectA = createProject(workspaceA, "PRJA2", "Project A2", pmId);
            UUID projectB = createProject(workspaceB, "PRJB2", "Project B2", pmId);

            UUID alertInProjA = UUID.randomUUID();
            String fpA = alertService.computeFingerprint(projectA, "PREV_A", null);
            jdbc.update("""
                    INSERT INTO alerts (id, project_id, fingerprint, alert_code, severity, status, title, created_at, updated_at, version)
                    VALUES (?, ?, ?, 'PREV_A', 'LOW', 'NEW', 'Alert in A', now(), now(), 0)
                    """, alertInProjA, projectA, fpA);

            String fpB = alertService.computeFingerprint(projectB, "PREV_B", null);

            // Attempt to insert alert in Project B referencing previous alert from Project A
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO alerts (id, project_id, previous_alert_id, fingerprint, alert_code, severity, status, title, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, ?, ?, 'PREV_B', 'LOW', 'NEW', 'Alert in B', now(), now(), 0)
                    """, projectB, alertInProjA, fpB))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_alerts_previous_alert_id_scope");
        }
    }

    // =========================================================================
    // 5. Fingerprint Format & Role Authorization Tests
    // =========================================================================
    @Nested
    @DisplayName("Fingerprint Format & Role Authorization Tests")
    class AuthorizationAndFormatTests {

        @Test
        @DisplayName("Fingerprint not matching ^[0-9a-f]{64}$ is rejected by database check constraint")
        void invalidFingerprintFormatRejected() {
            UUID ownerId = createUser("owner9@example.test", "Owner 9", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm9@example.test", "PM User 9", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "Workspace 9", "ws-format-9");
            UUID projectId = createProject(workspaceId, "FMT9", "Format Project 9", pmId);

            // Uppercase hex -> invalid
            String badFingerprint = "A".repeat(64);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO alerts (id, project_id, fingerprint, alert_code, severity, status, title, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, ?, 'RULE', 'LOW', 'NEW', 'Bad FP', now(), now(), 0)
                    """, projectId, badFingerprint))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("CLIENT role cannot list alerts or update alert status (403 Forbidden)")
        void clientRoleCannotAccessAlerts() throws Exception {
            UUID ownerId = createUser("owner10@example.test", "Owner 10", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm10@example.test", "PM User 10", "ACTIVE", "USER", true);
            UUID clientId = createUser("client10@example.test", "Client 10", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "Workspace 10", "ws-auth-10");
            UUID projectId = createProject(workspaceId, "AUT10", "Auth Project 10", pmId);
            addProjectMemberWithRole(projectId, workspaceId, clientId, "CLIENT", pmId);

            Alert alert = alertService.createOrUpdateAlert(
                    projectId, null, "INTERNAL_ALERT", Alert.Severity.LOW,
                    "Internal", Map.of(), Map.of()
            );

            String clientToken = issueAccessToken(clientId);

            // CLIENT cannot list alerts
            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/alerts", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                    .andExpect(status().isForbidden());

            // CLIENT cannot update status
            UpdateAlertStatusRequest req = new UpdateAlertStatusRequest(Alert.Status.ACKNOWLEDGED, null, 0L);
            mvc.perform(patch("/api/v1/workspaces/{w}/projects/{p}/alerts/{id}/status", workspaceId, projectId, alert.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }
    }
}
