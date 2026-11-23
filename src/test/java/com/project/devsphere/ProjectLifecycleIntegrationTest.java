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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
public class ProjectLifecycleIntegrationTest {

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
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        hmac[0] = 1;
        encryption[0] = 2;
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

    private UUID createWorkspace(UUID ownerId, String slug, String name, String status) {
        return tx.execute(s -> {
            UUID workspaceId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO workspaces (id, slug, name, owner_id, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                    workspaceId, slug, name, ownerId, status, now, now
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
        jdbc.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at) " +
                        "VALUES (?, ?, ?, 'ACTIVE', now())",
                workspaceId, userId, role
        );
    }

    private UUID addProjectMember(UUID projectId, UUID workspaceId, UUID userId) {
        UUID memberId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, 'ACTIVE', now(), now(), now(), 0)",
                memberId, projectId, workspaceId, userId
        );
        return memberId;
    }

    private UUID addProjectMemberRole(UUID projectId, UUID projectMemberId, String role, UUID grantedBy) {
        UUID roleId = UUID.randomUUID();
        UUID recipient = jdbc.queryForObject(
                "SELECT user_id FROM project_members WHERE id = ?",
                UUID.class,
                projectMemberId
        );
        jdbc.update(
                "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                        "VALUES (?, ?, ?, ?, ?, now(), ?, now())",
                roleId, projectId, projectMemberId, role, grantedBy, recipient
        );
        return roleId;
    }

    private UUID createWorkItem(UUID projectId, String itemKey, String title) {
        UUID workItemId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO work_items (id, project_id, item_key, type, title, status, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'TASK', ?, 'TO_DO', now(), now(), 0)",
                workItemId, projectId, itemKey, title
        );
        return workItemId;
    }

    // =========================================================================
    // 1. One-Project-Per-Workspace Invariant
    // =========================================================================

    @Nested
    @DisplayName("1-Project-Per-Workspace Invariant Tests")
    class OneProjectPerWorkspaceTests {

        @Test
        @DisplayName("Sequential creation of a 2nd project in the same workspace is rejected with 409 DUPLICATE_RESOURCE")
        void sequentialSecondProjectRejectedWithDuplicateResource() throws Exception {
            UUID ownerId = createUser("owner-seq@example.test", "Owner Sequential", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-seq", "Sequential Workspace", "SETUP_INCOMPLETE");

            Map<String, Object> project1 = Map.of(
                    "projectKey", "PRJONE",
                    "name", "Project One",
                    "objective", "First project in workspace",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "sprintLengthDays", 14,
                    "selfAssignPm", true
            );

            // 1st project creation succeeds with 201 Created
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(project1)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.projectKey").value("PRJONE"))
                    .andExpect(jsonPath("$.status").value("PLANNING"));

            Map<String, Object> project2 = Map.of(
                    "projectKey", "PRJTWO",
                    "name", "Project Two",
                    "objective", "Second project attempt",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "sprintLengthDays", 14,
                    "selfAssignPm", true
            );

            // 2nd project creation in same workspace is rejected with 409 DUPLICATE_RESOURCE
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(project2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));

            int projectCount = jdbc.queryForObject(
                    "SELECT count(*) FROM projects WHERE workspace_id = ?",
                    Integer.class,
                    workspaceId
            );
            assertThat(projectCount).isEqualTo(1);
        }

        @Test
        @DisplayName("Concurrent race creation of 2 projects in the same workspace: exactly 1 succeeds, 1 fails with 409 DUPLICATE_RESOURCE")
        void concurrentRaceProjectCreationEnsuresExactlyOneSucceeds() throws Exception {
            UUID ownerId = createUser("owner-race@example.test", "Owner Race", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-race", "Race Workspace", "SETUP_INCOMPLETE");

            Map<String, Object> projectA = Map.of(
                    "projectKey", "PRJA",
                    "name", "Project A",
                    "objective", "Race Project A",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "selfAssignPm", true
            );

            Map<String, Object> projectB = Map.of(
                    "projectKey", "PRJB",
                    "name", "Project B",
                    "objective", "Race Project B",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "selfAssignPm", true
            );

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(1);

            Callable<Integer> createA = () -> {
                latch.await();
                return mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(projectA)))
                        .andReturn().getResponse().getStatus();
            };

            Callable<Integer> createB = () -> {
                latch.await();
                return mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(projectB)))
                        .andReturn().getResponse().getStatus();
            };

            Future<Integer> futureA = executor.submit(createA);
            Future<Integer> futureB = executor.submit(createB);

            latch.countDown();

            int statusA = futureA.get(10, TimeUnit.SECONDS);
            int statusB = futureB.get(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Exactly one succeeds (201) and one conflicts (409)
            assertThat(List.of(statusA, statusB))
                    .containsExactlyInAnyOrder(201, 409);

            int projectCount = jdbc.queryForObject(
                    "SELECT count(*) FROM projects WHERE workspace_id = ?",
                    Integer.class,
                    workspaceId
            );
            assertThat(projectCount).isEqualTo(1);
        }
    }

    // =========================================================================
    // 2. Initial Setup & Self-Assignment
    // =========================================================================

    @Nested
    @DisplayName("Initial Setup & Self-Assignment Tests")
    class InitialSetupAndSelfAssignmentTests {

        @Test
        @DisplayName("selfAssignPm: true creates project in PLANNING, activates workspace to ACTIVE, and grants caller active PM role")
        void selfAssignPmTrueActivatesWorkspaceAndGrantsPmRole() throws Exception {
            UUID ownerId = createUser("owner-pm-true@example.test", "Owner PM True", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-pm-true", "PM True WS", "SETUP_INCOMPLETE");

            Map<String, Object> request = Map.of(
                    "projectKey", "PRJACT",
                    "name", "Active Setup Project",
                    "objective", "Setup with self PM",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "selfAssignPm", true
            );

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PLANNING"))
                    .andReturn();

            JsonNode projectNode = json.readTree(result.getResponse().getContentAsString());
            UUID projectId = UUID.fromString(projectNode.get("id").asText());

            // 1. Verify workspace status transitioned to ACTIVE
            String wsStatus = jdbc.queryForObject(
                    "SELECT status FROM workspaces WHERE id = ?",
                    String.class,
                    workspaceId
            );
            assertThat(wsStatus).isEqualTo("ACTIVE");

            // 2. Verify caller has active PM role in project
            Boolean hasPmRole = jdbc.queryForObject(
                    "SELECT ds_has_project_role(?, ?, 'PM')",
                    Boolean.class,
                    projectId,
                    ownerId
            );
            assertThat(hasPmRole).isTrue();

            // 3. Verify project_member_roles has accepted_at set
            OffsetDateTime acceptedAt = jdbc.queryForObject(
                    "SELECT pr.accepted_at FROM project_member_roles pr " +
                            "JOIN project_members pm ON pm.id = pr.project_member_id " +
                            "WHERE pr.project_id = ? AND pm.user_id = ? AND pr.role = 'PM'",
                    OffsetDateTime.class,
                    projectId,
                    ownerId
            );
            assertThat(acceptedAt).isNotNull();
        }

        @Test
        @DisplayName("selfAssignPm: false creates project in PLANNING, leaves workspace in SETUP_INCOMPLETE, and caller has no PM role")
        void selfAssignPmFalseLeavesWorkspaceIncomplete() throws Exception {
            UUID ownerId = createUser("owner-pm-false@example.test", "Owner PM False", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-pm-false", "PM False WS", "SETUP_INCOMPLETE");

            Map<String, Object> request = Map.of(
                    "projectKey", "PRJINCOMP",
                    "name", "Incomplete Setup Project",
                    "objective", "Setup without self PM",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "selfAssignPm", false
            );

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PLANNING"))
                    .andReturn();

            JsonNode projectNode = json.readTree(result.getResponse().getContentAsString());
            UUID projectId = UUID.fromString(projectNode.get("id").asText());

            // 1. Verify workspace status remains SETUP_INCOMPLETE
            String wsStatus = jdbc.queryForObject(
                    "SELECT status FROM workspaces WHERE id = ?",
                    String.class,
                    workspaceId
            );
            assertThat(wsStatus).isEqualTo("SETUP_INCOMPLETE");

            // 2. Verify caller does NOT have PM role
            Boolean hasPmRole = jdbc.queryForObject(
                    "SELECT ds_has_project_role(?, ?, 'PM')",
                    Boolean.class,
                    projectId,
                    ownerId
            );
            assertThat(hasPmRole).isFalse();
        }
    }

    // =========================================================================
    // 3. Project Metadata Update & Role Separation (TC-28 & H3)
    // =========================================================================

    @Nested
    @DisplayName("Project Metadata Update & Role Separation Tests")
    class ProjectMetadataUpdateTests {

        @Test
        @DisplayName("PM can update project metadata (name, description, objective, timezone, plannedStart, sprintLengthDays)")
        void pmCanUpdateProjectMetadata() throws Exception {
            UUID ownerId = createUser("owner-meta@example.test", "Owner Meta", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-meta", "Meta Workspace", "ACTIVE");

            // Create project with selfAssignPm=true so owner is PM
            Map<String, Object> createReq = Map.of(
                    "projectKey", "PRJMETA",
                    "name", "Initial Name",
                    "objective", "Initial Objective",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "sprintLengthDays", 14,
                    "selfAssignPm", true
            );

            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            JsonNode createdJson = json.readTree(createResult.getResponse().getContentAsString());
            UUID projectId = UUID.fromString(createdJson.get("id").asText());
            long createdVersion = createdJson.get("version").asLong();

            // PM updates project metadata
            Map<String, Object> updateReq = Map.of(
                    "name", "Updated Project Name",
                    "description", "Updated description content",
                    "objective", "Updated objective content",
                    "timezone", "UTC",
                    "plannedStart", "2026-12-15",
                    "sprintLengthDays", 21,
                    "expectedVersion", createdVersion
            );

            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(updateReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Project Name"))
                    .andExpect(jsonPath("$.description").value("Updated description content"))
                    .andExpect(jsonPath("$.objective").value("Updated objective content"))
                    .andExpect(jsonPath("$.timezone").value("UTC"))
                    .andExpect(jsonPath("$.sprintLengthDays").value(21))
                    .andExpect(jsonPath("$.version").value(createdVersion + 1));
        }

        @Test
        @DisplayName("Non-PM (including Workspace OWNER without PM) receives 403 ACCESS_DENIED on update (TC-28)")
        void nonPmCannotUpdateProjectMetadata() throws Exception {
            UUID ownerId = createUser("owner-nopm@example.test", "Owner No PM", "ACTIVE", "USER", true);
            UUID pmUserId = createUser("pm-user@example.test", "Real PM User", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(ownerId);
            String pmToken = issueAccessToken(pmUserId);

            UUID workspaceId = createWorkspace(ownerId, "ws-nopm", "No PM Workspace", "ACTIVE");
            addWorkspaceMember(workspaceId, pmUserId, "MEMBER");

            // Create project with selfAssignPm=false
            Map<String, Object> createReq = Map.of(
                    "projectKey", "PRJNOPM",
                    "name", "No PM Project",
                    "objective", "Initial Objective",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "selfAssignPm", false
            );

            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID projectId = UUID.fromString(json.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

            // Grant PM role to pmUserId (not owner)
            UUID pmMemberId = addProjectMember(projectId, workspaceId, pmUserId);
            addProjectMemberRole(projectId, pmMemberId, "PM", ownerId);

            // Workspace OWNER (without PM role) tries to update project metadata -> 403 ACCESS_DENIED
            Map<String, Object> updateReq = Map.of(
                    "name", "Owner Malicious Update",
                    "expectedVersion", 0
            );

            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(updateReq)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Attempting to modify committedDeadline returns 409 CLIENT_DEADLINE_CONFIRMATION_REQUIRED")
        void modifyingCommittedDeadlineReturnsConflict() throws Exception {
            UUID ownerId = createUser("owner-deadline@example.test", "Owner Deadline", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-dl", "Deadline Workspace", "ACTIVE");

            Map<String, Object> createReq = Map.of(
                    "projectKey", "PRJDL",
                    "name", "Deadline Project",
                    "objective", "Objective",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "selfAssignPm", true
            );

            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID projectId = UUID.fromString(json.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

            // Attempting to alter committedDeadline
            Map<String, Object> updateReq = Map.of(
                    "name", "Changed Name",
                    "committedDeadline", "2027-06-01",
                    "expectedVersion", 0
            );

            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(updateReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CLIENT_DEADLINE_CONFIRMATION_REQUIRED"));
        }

        @Test
        @DisplayName("Mismatched expectedVersion returns 409 PLAN_STALE")
        void mismatchedVersionReturnsPlanStale() throws Exception {
            UUID ownerId = createUser("owner-stale@example.test", "Owner Stale", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-stale", "Stale Workspace", "ACTIVE");

            Map<String, Object> createReq = Map.of(
                    "projectKey", "PRJSTALE",
                    "name", "Stale Project",
                    "objective", "Objective",
                    "timezone", "Asia/Ho_Chi_Minh",
                    "plannedStart", "2026-12-01",
                    "selfAssignPm", true
            );

            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID projectId = UUID.fromString(json.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

            // Mismatched expectedVersion
            Map<String, Object> updateReq = Map.of(
                    "name", "Stale Name Update",
                    "expectedVersion", 999
            );

            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(updateReq)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_STALE"));
        }
    }

    // =========================================================================
    // 4. Project Activation Preconditions & Matrix (TC-31 & ds_project_activation)
    // =========================================================================

    @Nested
    @DisplayName("Project Activation Preconditions & Matrix Tests (TC-31)")
    class ProjectActivationTests {

        private UUID setupProjectForActivation(UUID ownerId, String projectKey, String objective, LocalDate plannedStart) {
            UUID workspaceId = createWorkspace(ownerId, "ws-act-" + projectKey.toLowerCase(), "Act Workspace", "ACTIVE");
            UUID projectId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, sprint_length_days, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 'Activation Test Project', 'Desc', ?, 'PLANNING', 'Asia/Ho_Chi_Minh', ?, 14, ?, ?, 0)",
                    projectId, workspaceId, projectKey, objective, plannedStart, now, now
            );

            UUID memberId = addProjectMember(projectId, workspaceId, ownerId);
            addProjectMemberRole(projectId, memberId, "PM", ownerId);

            return projectId;
        }

        private long getProjectVersion(UUID projectId) {
            return jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
        }

        @Test
        @DisplayName("Activation fails 422 ACTIVATION_PRECONDITION_FAILED when objective is blank or missing")
        void activationFailsWhenObjectiveMissing() throws Exception {
            UUID ownerId = createUser("act-no-obj@example.test", "Act No Obj", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID projectId = setupProjectForActivation(ownerId, "NOOBJ", null, LocalDate.of(2026, 12, 1));
            UUID workspaceId = jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID.class, projectId);
            createWorkItem(projectId, "NOOBJ-1", "Task 1");

            Map<String, Object> request = Map.of("expectedVersion", getProjectVersion(projectId));

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/activate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACTIVATION_PRECONDITION_FAILED"));

            String status = jdbc.queryForObject("SELECT status FROM projects WHERE id = ?", String.class, projectId);
            assertThat(status).isEqualTo("PLANNING");
        }

        @Test
        @DisplayName("Activation fails 422 ACTIVATION_PRECONDITION_FAILED when planned_start is missing")
        void activationFailsWhenPlannedStartMissing() throws Exception {
            UUID ownerId = createUser("act-no-start@example.test", "Act No Start", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID projectId = setupProjectForActivation(ownerId, "NOSTART", "Objective present", null);
            UUID workspaceId = jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID.class, projectId);
            createWorkItem(projectId, "NOSTART-1", "Task 1");

            Map<String, Object> request = Map.of("expectedVersion", getProjectVersion(projectId));

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/activate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACTIVATION_PRECONDITION_FAILED"));
        }

        @Test
        @DisplayName("Activation fails 422 ACTIVATION_PRECONDITION_FAILED when backlog has 0 active work items")
        void activationFailsWhenBacklogEmpty() throws Exception {
            UUID ownerId = createUser("act-empty-bl@example.test", "Act Empty Backlog", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID projectId = setupProjectForActivation(ownerId, "EMPTYBL", "Objective present", LocalDate.of(2026, 12, 1));
            UUID workspaceId = jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID.class, projectId);

            // No work items created (backlog is 0)
            Map<String, Object> request = Map.of("expectedVersion", getProjectVersion(projectId));

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/activate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACTIVATION_PRECONDITION_FAILED"));
        }

        @Test
        @DisplayName("Activation fails 422 ACTIVATION_PRECONDITION_FAILED when multiple PMs or no PM exists")
        void activationFailsWhenPmCountNotOne() throws Exception {
            UUID ownerId = createUser("act-mult-pm@example.test", "Act Multi PM", "ACTIVE", "USER", true);
            UUID secondPmId = createUser("act-second-pm@example.test", "Second PM", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID projectId = setupProjectForActivation(ownerId, "MULTPM", "Objective present", LocalDate.of(2026, 12, 1));
            UUID workspaceId = jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID.class, projectId);
            createWorkItem(projectId, "MULTPM-1", "Task 1");

            try {
                jdbc.execute("DROP INDEX IF EXISTS uq_project_one_pm");
                // Add a second PM
                addWorkspaceMember(workspaceId, secondPmId, "MEMBER");
                UUID secondMemberId = addProjectMember(projectId, workspaceId, secondPmId);
                addProjectMemberRole(projectId, secondMemberId, "PM", ownerId);

                Map<String, Object> request = Map.of("expectedVersion", getProjectVersion(projectId));

                mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/activate")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(request)))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.code").value("ACTIVATION_PRECONDITION_FAILED"));
            } finally {
                jdbc.execute("DELETE FROM project_member_roles WHERE project_id = '" + projectId + "' AND project_member_id <> (SELECT id FROM project_members WHERE project_id = '" + projectId + "' AND user_id = '" + ownerId + "')");
                jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_project_one_pm ON project_member_roles(project_id) WHERE role='PM' AND revoked_at IS NULL");
            }
        }

        @Test
        @DisplayName("Non-PM attempting activation receives 403 ACCESS_DENIED")
        void nonPmAttemptingActivationReceivesForbidden() throws Exception {
            UUID ownerId = createUser("owner-act-perm@example.test", "Owner Act Perm", "ACTIVE", "USER", true);
            UUID developerId = createUser("dev-act-perm@example.test", "Dev Act Perm", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(developerId);

            UUID projectId = setupProjectForActivation(ownerId, "DEVACT", "Objective present", LocalDate.of(2026, 12, 1));
            UUID workspaceId = jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID.class, projectId);
            createWorkItem(projectId, "DEVACT-1", "Task 1");

            addWorkspaceMember(workspaceId, developerId, "MEMBER");
            UUID devMemberId = addProjectMember(projectId, workspaceId, developerId);
            addProjectMemberRole(projectId, devMemberId, "DEVELOPER", ownerId);

            Map<String, Object> request = Map.of("expectedVersion", getProjectVersion(projectId));

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/activate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("When all preconditions met (objective, planned_start, backlog >= 1, PM = 1), activation succeeds and status becomes ACTIVE")
        void fullPreconditionsMetActivationSucceeds() throws Exception {
            UUID ownerId = createUser("act-success@example.test", "Act Success PM", "ACTIVE", "USER", true);
            String token = issueAccessToken(ownerId);
            UUID projectId = setupProjectForActivation(ownerId, "ACTSUCC", "Rigorous Project Objective", LocalDate.of(2026, 12, 1));
            UUID workspaceId = jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID.class, projectId);
            createWorkItem(projectId, "ACTSUCC-1", "First Sprint Backlog Item");

            long currentVersion = getProjectVersion(projectId);
            Map<String, Object> request = Map.of("expectedVersion", currentVersion);

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/activate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.version").value(currentVersion + 1));

            String updatedStatus = jdbc.queryForObject("SELECT status FROM projects WHERE id = ?", String.class, projectId);
            assertThat(updatedStatus).isEqualTo("ACTIVE");
        }
    }

    // =========================================================================
    // 5. Cross-Tenant Isolation
    // =========================================================================

    @Nested
    @DisplayName("Cross-Tenant Isolation Tests")
    class CrossTenantIsolationTests {

        @Test
        @DisplayName("Accessing a project with a mismatched workspace ID returns 403 ACCESS_DENIED")
        void mismatchedWorkspaceReturnsAccessDenied() throws Exception {
            UUID userA = createUser("tenant-a@example.test", "Tenant A", "ACTIVE", "USER", true);
            String tokenA = issueAccessToken(userA);
            UUID workspaceA = createWorkspace(userA, "ws-tenant-a", "Tenant A WS", "ACTIVE");

            UUID userB = createUser("tenant-b@example.test", "Tenant B", "ACTIVE", "USER", true);
            UUID workspaceB = createWorkspace(userB, "ws-tenant-b", "Tenant B WS", "ACTIVE");

            // Project belongs to workspace B
            UUID projectB = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, status, timezone, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'PRJB', 'Project B', 'PLANNING', 'Asia/Ho_Chi_Minh', ?, ?, 0)",
                    projectB, workspaceB, now, now
            );

            // User A attempts to access Project B using Workspace A's path -> 403 ACCESS_DENIED
            mvc.perform(get("/api/v1/workspaces/" + workspaceA + "/projects/" + projectB)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                    .andExpect(status().isForbidden());

            // User A attempts to patch Project B under Workspace A -> 403 ACCESS_DENIED
            mvc.perform(patch("/api/v1/workspaces/" + workspaceA + "/projects/" + projectB)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Hacked Name\",\"expectedVersion\":0}"))
                    .andExpect(status().isForbidden());

            // User A attempts to activate Project B under Workspace A -> 403 ACCESS_DENIED
            mvc.perform(post("/api/v1/workspaces/" + workspaceA + "/projects/" + projectB + "/activate")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":0}"))
                    .andExpect(status().isForbidden());
        }
    }
}
