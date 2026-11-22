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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
public class SprintProgressAndClosureIntegrationTest {

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

    private UUID createSprintViaApi(UUID workspaceId, UUID projectId, String token, String name, String goal, LocalDate start, LocalDate end) throws Exception {
        Map<String, Object> req = Map.of(
                "name", name,
                "goal", goal,
                "startDate", start.toString(),
                "endDate", end.toString()
        );
        MvcResult res = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createWorkItemViaApi(UUID workspaceId, UUID projectId, String token, String title, Double storyPoints, Integer estimateMinutes) throws Exception {
        Map<String, Object> req = Map.of(
                "type", "TASK",
                "title", title,
                "storyPoints", storyPoints != null ? storyPoints : 1.0,
                "estimateMinutes", estimateMinutes != null ? estimateMinutes : 60
        );
        MvcResult res = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    // =========================================================================
    // 1. Work Items Assignment Tests & Closed Sprint Guard
    // =========================================================================

    @Nested
    @DisplayName("Work Items Assignment & Closed Sprint Tests")
    class WorkItemAssignmentTests {

        @Test
        @DisplayName("PM can assign and remove backlog items from a PLANNED or ACTIVE sprint")
        void pmCanAssignAndRemoveBacklogItems() throws Exception {
            UUID ownerId = createUser("owner-assign@example.test", "Owner Assign", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-assign@example.test", "PM Assign", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmId);

            UUID workspaceId = createWorkspace(ownerId, "ws-assign", "Assign WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            UUID projectId = createProject(workspaceId, "SPR1", "Sprint 1 Project", pmId);

            UUID sprintId = createSprintViaApi(
                    workspaceId, projectId, pmToken,
                    "Sprint 1", "Initial Goal",
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(14)
            );

            UUID item1 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Item 1", 3.0, 120);
            UUID item2 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Item 2", 5.0, 180);

            // 1. Assign both items to sprint
            Map<String, Object> addReq = Map.of(
                    "action", "ADD",
                    "workItemIds", List.of(item1.toString(), item2.toString())
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(addReq)))
                    .andExpect(status().isOk());

            // Verify both items now point to sprintId
            UUID s1 = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item1);
            UUID s2 = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item2);
            assertThat(s1).isEqualTo(sprintId);
            assertThat(s2).isEqualTo(sprintId);

            // 2. Remove item1 from sprint
            Map<String, Object> removeReq = Map.of(
                    "action", "REMOVE",
                    "workItemIds", List.of(item1.toString())
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(removeReq)))
                    .andExpect(status().isOk());

            // Verify item1 returned to backlog (sprint_id IS NULL), item2 remains
            UUID s1After = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item1);
            UUID s2After = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item2);
            assertThat(s1After).isNull();
            assertThat(s2After).isEqualTo(sprintId);
        }

        @Test
        @DisplayName("Active DEVELOPER role can assign items; non-member is rejected with 403 ACCESS_DENIED")
        void developerCanAssignItemsAndNonMemberForbidden() throws Exception {
            UUID ownerId = createUser("owner-dev-as@example.test", "Owner DevAs", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-dev-as@example.test", "PM DevAs", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-as@example.test", "Dev As", "ACTIVE", "USER", true);
            UUID outsiderId = createUser("outsider@example.test", "Outsider", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            String devToken = issueAccessToken(devId);
            String outsiderToken = issueAccessToken(outsiderId);

            UUID workspaceId = createWorkspace(ownerId, "ws-dev-as", "DevAs WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "SPRDEV", "Dev As Project", pmId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            UUID sprintId = createSprintViaApi(
                    workspaceId, projectId, pmToken,
                    "Dev Sprint", "Dev Sprint Goal",
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(14)
            );

            UUID item = createWorkItemViaApi(workspaceId, projectId, pmToken, "Dev Task", 2.0, 60);

            // Developer can ADD item to sprint
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("action", "ADD", "workItemIds", List.of(item.toString())))))
                    .andExpect(status().isOk());

            // Outsider cannot modify sprint items -> 403 ACCESS_DENIED
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("action", "REMOVE", "workItemIds", List.of(item.toString())))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Assigning items to a CLOSED sprint is rejected with 409 SPRINT_CLOSED")
        void assigningItemsToClosedSprintRejected() throws Exception {
            UUID ownerId = createUser("owner-clsd@example.test", "Owner Closed", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-clsd@example.test", "PM Closed", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmId);

            UUID workspaceId = createWorkspace(ownerId, "ws-clsd", "Closed WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            UUID projectId = createProject(workspaceId, "SPRCLS", "Closed Project", pmId);

            UUID sprintId = createSprintViaApi(
                    workspaceId, projectId, pmToken,
                    "Sprint To Close", "Close Goal",
                    LocalDate.now().minusDays(14), LocalDate.now().minusDays(1)
            );

            // Start sprint
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("expectedVersion", 0))))
                    .andExpect(status().isOk());

            // Close sprint
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/close")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("expectedVersion", 1))))
                    .andExpect(status().isOk());

            UUID item = createWorkItemViaApi(workspaceId, projectId, pmToken, "Late Item", 1.0, 30);

            // Attempt to assign item to CLOSED sprint -> 409 SPRINT_CLOSED
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("action", "ADD", "workItemIds", List.of(item.toString())))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SPRINT_CLOSED"));
        }
    }

    // =========================================================================
    // 2. DONE Item Immutability Tests (ds_work_item_guard)
    // =========================================================================

    @Nested
    @DisplayName("DONE Item Immutability Tests (ds_work_item_guard)")
    class DoneItemImmutabilityTests {

        @Test
        @DisplayName("Moving or removing a DONE item from a sprint is rejected by ds_work_item_guard and API with 409")
        void movingOrRemovingDoneItemRejected() throws Exception {
            UUID ownerId = createUser("owner-done@example.test", "Owner Done", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-done@example.test", "PM Done", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-done@example.test", "Dev Done", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            String devToken = issueAccessToken(devId);

            UUID workspaceId = createWorkspace(ownerId, "ws-done", "Done WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "SPRDN", "Done Project", pmId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            UUID sprintId = createSprintViaApi(
                    workspaceId, projectId, pmToken,
                    "Done Sprint", "Done Sprint Goal",
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(14)
            );

            // Create task assigned to devId
            MvcResult taskRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "type", "TASK",
                                    "title", "Task destined for DONE",
                                    "assigneeId", devId.toString(),
                                    "storyPoints", 3.0
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID taskId = UUID.fromString(json.readTree(taskRes.getResponse().getContentAsString()).get("id").asText());
            Long version = json.readTree(taskRes.getResponse().getContentAsString()).get("version").asLong();

            // Assign item to sprint
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("action", "ADD", "workItemIds", List.of(taskId.toString())))))
                    .andExpect(status().isOk());

            version = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, taskId);

            // Transition item: TO_DO -> IN_PROGRESS -> IN_REVIEW -> DONE
            MvcResult r1 = mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", version))))
                    .andExpect(status().isOk())
                    .andReturn();
            version = json.readTree(r1.getResponse().getContentAsString()).get("version").asLong();

            MvcResult r2 = mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "IN_REVIEW", "expectedVersion", version))))
                    .andExpect(status().isOk())
                    .andReturn();

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"));

            // 1. Attempting to REMOVE the DONE item from the sprint is rejected with 409
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("action", "REMOVE", "workItemIds", List.of(taskId.toString())))))
                    .andExpect(status().isConflict());

            // 2. Direct SQL update attempting to change sprint_id of DONE item triggers ds_work_item_guard() / ds_done_scope()
            assertThatThrownBy(() -> jdbc.update("UPDATE work_items SET sprint_id = NULL WHERE id = ?", taskId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("DONE scope is immutable; create a Change Request");
        }
    }

    // =========================================================================
    // 3. Sprint Closure Snapshot Tests (ds_sprint_close)
    // =========================================================================

    @Nested
    @DisplayName("Sprint Closure Snapshot Tests (ds_sprint_close)")
    class SprintClosureSnapshotTests {

        @Test
        @DisplayName("Closing active sprint triggers snapshot: stamps closed_at, aggregates completed_points, unlinks incomplete items")
        void closingActiveSprintTriggersSnapshot() throws Exception {
            UUID ownerId = createUser("owner-snap@example.test", "Owner Snap", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-snap@example.test", "PM Snap", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-snap@example.test", "Dev Snap", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            String devToken = issueAccessToken(devId);

            UUID workspaceId = createWorkspace(ownerId, "ws-snap", "Snap WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "SPRSNAP", "Snap Project", pmId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            // 1. Create sprint
            UUID sprintId = createSprintViaApi(
                    workspaceId, projectId, pmToken,
                    "Snapshot Sprint", "Test Snapshot Goal",
                    LocalDate.now().minusDays(7), LocalDate.now().plusDays(7)
            );

            // 2. Create 4 items
            // Item 1: 5.0 points, advance to DONE
            UUID item1 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Item 1", 5.0, 120);
            // Item 2: 3.0 points, advance to DONE
            UUID item2 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Item 2", 3.0, 90);
            // Item 3: 8.0 points, advance to IN_PROGRESS
            UUID item3 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Item 3", 8.0, 240);
            // Item 4: 2.0 points, remains in TO_DO
            UUID item4 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Item 4", 2.0, 60);

            // Assign devId to items 1, 2, 3
            jdbc.update("UPDATE work_items SET assignee_id = ? WHERE id IN (?, ?, ?)", devId, item1, item2, item3);

            // Add all 4 items to the sprint
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "action", "ADD",
                                    "workItemIds", List.of(item1.toString(), item2.toString(), item3.toString(), item4.toString())
                            ))))
                    .andExpect(status().isOk());

            // Start sprint (initial committed_points should be 5 + 3 + 8 + 2 = 18.0)
            MvcResult startRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("expectedVersion", 0))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.committedPoints").value(18.0))
                    .andReturn();
            Long sprintVersion = json.readTree(startRes.getResponse().getContentAsString()).get("version").asLong();

            // Advance item 1 to DONE
            Long v1 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item1);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item1 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", v1))));
            v1 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item1);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item1 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_REVIEW", "expectedVersion", v1))));
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item1 + "/accept")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken));

            // Advance item 2 to DONE
            Long v2 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item2);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item2 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", v2))));
            v2 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item2);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item2 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_REVIEW", "expectedVersion", v2))));
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item2 + "/accept")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken));

            // Advance item 3 to IN_PROGRESS
            Long v3 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item3);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item3 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", v3))));

            // Item 4 remains in TO_DO

            // 3. Close the sprint
            Map<String, Object> closeReq = Map.of(
                    "reviewNotes", "Sprint completed 2 items",
                    "retrospectiveNotes", "Good velocity, need to size stories better",
                    "expectedVersion", sprintVersion
            );

            MvcResult closeRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/close")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(closeReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.closedAt").isNotEmpty())
                    .andExpect(jsonPath("$.completedPoints").value(8.0))
                    .andExpect(jsonPath("$.reviewNotes").value("Sprint completed 2 items"))
                    .andExpect(jsonPath("$.retrospectiveNotes").value("Good velocity, need to size stories better"))
                    .andReturn();

            // 4. Verify Database Trigger ds_sprint_close effects:
            // a) Completed points stored in DB is 8.0
            BigDecimal completedPointsDb = jdbc.queryForObject("SELECT completed_points FROM sprints WHERE id = ?", BigDecimal.class, sprintId);
            assertThat(completedPointsDb).isEqualByComparingTo(BigDecimal.valueOf(8.0));

            // b) DONE items (item 1 and item 2) remain linked to sprint
            UUID sItem1 = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item1);
            UUID sItem2 = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item2);
            assertThat(sItem1).isEqualTo(sprintId);
            assertThat(sItem2).isEqualTo(sprintId);

            // c) Incomplete items (item 3 and item 4) are kicked back to backlog (sprint_id IS NULL)
            UUID sItem3 = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item3);
            UUID sItem4 = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item4);
            assertThat(sItem3).isNull();
            assertThat(sItem4).isNull();

            // 5. Closed sprint immutability guard: modifying status, dates or metrics on closed sprint is rejected
            assertThatThrownBy(() -> jdbc.update("UPDATE sprints SET status = 'ACTIVE' WHERE id = ?", sprintId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Closed Sprint metrics and dates are immutable");
        }
    }

    // =========================================================================
    // 4. Sprint Progress & Burndown Data Tests
    // =========================================================================

    @Nested
    @DisplayName("Sprint Progress & Burndown Data Tests")
    class SprintProgressMetricsTests {

        @Test
        @DisplayName("GET .../progress returns accurate aggregation across points, counts, estimates and blocked items")
        void accurateProgressMetricsCalculated() throws Exception {
            UUID ownerId = createUser("owner-prog@example.test", "Owner Prog", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-prog@example.test", "PM Prog", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-prog@example.test", "Dev Prog", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            String devToken = issueAccessToken(devId);

            UUID workspaceId = createWorkspace(ownerId, "ws-prog", "Prog WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "SPRPRG", "Prog Project", pmId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            UUID sprintId = createSprintViaApi(
                    workspaceId, projectId, pmToken,
                    "Progress Sprint", "Progress Goal",
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(14)
            );

            // Create 4 items
            UUID item1 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Done Item", 5.0, 120);
            UUID item2 = createWorkItemViaApi(workspaceId, projectId, pmToken, "In Progress Item", 3.0, 60);
            UUID item3 = createWorkItemViaApi(workspaceId, projectId, pmToken, "Blocked Todo Item", 2.0, 90);
            UUID item4 = createWorkItemViaApi(workspaceId, projectId, pmToken, "In Review Item", 1.0, 45);

            jdbc.update("UPDATE work_items SET assignee_id = ? WHERE id IN (?, ?, ?)", devId, item1, item2, item4);

            // Assign all 4 items to the sprint
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "action", "ADD",
                                    "workItemIds", List.of(item1.toString(), item2.toString(), item3.toString(), item4.toString())
                            ))))
                    .andExpect(status().isOk());

            // Start sprint -> committedPoints = 11.0 (5 + 3 + 2 + 1)
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("expectedVersion", 0))))
                    .andExpect(status().isOk());

            // Advance item 1 to DONE (remaining_minutes = 0, time_spent = 130)
            jdbc.update("UPDATE work_items SET remaining_minutes = 0, time_spent_minutes = 130 WHERE id = ?", item1);
            Long v1 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item1);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item1 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", v1))));
            v1 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item1);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item1 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_REVIEW", "expectedVersion", v1))));
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item1 + "/accept")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken));

            // Advance item 2 to IN_PROGRESS (remaining_minutes = 30, time_spent = 40)
            Long v2 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item2);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item2 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", v2))));
            jdbc.update("UPDATE work_items SET remaining_minutes = 30, time_spent_minutes = 40 WHERE id = ?", item2);

            // Item 3: remains in TO_DO, mark BLOCKED (remaining_minutes = 90, time_spent = 0)
            Long v3 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item3);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item3 + "/blocked")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("isBlocked", true, "blockedReason", "Waiting for API specs", "expectedVersion", v3))));

            // Advance item 4 to IN_REVIEW (remaining_minutes = 10, time_spent = 35)
            Long v4 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item4);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item4 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", v4))));
            v4 = jdbc.queryForObject("SELECT version FROM work_items WHERE id = ?", Long.class, item4);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + item4 + "/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("status", "IN_REVIEW", "expectedVersion", v4))));
            jdbc.update("UPDATE work_items SET remaining_minutes = 10, time_spent_minutes = 35 WHERE id = ?", item4);

            // 2. Query progress endpoint
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + sprintId + "/progress")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sprintId").value(sprintId.toString()))
                    .andExpect(jsonPath("$.sprintName").value("Progress Sprint"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.committedPoints").value(11.0))
                    .andExpect(jsonPath("$.completedPoints").value(5.0))
                    .andExpect(jsonPath("$.remainingPoints").value(6.0))
                    .andExpect(jsonPath("$.totalItems").value(4))
                    .andExpect(jsonPath("$.doneItems").value(1))
                    .andExpect(jsonPath("$.inProgressItems").value(1))
                    .andExpect(jsonPath("$.inReviewItems").value(1))
                    .andExpect(jsonPath("$.toDoItems").value(1))
                    .andExpect(jsonPath("$.totalEstimateMinutes").value(315)) // 120 + 60 + 90 + 45
                    .andExpect(jsonPath("$.totalRemainingMinutes").value(130)) // 0 + 30 + 90 + 10
                    .andExpect(jsonPath("$.totalSpentMinutes").value(205)) // 130 + 40 + 0 + 35
                    .andExpect(jsonPath("$.blockedCount").value(1));
        }
    }
}
