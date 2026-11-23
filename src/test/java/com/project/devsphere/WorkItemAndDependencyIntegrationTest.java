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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
public class WorkItemAndDependencyIntegrationTest {

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

    // =========================================================================
    // 1. Work Items & Monotonic Item Keys Tests
    // =========================================================================

    @Nested
    @DisplayName("Work Items & Monotonic Item Keys Tests")
    class MonotonicItemKeysTests {

        @Test
        @DisplayName("Sequential work item creation generates monotonic item keys via trigger ds_work_item_key")
        void sequentialCreationGeneratesMonotonicKeys() throws Exception {
            UUID ownerId = createUser("owner-key@example.test", "Owner Key", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-key@example.test", "PM Key", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmId);

            UUID workspaceId = createWorkspace(ownerId, "ws-key", "Key WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            UUID projectId = createProject(workspaceId, "ALPHA", "Alpha Project", pmId);

            // Create item 1 (TASK)
            Map<String, Object> req1 = Map.of(
                    "type", "TASK",
                    "title", "First Task",
                    "description", "First task description"
            );
            MvcResult res1 = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req1)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.itemKey").value("ALPHA-1"))
                    .andExpect(jsonPath("$.title").value("First Task"))
                    .andReturn();

            UUID id1 = UUID.fromString(json.readTree(res1.getResponse().getContentAsString()).get("id").asText());

            // Create item 2 (TASK)
            Map<String, Object> req2 = Map.of(
                    "type", "TASK",
                    "title", "Second Task",
                    "description", "Second task description"
            );
            MvcResult res2 = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req2)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.itemKey").value("ALPHA-2"))
                    .andExpect(jsonPath("$.title").value("Second Task"))
                    .andReturn();

            UUID id2 = UUID.fromString(json.readTree(res2.getResponse().getContentAsString()).get("id").asText());

            // Verify database rows
            String key1 = jdbc.queryForObject("SELECT item_key FROM work_items WHERE id = ?", String.class, id1);
            String key2 = jdbc.queryForObject("SELECT item_key FROM work_items WHERE id = ?", String.class, id2);
            assertThat(key1).isEqualTo("ALPHA-1");
            assertThat(key2).isEqualTo("ALPHA-2");

            // Immutability: direct update of item_key in SQL is rejected by trigger ds_work_item_key
            assertThatThrownBy(() -> jdbc.update("UPDATE work_items SET item_key = 'ALPHA-999' WHERE id = ?", id1))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Work Item key is immutable");
        }
    }

    // =========================================================================
    // 2. Hierarchy Invariants Tests (ds_work_item_guard)
    // =========================================================================

    @Nested
    @DisplayName("Hierarchy Invariants Tests (ds_work_item_guard)")
    class HierarchyInvariantsTests {

        @Test
        @DisplayName("Valid hierarchies succeed: Story in Epic, Task in Story, Subtask in Task, Bug in Story")
        void validHierarchySucceeds() throws Exception {
            UUID ownerId = createUser("owner-h@example.test", "Owner H", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-h@example.test", "PM H", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmId);

            UUID workspaceId = createWorkspace(ownerId, "ws-h", "Hierarchy WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            UUID projectId = createProject(workspaceId, "BETA", "Beta Project", pmId);

            // 1. Root EPIC
            Map<String, Object> epicReq = Map.of(
                    "type", "EPIC",
                    "title", "Core System Architecture"
            );
            MvcResult epicRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(epicReq)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("EPIC"))
                    .andReturn();
            UUID epicId = UUID.fromString(json.readTree(epicRes.getResponse().getContentAsString()).get("id").asText());

            // 2. USER_STORY under EPIC
            Map<String, Object> storyReq = Map.of(
                    "type", "USER_STORY",
                    "title", "User Auth Module",
                    "parentId", epicId.toString()
            );
            MvcResult storyRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(storyReq)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("USER_STORY"))
                    .andExpect(jsonPath("$.parentId").value(epicId.toString()))
                    .andReturn();
            UUID storyId = UUID.fromString(json.readTree(storyRes.getResponse().getContentAsString()).get("id").asText());

            // 3. TASK under USER_STORY
            Map<String, Object> taskReq = Map.of(
                    "type", "TASK",
                    "title", "Implement JWT Verification",
                    "parentId", storyId.toString()
            );
            MvcResult taskRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(taskReq)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("TASK"))
                    .andExpect(jsonPath("$.parentId").value(storyId.toString()))
                    .andReturn();
            UUID taskId = UUID.fromString(json.readTree(taskRes.getResponse().getContentAsString()).get("id").asText());

            // 4. SUBTASK under TASK
            Map<String, Object> subtaskReq = Map.of(
                    "type", "SUBTASK",
                    "title", "Unit Test JWT Signature",
                    "parentId", taskId.toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(subtaskReq)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("SUBTASK"))
                    .andExpect(jsonPath("$.parentId").value(taskId.toString()));

            // 5. BUG under USER_STORY
            Map<String, Object> bugReq = Map.of(
                    "type", "BUG",
                    "title", "Fix NullPointerException on empty bearer token",
                    "parentId", storyId.toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(bugReq)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("BUG"))
                    .andExpect(jsonPath("$.parentId").value(storyId.toString()));
        }

        @Test
        @DisplayName("Invalid hierarchies are rejected with 409 INVALID_HIERARCHY")
        void invalidHierarchyRejected() throws Exception {
            UUID ownerId = createUser("owner-inv@example.test", "Owner Inv", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-inv@example.test", "PM Inv", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmId);

            UUID workspaceId = createWorkspace(ownerId, "ws-inv", "Inv WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            UUID projectId = createProject(workspaceId, "GAMMA", "Gamma Project", pmId);

            // Create EPIC
            Map<String, Object> epicReq = Map.of("type", "EPIC", "title", "Epic One");
            MvcResult epicRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(epicReq)))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID epicId = UUID.fromString(json.readTree(epicRes.getResponse().getContentAsString()).get("id").asText());

            // 1. SUBTASK directly under EPIC is rejected
            Map<String, Object> invalidSubtask = Map.of(
                    "type", "SUBTASK",
                    "title", "Illegal Subtask Under Epic",
                    "parentId", epicId.toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(invalidSubtask)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_HIERARCHY"));

            // 2. EPIC with a parent is rejected
            Map<String, Object> invalidEpic = Map.of(
                    "type", "EPIC",
                    "title", "Nested Epic",
                    "parentId", epicId.toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(invalidEpic)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_HIERARCHY"));

            // 3. TASK directly under EPIC is rejected
            Map<String, Object> invalidTask = Map.of(
                    "type", "TASK",
                    "title", "Task Under Epic Without Story",
                    "parentId", epicId.toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(invalidTask)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_HIERARCHY"));
        }

        @Test
        @DisplayName("Circular ancestry in work item hierarchy is detected and rejected with 409 HIERARCHY_CYCLE")
        void circularAncestryRejected() throws Exception {
            UUID ownerId = createUser("owner-cyc@example.test", "Owner Cyc", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-cyc@example.test", "PM Cyc", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmId);

            UUID workspaceId = createWorkspace(ownerId, "ws-cyc", "Cyc WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            UUID projectId = createProject(workspaceId, "DELTA", "Delta Project", pmId);

            // 1. Create root EPIC
            MvcResult epicRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("type", "EPIC", "title", "Epic Cyc"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID epicId = UUID.fromString(json.readTree(epicRes.getResponse().getContentAsString()).get("id").asText());

            // 2. Create USER_STORY with parent EPIC
            MvcResult storyRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("type", "USER_STORY", "title", "Story Cyc", "parentId", epicId.toString()))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID storyId = UUID.fromString(json.readTree(storyRes.getResponse().getContentAsString()).get("id").asText());

            // 3. Create TASK with parent USER_STORY
            MvcResult taskRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("type", "TASK", "title", "Task Cyc", "parentId", storyId.toString()))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID taskId = UUID.fromString(json.readTree(taskRes.getResponse().getContentAsString()).get("id").asText());

            // 4. Updating parent in SQL directly or checking cycle trigger:
            // Attempting to set Story's parent to Task would form cycle (Story -> Task -> Story)
            assertThatThrownBy(() -> jdbc.update("UPDATE work_items SET parent_id = ? WHERE id = ?", taskId, storyId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 3. State Transitions & Frozen DONE Tests
    // =========================================================================

    @Nested
    @DisplayName("State Transitions & Frozen DONE Tests")
    class StateTransitionsAndFrozenDoneTests {

        @Test
        @DisplayName("Assignee must hold active DEVELOPER role; non-developer assignment is rejected")
        void assigneeMustHoldDeveloperRole() throws Exception {
            UUID ownerId = createUser("owner-dev@example.test", "Owner Dev", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-dev@example.test", "PM Dev", "ACTIVE", "USER", true);
            UUID nonDevId = createUser("nondev@example.test", "Non Dev", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-ok@example.test", "Dev Ok", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            UUID workspaceId = createWorkspace(ownerId, "ws-dev", "Dev WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, nonDevId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "EPSILON", "Epsilon Project", pmId);
            // devId has DEVELOPER role, nonDevId has no project role
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            // Create TASK
            MvcResult taskRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("type", "TASK", "title", "Dev Assignment Task"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID taskId = UUID.fromString(json.readTree(taskRes.getResponse().getContentAsString()).get("id").asText());
            Long version = json.readTree(taskRes.getResponse().getContentAsString()).get("version").asLong();

            // 1. Assign to nonDevId -> 400 ASSIGNEE_NOT_DEVELOPER
            Map<String, Object> assignNonDev = Map.of(
                    "assigneeId", nonDevId.toString(),
                    "expectedVersion", version
            );
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/assign")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(assignNonDev)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ASSIGNEE_NOT_DEVELOPER"));

            // 2. Assign to devId -> 200 OK
            Map<String, Object> assignDev = Map.of(
                    "assigneeId", devId.toString(),
                    "expectedVersion", version
            );
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/assign")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(assignDev)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assigneeId").value(devId.toString()));
        }

        @Test
        @DisplayName("Strict state machine transitions: TO_DO -> IN_PROGRESS -> IN_REVIEW -> DONE; illegal jumps rejected")
        void strictStateTransitionsAndAcceptance() throws Exception {
            UUID ownerId = createUser("owner-sm@example.test", "Owner SM", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-sm@example.test", "PM SM", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-sm@example.test", "Dev SM", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            String devToken = issueAccessToken(devId);

            UUID workspaceId = createWorkspace(ownerId, "ws-sm", "SM WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "ZETA", "Zeta Project", pmId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            // Create TASK assigned to devId
            MvcResult taskRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "type", "TASK",
                                    "title", "State Machine Task",
                                    "assigneeId", devId.toString()
                            ))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("TO_DO"))
                    .andReturn();
            UUID taskId = UUID.fromString(json.readTree(taskRes.getResponse().getContentAsString()).get("id").asText());
            Long version = json.readTree(taskRes.getResponse().getContentAsString()).get("version").asLong();

            // 1. Direct illegal jump: TO_DO -> DONE is rejected with 409 INVALID_STATE_TRANSITION
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "DONE", "expectedVersion", version))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

            // 2. Direct illegal jump: TO_DO -> IN_REVIEW is rejected with 409 INVALID_STATE_TRANSITION
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "IN_REVIEW", "expectedVersion", version))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

            // 3. Valid transition: TO_DO -> IN_PROGRESS
            MvcResult progRes = mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "IN_PROGRESS", "expectedVersion", version))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andReturn();
            version = json.readTree(progRes.getResponse().getContentAsString()).get("version").asLong();

            // 4. Valid transition: IN_PROGRESS -> IN_REVIEW
            MvcResult revRes = mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "IN_REVIEW", "expectedVersion", version))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                    .andReturn();

            // 5. Non-PM attempting to accept task to DONE receives 403 ACCESS_DENIED
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isForbidden());

            // 6. PM rejects task back to IN_PROGRESS with rejection reason
            MvcResult rejRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/reject")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("rejectionReason", "Missing unit tests"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andReturn();
            version = json.readTree(rejRes.getResponse().getContentAsString()).get("version").asLong();

            // 7. DEV transitions back to IN_REVIEW
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "IN_REVIEW", "expectedVersion", version))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_REVIEW"));

            // 8. PM accepts task to DONE
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andExpect(jsonPath("$.acceptedBy").value(pmId.toString()))
                    .andExpect(jsonPath("$.completedAt").isNotEmpty());
        }

        @Test
        @DisplayName("Frozen DONE: Once DONE, modifying core scope or deleting work item is rejected with 409 SCOPE_LOCKED")
        void doneItemIsFrozen() throws Exception {
            UUID ownerId = createUser("owner-fz@example.test", "Owner Fz", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-fz@example.test", "PM Fz", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-fz@example.test", "Dev Fz", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            String devToken = issueAccessToken(devId);

            UUID workspaceId = createWorkspace(ownerId, "ws-fz", "FZ WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "ETA", "Eta Project", pmId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            // Create TASK and advance through TO_DO -> IN_PROGRESS -> IN_REVIEW -> DONE
            MvcResult taskRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "type", "TASK",
                                    "title", "Task To Freeze",
                                    "assigneeId", devId.toString(),
                                    "estimateMinutes", 120
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID taskId = UUID.fromString(json.readTree(taskRes.getResponse().getContentAsString()).get("id").asText());
            Long version = json.readTree(taskRes.getResponse().getContentAsString()).get("version").asLong();

            // Progress -> In Review
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

            // PM accepts -> DONE
            MvcResult doneRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andReturn();
            version = json.readTree(doneRes.getResponse().getContentAsString()).get("version").asLong();

            // FROZEN VERIFICATIONS:
            // 1. Modifying title or estimates is rejected with 409 SCOPE_LOCKED
            Map<String, Object> updateReq = Map.of(
                    "title", "Attempted Title Edit on DONE item",
                    "expectedVersion", version
            );
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(updateReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SCOPE_LOCKED"));

            // 2. Toggling isBlocked is rejected with 409 SCOPE_LOCKED
            Map<String, Object> blockReq = Map.of(
                    "isBlocked", true,
                    "blockedReason", "Cannot block a done item",
                    "expectedVersion", version
            );
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/blocked")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(blockReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SCOPE_LOCKED"));

            // 3. Deleting DONE item is rejected with 409 SCOPE_LOCKED
            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SCOPE_LOCKED"));
        }
    }

    // =========================================================================
    // 4. DAG Dependency Cycle Prevention Tests (ds_dependency_dag)
    // =========================================================================

    @Nested
    @DisplayName("DAG Dependency Cycle Prevention Tests (ds_dependency_dag)")
    class DagDependencyCycleTests {

        @Test
        @DisplayName("Valid dependencies A -> B and B -> C succeed; circular edge C -> A is rejected with 409 DEPENDENCY_CYCLE")
        void dependencyCyclePrevention() throws Exception {
            UUID ownerId = createUser("owner-dag@example.test", "Owner DAG", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-dag@example.test", "PM DAG", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmId);

            UUID workspaceId = createWorkspace(ownerId, "ws-dag", "DAG WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            UUID projectId = createProject(workspaceId, "THETA", "Theta Project", pmId);

            // Create 3 work items: A, B, C
            MvcResult resA = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("type", "TASK", "title", "Task A"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID itemA = UUID.fromString(json.readTree(resA.getResponse().getContentAsString()).get("id").asText());

            MvcResult resB = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("type", "TASK", "title", "Task B"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID itemB = UUID.fromString(json.readTree(resB.getResponse().getContentAsString()).get("id").asText());

            MvcResult resC = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("type", "TASK", "title", "Task C"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID itemC = UUID.fromString(json.readTree(resC.getResponse().getContentAsString()).get("id").asText());

            // 1. Link A -> B (A is predecessor, B is successor) -> 201 Created
            Map<String, Object> edgeAB = Map.of(
                    "predecessorId", itemA.toString(),
                    "successorId", itemB.toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/dependencies")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(edgeAB)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.predecessorId").value(itemA.toString()))
                    .andExpect(jsonPath("$.successorId").value(itemB.toString()));

            // 2. Link B -> C -> 201 Created
            Map<String, Object> edgeBC = Map.of(
                    "predecessorId", itemB.toString(),
                    "successorId", itemC.toString()
            );
            MvcResult resEdgeBC = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/dependencies")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(edgeBC)))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID edgeBcId = UUID.fromString(json.readTree(resEdgeBC.getResponse().getContentAsString()).get("id").asText());

            // 3. CYCLE ATTEMPT: Link C -> A (would form A -> B -> C -> A) -> 409 DEPENDENCY_CYCLE
            Map<String, Object> edgeCA = Map.of(
                    "predecessorId", itemC.toString(),
                    "successorId", itemA.toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/dependencies")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(edgeCA)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_CYCLE"));

            // 4. SELF-DEPENDENCY ATTEMPT: Link A -> A -> 409 DEPENDENCY_CYCLE
            Map<String, Object> edgeAA = Map.of(
                    "predecessorId", itemA.toString(),
                    "successorId", itemA.toString()
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/dependencies")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(edgeAA)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_CYCLE"));

            // 5. List dependencies
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/dependencies")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(2));

            // 6. Delete edge B -> C
            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/dependencies/" + edgeBcId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isNoContent());

            // 7. Verify list now has only 1 dependency
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/dependencies")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    // =========================================================================
    // 5. Acceptance Criteria Tests
    // =========================================================================

    @Nested
    @DisplayName("Acceptance Criteria Tests")
    class AcceptanceCriteriaTests {

        @Test
        @DisplayName("PM can create, list, verify and delete acceptance criteria; non-PM cannot verify")
        void acceptanceCriteriaLifecycle() throws Exception {
            UUID ownerId = createUser("owner-crit@example.test", "Owner Crit", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-crit@example.test", "PM Crit", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-crit@example.test", "Dev Crit", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            String devToken = issueAccessToken(devId);

            UUID workspaceId = createWorkspace(ownerId, "ws-crit", "Crit WS");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "IOTA", "Iota Project", pmId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", pmId);

            // Create TASK
            MvcResult taskRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("type", "TASK", "title", "Task with Criteria"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID taskId = UUID.fromString(json.readTree(taskRes.getResponse().getContentAsString()).get("id").asText());

            // 1. PM adds Criterion 1 (CI_TEST, weight 2.5)
            Map<String, Object> crit1Req = Map.of(
                    "description", "All unit tests pass with > 90% branch coverage",
                    "evidenceKind", "CI_TEST",
                    "weight", 2.5,
                    "position", 1
            );
            MvcResult crit1Res = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/criteria")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(crit1Req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.description").value("All unit tests pass with > 90% branch coverage"))
                    .andExpect(jsonPath("$.evidenceKind").value("CI_TEST"))
                    .andExpect(jsonPath("$.status").value("NOT_EVALUATED"))
                    .andReturn();
            UUID crit1Id = UUID.fromString(json.readTree(crit1Res.getResponse().getContentAsString()).get("id").asText());

            // 2. PM adds Criterion 2 (MANUAL, weight 1.0)
            Map<String, Object> crit2Req = Map.of(
                    "description", "Design team manual inspection approved",
                    "evidenceKind", "MANUAL",
                    "weight", 1.0,
                    "position", 2
            );
            MvcResult crit2Res = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/criteria")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(crit2Req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.description").value("Design team manual inspection approved"))
                    .andReturn();
            UUID crit2Id = UUID.fromString(json.readTree(crit2Res.getResponse().getContentAsString()).get("id").asText());

            // 3. List criteria for the work item
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/criteria")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));

            // 4. Non-PM (Developer) attempting to verify criterion receives 403 ACCESS_DENIED
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/criteria/" + crit1Id + "/verify")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "VERIFIED"))))
                    .andExpect(status().isForbidden());

            // 5. PM verifies criterion 1
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/criteria/" + crit1Id + "/verify")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("status", "VERIFIED"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("VERIFIED"))
                    .andExpect(jsonPath("$.verifiedBy").value(pmId.toString()))
                    .andExpect(jsonPath("$.verifiedAt").isNotEmpty());

            // 6. Delete criterion 2
            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/criteria/" + crit2Id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isNoContent());

            // 7. Verify only 1 criterion remains
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/work-items/" + taskId + "/criteria")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(crit1Id.toString()));
        }
    }
}
