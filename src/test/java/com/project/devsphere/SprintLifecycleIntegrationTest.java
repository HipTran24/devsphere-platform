package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.domain.SessionTokens;
import org.hamcrest.Matchers;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
public class SprintLifecycleIntegrationTest {

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
        hmac[0] = 5;
        encryption[0] = 9;
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

    // --- Helper Fixtures ---

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
                            "VALUES (?, ?, ?, ?, 'SETUP_INCOMPLETE', ?, ?, 0)",
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

    private UUID addProjectMember(UUID projectId, UUID workspaceId, UUID userId) {
        UUID memberId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                memberId, projectId, workspaceId, userId, now, now, now
        );
        return memberId;
    }

    private UUID addProjectMemberRole(UUID projectId, UUID projectMemberId, String role, UUID grantedBy) {
        UUID roleId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                roleId, projectId, projectMemberId, role, grantedBy, now, grantedBy, now
        );
        return roleId;
    }

    private UUID insertWorkItem(UUID projectId, String itemKey, String title, BigDecimal storyPoints, UUID sprintId, String status, UUID pmUserId) {
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime completedAt = "DONE".equals(status) ? now : null;
        UUID acceptedBy = "DONE".equals(status) ? pmUserId : null;

        jdbc.update(
                "INSERT INTO work_items (" +
                        "id, project_id, item_key, type, title, status, story_points, sprint_id, " +
                        "completed_at, accepted_by, created_at, updated_at, version" +
                        ") VALUES (?, ?, ?, 'USER_STORY', ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                itemId, projectId, itemKey, title, status, storyPoints, sprintId,
                completedAt, acceptedBy, now, now
        );
        return itemId;
    }

    // =========================================================================
    // 1. Sprint Creation & Validation Tests
    // =========================================================================

    @Nested
    @DisplayName("1. Sprint Creation & Validation")
    class SprintCreationTests {

        @Test
        @DisplayName("PM creates sprint with valid dates -> 201 Created, status PLANNED")
        void pmCreatesSprintWithValidDates() throws Exception {
            UUID pmUser = createUser("pm-sprint@devsphere.test", "PM Sprint", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-sp", "Workspace Sprint");
            UUID prjId = createProject(wsId, "SPPRJ", "Sprint Project", pmUser);

            String requestBody = json.writeValueAsString(Map.of(
                    "name", "Sprint 1 - Foundation",
                    "goal", "Deliver authentication and database baseline",
                    "startDate", "2026-12-01",
                    "endDate", "2026-12-14"
            ));

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.projectId").value(prjId.toString()))
                    .andExpect(jsonPath("$.name").value("Sprint 1 - Foundation"))
                    .andExpect(jsonPath("$.goal").value("Deliver authentication and database baseline"))
                    .andExpect(jsonPath("$.status").value("PLANNED"))
                    .andExpect(jsonPath("$.startDate").value("2026-12-01"))
                    .andExpect(jsonPath("$.endDate").value("2026-12-14"))
                    .andReturn();

            UUID sprintId = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());

            // Direct DB assertions:
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT name, goal, status, start_date, end_date, committed_points, completed_points, closed_at FROM sprints WHERE id = ?",
                    sprintId
            );
            assertThat(row.get("name")).isEqualTo("Sprint 1 - Foundation");
            assertThat(row.get("goal")).isEqualTo("Deliver authentication and database baseline");
            assertThat(row.get("status")).isEqualTo("PLANNED");
            assertThat(row.get("start_date").toString()).isEqualTo("2026-12-01");
            assertThat(row.get("end_date").toString()).isEqualTo("2026-12-14");
            assertThat(row.get("committed_points")).isNull();
            assertThat(row.get("completed_points")).isNull();
            assertThat(row.get("closed_at")).isNull();
        }

        @Test
        @DisplayName("Date validation: endDate <= startDate is rejected with 400 Bad Request")
        void sprintCreationWithInvalidDatesRejected() throws Exception {
            UUID pmUser = createUser("pm-dates@devsphere.test", "PM Dates", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-dates", "Workspace Dates");
            UUID prjId = createProject(wsId, "DATEPRJ", "Date Project", pmUser);

            // Case 1: endDate == startDate
            String sameDateBody = json.writeValueAsString(Map.of(
                    "name", "Sprint Same Dates",
                    "goal", "Invalid same date",
                    "startDate", "2026-12-01",
                    "endDate", "2026-12-01"
            ));

            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sameDateBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(
                            Matchers.is("INVALID_SPRINT_DATES"),
                            Matchers.is("VALIDATION_FAILED"),
                            Matchers.is("BAD_REQUEST")
                    )));

            // Case 2: endDate < startDate
            String invertedDateBody = json.writeValueAsString(Map.of(
                    "name", "Sprint Inverted Dates",
                    "goal", "Invalid inverted dates",
                    "startDate", "2026-12-14",
                    "endDate", "2026-12-01"
            ));

            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invertedDateBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(
                            Matchers.is("INVALID_SPRINT_DATES"),
                            Matchers.is("VALIDATION_FAILED"),
                            Matchers.is("BAD_REQUEST")
                    )));
        }

        @Test
        @DisplayName("Non-PM caller is denied sprint creation with 403 ACCESS_DENIED")
        void nonPmCannotCreateSprint() throws Exception {
            UUID pmUser = createUser("pm-auth-sp@devsphere.test", "PM Auth", "ACTIVE", "USER", true);
            UUID wsId = createWorkspace(pmUser, "ws-auth-sp", "Workspace Auth");
            UUID prjId = createProject(wsId, "AUTHSP", "Auth Project", pmUser);

            UUID devUser = createUser("dev-sprint@devsphere.test", "Dev Sprint", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUser);
            addWorkspaceMember(wsId, devUser, "MEMBER");
            UUID devMemberId = addProjectMember(prjId, wsId, devUser);
            addProjectMemberRole(prjId, devMemberId, "DEVELOPER", pmUser);

            String requestBody = json.writeValueAsString(Map.of(
                    "name", "Unauthorized Sprint",
                    "goal", "Should be rejected",
                    "startDate", "2026-12-01",
                    "endDate", "2026-12-14"
            ));

            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(
                            Matchers.is("ACCESS_DENIED"),
                            Matchers.is("FORBIDDEN")
                    )));
        }
    }

    // =========================================================================
    // 2. Starting Sprint & Single Active Sprint Invariant Tests
    // =========================================================================

    @Nested
    @DisplayName("2. Starting Sprint & Single Active Sprint Invariant")
    class StartingSprintTests {

        @Test
        @DisplayName("Starting PLANNED sprint transitions to ACTIVE and calculates committed_points from sum of assigned items")
        void startPlannedSprintCalculatesCommittedPoints() throws Exception {
            UUID pmUser = createUser("pm-start@devsphere.test", "PM Start", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-start", "Workspace Start");
            UUID prjId = createProject(wsId, "STARTSPRJ", "Start Project", pmUser);

            // 1. Create sprint
            MvcResult sprintResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "name", "Sprint Kickoff",
                                    "goal", "Complete initial sprint backlog",
                                    "startDate", "2026-12-01",
                                    "endDate", "2026-12-14"
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID sprintId = UUID.fromString(json.readTree(sprintResult.getResponse().getContentAsString()).get("id").asText());

            // 2. Assign items to sprint in DB: Item A (5 points), Item B (8.5 points)
            insertWorkItem(prjId, "STARTSPRJ-1", "User Story A", new BigDecimal("5.00"), sprintId, "TO_DO", pmUser);
            insertWorkItem(prjId, "STARTSPRJ-2", "User Story B", new BigDecimal("8.50"), sprintId, "TO_DO", pmUser);
            // Item C is in backlog (unassigned, 3 points)
            insertWorkItem(prjId, "STARTSPRJ-3", "User Story C (Backlog)", new BigDecimal("3.00"), null, "TO_DO", pmUser);

            // 3. Start sprint
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.committedPoints").value(13.5));

            // Direct DB assertions:
            Map<String, Object> sprintDb = jdbc.queryForMap(
                    "SELECT status, committed_points FROM sprints WHERE id = ?", sprintId);
            assertThat(sprintDb.get("status")).isEqualTo("ACTIVE");
            assertThat(new BigDecimal(sprintDb.get("committed_points").toString()))
                    .isEqualByComparingTo(new BigDecimal("13.50"));
        }

        @Test
        @DisplayName("Sequential attempt to start 2nd sprint while one is ACTIVE returns 409 ACTIVE_SPRINT_EXISTS")
        void sequentialStartSecondSprintFailsWith409() throws Exception {
            UUID pmUser = createUser("pm-seq@devsphere.test", "PM Seq", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-seq", "Workspace Seq");
            UUID prjId = createProject(wsId, "SEQPRJ", "Seq Project", pmUser);

            // Create Sprint 1 and Sprint 2
            MvcResult s1Result = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "name", "Sprint 1", "startDate", "2026-12-01", "endDate", "2026-12-14"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID s1Id = UUID.fromString(json.readTree(s1Result.getResponse().getContentAsString()).get("id").asText());

            MvcResult s2Result = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "name", "Sprint 2", "startDate", "2026-12-15", "endDate", "2026-12-28"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID s2Id = UUID.fromString(json.readTree(s2Result.getResponse().getContentAsString()).get("id").asText());

            // Start Sprint 1 -> 200 OK
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + s1Id + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            // Attempt to start Sprint 2 -> 409 Conflict
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + s2Id + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(
                            Matchers.is("ACTIVE_SPRINT_EXISTS"),
                            Matchers.is("CONFLICT")
                    )));

            // DB check: Sprint 1 is ACTIVE, Sprint 2 remains PLANNED
            String s1Status = jdbc.queryForObject("SELECT status FROM sprints WHERE id = ?", String.class, s1Id);
            String s2Status = jdbc.queryForObject("SELECT status FROM sprints WHERE id = ?", String.class, s2Id);
            assertThat(s1Status).isEqualTo("ACTIVE");
            assertThat(s2Status).isEqualTo("PLANNED");
        }

        @Test
        @DisplayName("Concurrency race condition: 2 simultaneous start calls for distinct sprints in same project ensure exactly one ACTIVE")
        void concurrentRaceConditionEnsuresSingleActiveSprint() throws Exception {
            UUID pmUser = createUser("pm-race@devsphere.test", "PM Race", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-race", "Workspace Race");
            UUID prjId = createProject(wsId, "RACEPRJ", "Race Project", pmUser);

            // Create Sprint A and Sprint B
            MvcResult saResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "name", "Sprint A", "startDate", "2026-12-01", "endDate", "2026-12-14"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID saId = UUID.fromString(json.readTree(saResult.getResponse().getContentAsString()).get("id").asText());

            MvcResult sbResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "name", "Sprint B", "startDate", "2026-12-15", "endDate", "2026-12-28"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID sbId = UUID.fromString(json.readTree(sbResult.getResponse().getContentAsString()).get("id").asText());

            // Prepare concurrent execution
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger conflictCount = new AtomicInteger(0);

            Future<?> taskA = executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    int code = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + saId + "/start")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                            .andReturn().getResponse().getStatus();
                    if (code == 200) successCount.incrementAndGet();
                    else if (code == 409) conflictCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Future<?> taskB = executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    int code = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sbId + "/start")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                            .andReturn().getResponse().getStatus();
                    if (code == 200) successCount.incrementAndGet();
                    else if (code == 409) conflictCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            readyLatch.await();
            startLatch.countDown(); // Release both threads at the exact same instant

            taskA.get();
            taskB.get();
            executor.shutdown();

            // Assertions: exactly one 200 OK and one 409 Conflict
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(1);

            // DB assertion: Exactly 1 active sprint in project
            Integer activeCount = jdbc.queryForObject(
                    "SELECT count(*) FROM sprints WHERE project_id = ? AND status = 'ACTIVE'",
                    Integer.class,
                    prjId
            );
            assertThat(activeCount).isEqualTo(1);
        }
    }

    // =========================================================================
    // 3. Immutability of Closed Sprint (ds_sprint_close)
    // =========================================================================

    @Nested
    @DisplayName("3. Immutability of Closed Sprint (ds_sprint_close)")
    class ClosedSprintImmutabilityTests {

        @Test
        @DisplayName("Closing sprint snapshots completed_points, unlinks incomplete items, and stamps closed_at")
        void closingSprintExecutesSnapshotTrigger() throws Exception {
            UUID pmUser = createUser("pm-close@devsphere.test", "PM Close", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-close", "Workspace Close");
            UUID prjId = createProject(wsId, "CLOSEPRJ", "Close Project", pmUser);

            // 1. Create and start sprint
            MvcResult sResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "name", "Sprint To Close", "startDate", "2026-12-01", "endDate", "2026-12-14"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID sprintId = UUID.fromString(json.readTree(sResult.getResponse().getContentAsString()).get("id").asText());

            // 2. Add work items:
            // Item 1: DONE, 5 points
            UUID item1Id = insertWorkItem(prjId, "CLOSEPRJ-1", "Done Item", new BigDecimal("5.00"), sprintId, "DONE", pmUser);
            // Item 2: IN_PROGRESS, 8 points
            UUID item2Id = insertWorkItem(prjId, "CLOSEPRJ-2", "In Progress Item", new BigDecimal("8.00"), sprintId, "IN_PROGRESS", pmUser);

            // Start sprint
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk());

            // 3. Close sprint
            String closeBody = json.writeValueAsString(Map.of(
                    "reviewNotes", "All acceptance criteria verified for completed items",
                    "retrospectiveNotes", "Carry over incomplete tasks to next sprint",
                    "expectedVersion", 1L
            ));

            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId + "/close")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(closeBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.completedPoints").value(5.0));

            // 4. Invariant verifications in DB:
            // a) sprints record
            Map<String, Object> sprintRow = jdbc.queryForMap(
                    "SELECT status, closed_at, completed_points, review_notes FROM sprints WHERE id = ?", sprintId);
            assertThat(sprintRow.get("status")).isEqualTo("CLOSED");
            assertThat(sprintRow.get("closed_at")).isNotNull();
            assertThat(new BigDecimal(sprintRow.get("completed_points").toString()))
                    .isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(sprintRow.get("review_notes")).isEqualTo("All acceptance criteria verified for completed items");

            // b) Item 1 (DONE) stays attached to closed sprint
            UUID item1Sprint = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item1Id);
            assertThat(item1Sprint).isEqualTo(sprintId);

            // c) Item 2 (IN_PROGRESS) automatically unlinked (sprint_id = NULL) back to backlog
            UUID item2Sprint = jdbc.queryForObject("SELECT sprint_id FROM work_items WHERE id = ?", UUID.class, item2Id);
            assertThat(item2Sprint).isNull();
        }

        @Test
        @DisplayName("Database trigger ds_sprint_close blocks modifying dates or metrics on CLOSED sprint (ERRCODE 23514)")
        void closedSprintMetricsAndDatesAreImmutable() throws Exception {
            UUID pmUser = createUser("pm-immu@devsphere.test", "PM Immu", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-immu", "Workspace Immu");
            UUID prjId = createProject(wsId, "IMMUPRJ", "Immu Project", pmUser);

            // 1. Create, start and close sprint
            MvcResult sResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "name", "Sprint Immutable", "startDate", "2026-12-01", "endDate", "2026-12-14"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID sprintId = UUID.fromString(json.readTree(sResult.getResponse().getContentAsString()).get("id").asText());

            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk());

            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId + "/close")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("expectedVersion", 1L))))
                    .andExpect(status().isOk());

            // 2. Direct DB tamper attempt: update start_date on closed sprint
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE sprints SET start_date = '2026-11-01' WHERE id = ?", sprintId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Closed Sprint metrics and dates are immutable");

            // 3. Direct DB tamper attempt: update status back to ACTIVE on closed sprint
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE sprints SET status = 'ACTIVE' WHERE id = ?", sprintId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Closed Sprint metrics and dates are immutable");

            // 4. Direct DB tamper attempt: update completed_points on closed sprint
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE sprints SET completed_points = 99.00 WHERE id = ?", sprintId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Closed Sprint metrics and dates are immutable");

            // 5. API PATCH attempt on closed sprint modifying dates is rejected
            String patchBody = json.writeValueAsString(Map.of(
                    "startDate", "2026-11-01",
                    "endDate", "2026-11-20",
                    "expectedVersion", 2L
            ));

            mvc.perform(patch("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody))
                    .andExpect(status().is4xxClientError());
        }
    }

    // =========================================================================
    // 4. PM Role Authorization Tests
    // =========================================================================

    @Nested
    @DisplayName("4. PM Role Authorization")
    class AuthorizationTests {

        @Test
        @DisplayName("Non-PM is denied calling start, close, or update endpoints on sprints (403 ACCESS_DENIED)")
        void nonPmCannotManageSprintLifecycle() throws Exception {
            UUID pmUser = createUser("pm-sec@devsphere.test", "PM Sec", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-sec", "Workspace Sec");
            UUID prjId = createProject(wsId, "SECPRJ", "Sec Project", pmUser);

            UUID devUser = createUser("dev-sec@devsphere.test", "Dev Sec", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUser);
            addWorkspaceMember(wsId, devUser, "MEMBER");
            UUID devMemberId = addProjectMember(prjId, wsId, devUser);
            addProjectMemberRole(prjId, devMemberId, "DEVELOPER", pmUser);

            // PM creates sprint
            MvcResult sResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "name", "Security Sprint", "startDate", "2026-12-01", "endDate", "2026-12-14"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID sprintId = UUID.fromString(json.readTree(sResult.getResponse().getContentAsString()).get("id").asText());

            // 1. Dev cannot start sprint
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(
                            Matchers.is("ACCESS_DENIED"),
                            Matchers.is("FORBIDDEN")
                    )));

            // 2. Dev cannot patch sprint
            mvc.perform(patch("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "goal", "Tampered goal",
                                    "expectedVersion", 0L
                            ))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(
                            Matchers.is("ACCESS_DENIED"),
                            Matchers.is("FORBIDDEN")
                    )));

            // PM starts sprint
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId + "/start")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk());

            // 3. Dev cannot close sprint
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/sprints/" + sprintId + "/close")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("expectedVersion", 1L))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(
                            Matchers.is("ACCESS_DENIED"),
                            Matchers.is("FORBIDDEN")
                    )));
        }
    }
}
