package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.resource.api.AddTimeOffRequest;
import com.project.devsphere.resource.api.EffectiveCapacityChangeRequest;
import com.project.devsphere.resource.api.PutResourceCapacityRequest;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.api.WeeklySlot;
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
public class DeveloperCapacityIntegrationTest {

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
                            "VALUES (?, ?, ?, ?, 'Capacity Project Description', 'Capacity Project Objective', 'PLANNING', 'Asia/Ho_Chi_Minh', '2026-12-01', 14, ?, ?, 0)",
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

    // Standard Monday to Friday 9:00 - 17:00 weekly slots (540 to 1020 minutes = 480 mins/day)
    private List<WeeklySlot> createStandardWeekdaySlots() {
        return List.of(
                new WeeklySlot(1, 540, 1020),
                new WeeklySlot(2, 540, 1020),
                new WeeklySlot(3, 540, 1020),
                new WeeklySlot(4, 540, 1020),
                new WeeklySlot(5, 540, 1020)
        );
    }

    // =========================================================================
    // 1. Role & Scope Guard Tests
    // =========================================================================

    @Nested
    @DisplayName("Role & Scope Guard Tests")
    class RoleAndScopeGuardTests {

        @Test
        @DisplayName("PM can PUT capacity for DEVELOPER; attempt for non-developer is rejected with 400 ASSIGNEE_NOT_DEVELOPER")
        void pmCanConfigureCapacityOnlyForDeveloper() throws Exception {
            UUID ownerId = createUser("pm-cap@example.test", "PM Capacity", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-cap@example.test", "Dev Capacity", "ACTIVE", "USER", true);
            UUID viewerId = createUser("viewer-cap@example.test", "Viewer Capacity", "ACTIVE", "USER", true);

            UUID workspaceId = createWorkspace(ownerId, "ws-cap", "Workspace Cap");
            addWorkspaceMember(workspaceId, devId, "MEMBER");
            addWorkspaceMember(workspaceId, viewerId, "MEMBER");

            UUID projectId = createProject(workspaceId, "CAP1", "Cap Project 1", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, viewerId, "VIEWER", ownerId);

            Long pVer = getProjectVersion(projectId);

            // 1. PM puts capacity for active DEVELOPER -> 201 Created
            PutResourceCapacityRequest reqDev = new PutResourceCapacityRequest(
                    devId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("backend", 4), new SkillLevel("database", 3)),
                    createStandardWeekdaySlots(),
                    List.of(),
                    480,
                    new BigDecimal("100.00"),
                    pVer,
                    null
            );

            mvc.perform(put("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(reqDev)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.developerId").value(devId.toString()))
                    .andExpect(jsonPath("$.minutesPerDay").value(480))
                    .andExpect(jsonPath("$.eligibility").value("ELIGIBLE"));

            // 2. PM attempts to put capacity for VIEWER -> rejected with 400 ASSIGNEE_NOT_DEVELOPER
            Long pVer2 = getProjectVersion(projectId);
            PutResourceCapacityRequest reqViewer = new PutResourceCapacityRequest(
                    viewerId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("qa", 3)),
                    createStandardWeekdaySlots(),
                    List.of(),
                    480,
                    new BigDecimal("100.00"),
                    pVer2,
                    null
            );

            mvc.perform(put("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(reqViewer)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ASSIGNEE_NOT_DEVELOPER"));
        }

        @Test
        @DisplayName("DEVELOPER can read own capacity and calendar via GET; reading another developer returns 403 Forbidden")
        void developerCanReadOwnCapacityAndCalendar() throws Exception {
            UUID ownerId = createUser("pm-read@example.test", "PM Read", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID dev1 = createUser("dev1-read@example.test", "Dev 1", "ACTIVE", "USER", true);
            String dev1Token = issueAccessToken(dev1);
            UUID dev2 = createUser("dev2-read@example.test", "Dev 2", "ACTIVE", "USER", true);
            String dev2Token = issueAccessToken(dev2);

            UUID workspaceId = createWorkspace(ownerId, "ws-read", "Workspace Read");
            addWorkspaceMember(workspaceId, dev1, "MEMBER");
            addWorkspaceMember(workspaceId, dev2, "MEMBER");

            UUID projectId = createProject(workspaceId, "CAP2", "Cap Project 2", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, dev1, "DEVELOPER", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, dev2, "DEVELOPER", ownerId);

            // PM sets up capacity for dev1
            Long pVer = getProjectVersion(projectId);
            PutResourceCapacityRequest reqDev1 = new PutResourceCapacityRequest(
                    dev1,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("frontend", 5)),
                    createStandardWeekdaySlots(),
                    List.of(),
                    480,
                    new BigDecimal("100.00"),
                    pVer,
                    null
            );
            mvc.perform(put("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(reqDev1)))
                    .andExpect(status().isCreated());

            // 1. Dev1 reads own capacity -> 200 OK
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .param("developerId", dev1.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dev1Token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].developerId").value(dev1.toString()));

            // 2. Dev1 reads own calendar -> 200 OK
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources/" + dev1 + "/calendar")
                            .param("from", "2026-12-01")
                            .param("to", "2026-12-07")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dev1Token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.developerId").value(dev1.toString()));

            // 3. Dev2 attempts to read Dev1's capacity -> 403 Forbidden
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .param("developerId", dev1.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dev2Token))
                    .andExpect(status().isForbidden());

            // 4. Dev2 attempts to read Dev1's calendar -> 403 Forbidden
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources/" + dev1 + "/calendar")
                            .param("from", "2026-12-01")
                            .param("to", "2026-12-07")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dev2Token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Non-member or unauthorized roles receive 403 Forbidden on resource endpoints")
        void nonMemberReceivesForbidden() throws Exception {
            UUID ownerId = createUser("pm-sec@example.test", "PM Sec", "ACTIVE", "USER", true);
            UUID outsider = createUser("outsider@example.test", "Outsider", "ACTIVE", "USER", true);
            String outsiderToken = issueAccessToken(outsider);

            UUID workspaceId = createWorkspace(ownerId, "ws-sec", "Workspace Sec");
            UUID projectId = createProject(workspaceId, "CAP3", "Cap Project 3", ownerId);

            // Outsider attempts to PUT capacity -> 403 Forbidden
            PutResourceCapacityRequest req = new PutResourceCapacityRequest(
                    outsider,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("frontend", 3)),
                    createStandardWeekdaySlots(),
                    List.of(),
                    480,
                    new BigDecimal("100.00"),
                    0L,
                    null
            );

            mvc.perform(put("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isForbidden());

            // Outsider attempts to GET resources -> 403 Forbidden
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // 2. Database Invariants & GiST Exclusion Tests
    // =========================================================================

    @Nested
    @DisplayName("Database Invariants & GiST Exclusion Tests")
    class DatabaseInvariantsTests {

        @Test
        @DisplayName("GiST exclusion constraint ex_capacity_effective_overlap rejects overlapping effective date ranges")
        void gistExclusionRejectsOverlappingRanges() {
            UUID ownerId = createUser("pm-gist@example.test", "PM GiST", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-gist@example.test", "Dev GiST", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "ws-gist", "Workspace GiST");
            addWorkspaceMember(workspaceId, devId, "MEMBER");
            UUID projectId = createProject(workspaceId, "GIST", "Gist Project", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            // Insert 1st capacity: [2026-12-01, 2026-12-15)
            jdbc.update(
                    "INSERT INTO developer_capacity (id, project_id, user_id, skills, weekly_slots, time_off, minutes_per_workday, allocation_pct, effective_from, effective_to, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, '[]'::jsonb, '[{\"dayOfWeek\":1,\"startMinute\":540,\"endMinute\":1020}]'::jsonb, '[]'::jsonb, 480, 100, '2026-12-01', '2026-12-15', now(), now(), 0)",
                    UUID.randomUUID(), projectId, devId
            );

            // Attempt to insert overlapping capacity: [2026-12-10, 2026-12-25)
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO developer_capacity (id, project_id, user_id, skills, weekly_slots, time_off, minutes_per_workday, allocation_pct, effective_from, effective_to, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, '[]'::jsonb, '[{\"dayOfWeek\":1,\"startMinute\":540,\"endMinute\":1020}]'::jsonb, '[]'::jsonb, 480, 100, '2026-12-10', '2026-12-25', now(), now(), 0)",
                    UUID.randomUUID(), projectId, devId
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ex_capacity_effective_overlap");
        }

        @Test
        @DisplayName("Database trigger ds_calendar_guard rejects malformed skills, overlapping weekly slots, and invalid time off")
        void calendarGuardRejectsInvalidEntries() {
            UUID ownerId = createUser("pm-trig@example.test", "PM Trig", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-trig@example.test", "Dev Trig", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "ws-trig", "Workspace Trig");
            addWorkspaceMember(workspaceId, devId, "MEMBER");
            UUID projectId = createProject(workspaceId, "TRIG", "Trig Project", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            // 1. Invalid skill level (level 6 exceeds 1..5)
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO developer_capacity (id, project_id, user_id, skills, weekly_slots, time_off, minutes_per_workday, allocation_pct, effective_from, effective_to, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, '[{\"skill\":\"rust\",\"level\":6}]'::jsonb, '[{\"dayOfWeek\":1,\"startMinute\":540,\"endMinute\":1020}]'::jsonb, '[]'::jsonb, 480, 100, '2026-12-01', null, now(), now(), 0)",
                    UUID.randomUUID(), projectId, devId
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Invalid skill level");

            // 2. Overlapping weekly slots on same day
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO developer_capacity (id, project_id, user_id, skills, weekly_slots, time_off, minutes_per_workday, allocation_pct, effective_from, effective_to, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, '[]'::jsonb, '[{\"dayOfWeek\":1,\"startMinute\":540,\"endMinute\":720},{\"dayOfWeek\":1,\"startMinute\":660,\"endMinute\":1020}]'::jsonb, '[]'::jsonb, 480, 100, '2026-12-01', null, now(), now(), 0)",
                    UUID.randomUUID(), projectId, devId
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Weekly slots overlap");

            // 3. Invalid time off interval (endAt <= startAt)
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO developer_capacity (id, project_id, user_id, skills, weekly_slots, time_off, minutes_per_workday, allocation_pct, effective_from, effective_to, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, '[]'::jsonb, '[{\"dayOfWeek\":1,\"startMinute\":540,\"endMinute\":1020}]'::jsonb, '[{\"startAt\":\"2026-12-05T12:00:00Z\",\"endAt\":\"2026-12-05T10:00:00Z\"}]'::jsonb, 480, 100, '2026-12-01', null, now(), now(), 0)",
                    UUID.randomUUID(), projectId, devId
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Invalid time off interval");

            // 4. Overlapping time off intervals
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO developer_capacity (id, project_id, user_id, skills, weekly_slots, time_off, minutes_per_workday, allocation_pct, effective_from, effective_to, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, '[]'::jsonb, '[{\"dayOfWeek\":1,\"startMinute\":540,\"endMinute\":1020}]'::jsonb, " +
                            "'[{\"startAt\":\"2026-12-05T09:00:00Z\",\"endAt\":\"2026-12-05T15:00:00Z\"},{\"startAt\":\"2026-12-05T14:00:00Z\",\"endAt\":\"2026-12-05T18:00:00Z\"}]'::jsonb, " +
                            "480, 100, '2026-12-01', null, now(), now(), 0)",
                    UUID.randomUUID(), projectId, devId
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Time off intervals overlap");
        }

        @Test
        @DisplayName("project_source_changed trigger increments projects.version on capacity insert or update")
        void capacityChangeBumpsProjectVersion() throws Exception {
            UUID ownerId = createUser("pm-bump@example.test", "PM Bump", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-bump@example.test", "Dev Bump", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "ws-bump", "Workspace Bump");
            addWorkspaceMember(workspaceId, devId, "MEMBER");
            UUID projectId = createProject(workspaceId, "BUMP", "Bump Project", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            Long v0 = getProjectVersion(projectId);

            // PUT capacity
            PutResourceCapacityRequest req = new PutResourceCapacityRequest(
                    devId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("java", 5)),
                    createStandardWeekdaySlots(),
                    List.of(),
                    480,
                    new BigDecimal("100.00"),
                    v0,
                    null
            );

            mvc.perform(put("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isCreated());

            Long v1 = getProjectVersion(projectId);
            assertThat(v1).isGreaterThan(v0);
        }
    }

    // =========================================================================
    // 3. Effective Change Splitting Tests
    // =========================================================================

    @Nested
    @DisplayName("Effective Change Splitting Tests")
    class EffectiveChangeSplittingTests {

        @Test
        @DisplayName("effective-changes closes superseded record at effectiveFrom and creates new open-ended record")
        void effectiveChangeSplitsIntervalCorrectly() throws Exception {
            UUID ownerId = createUser("pm-split@example.test", "PM Split", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-split@example.test", "Dev Split", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "ws-split", "Workspace Split");
            addWorkspaceMember(workspaceId, devId, "MEMBER");
            UUID projectId = createProject(workspaceId, "SPLIT", "Split Project", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            Long pVer0 = getProjectVersion(projectId);

            // 1. Initial capacity from 2026-12-01 (open-ended: effectiveTo = null)
            PutResourceCapacityRequest initReq = new PutResourceCapacityRequest(
                    devId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("frontend", 3)),
                    createStandardWeekdaySlots(),
                    List.of(),
                    480,
                    new BigDecimal("100.00"),
                    pVer0,
                    null
            );

            MvcResult putRes = mvc.perform(put("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(initReq)))
                    .andExpect(status().isCreated())
                    .andReturn();

            JsonNode putNode = json.readTree(putRes.getResponse().getContentAsString());
            UUID initialCapId = UUID.fromString(putNode.get("id").asText());
            Long initialCapVer = putNode.get("version").asLong();

            // 2. Perform effective change starting from 2027-01-01
            Long pVer1 = getProjectVersion(projectId);
            LocalDate newEffectiveFrom = LocalDate.of(2027, 1, 1);
            EffectiveCapacityChangeRequest changeReq = new EffectiveCapacityChangeRequest(
                    initialCapId,
                    initialCapVer,
                    newEffectiveFrom,
                    List.of(new SkillLevel("frontend", 4), new SkillLevel("team-lead", 3)),
                    createStandardWeekdaySlots(),
                    List.of(),
                    480,
                    new BigDecimal("80.00"), // allocation changed to 80%
                    "Promotion to Tech Lead with adjusted allocation",
                    pVer1
            );

            MvcResult changeRes = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources/" + devId + "/effective-changes")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(changeReq)))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode changeNode = json.readTree(changeRes.getResponse().getContentAsString());
            UUID newCapId = UUID.fromString(changeNode.get("id").asText());
            assertThat(newCapId).isNotEqualTo(initialCapId);
            assertThat(changeNode.get("effectiveFrom").asText()).isEqualTo("2027-01-01");
            assertThat(changeNode.get("effectiveTo").isNull()).isTrue();
            assertThat(changeNode.get("allocationPct").asDouble()).isEqualTo(80.0);

            // 3. Verify in database: initial record's effective_to is now 2027-01-01
            Map<String, Object> oldRecord = jdbc.queryForMap(
                    "SELECT * FROM developer_capacity WHERE id = ?", initialCapId);
            assertThat(oldRecord.get("effective_to").toString()).isEqualTo("2027-01-01");

            // 4. Listing capacities returns both intervals non-overlapping
            MvcResult listRes = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .param("developerId", devId.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode listNode = json.readTree(listRes.getResponse().getContentAsString());
            assertThat(listNode).hasSize(2);
            assertThat(listNode.get(0).get("effectiveFrom").asText()).isEqualTo("2026-12-01");
            assertThat(listNode.get(0).get("effectiveTo").asText()).isEqualTo("2027-01-01");
            assertThat(listNode.get(1).get("effectiveFrom").asText()).isEqualTo("2027-01-01");
            assertThat(listNode.get(1).get("effectiveTo").isNull()).isTrue();
        }
    }

    // =========================================================================
    // 4. Time-Off Appending Tests
    // =========================================================================

    @Nested
    @DisplayName("Time-Off Appending Tests")
    class TimeOffAppendingTests {

        @Test
        @DisplayName("Appending time off updates intersecting capacity record; overlapping time off is rejected with 409 TIME_OFF_OVERLAP")
        void appendTimeOffUpdatesRecordAndRejectsOverlap() throws Exception {
            UUID ownerId = createUser("pm-toff@example.test", "PM Toff", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-toff@example.test", "Dev Toff", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "ws-toff", "Workspace Toff");
            addWorkspaceMember(workspaceId, devId, "MEMBER");
            UUID projectId = createProject(workspaceId, "TOFF", "Toff Project", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            Long pVer0 = getProjectVersion(projectId);

            // Create capacity
            PutResourceCapacityRequest initReq = new PutResourceCapacityRequest(
                    devId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("backend", 4)),
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
                            .content(json.writeValueAsString(initReq)))
                    .andExpect(status().isCreated());

            // 1. Add time off: 2026-12-10 09:00:00Z to 2026-12-10 17:00:00Z
            Long pVer1 = getProjectVersion(projectId);
            Instant to1Start = Instant.parse("2026-12-10T09:00:00Z");
            Instant to1End = Instant.parse("2026-12-10T17:00:00Z");
            AddTimeOffRequest timeOffReq1 = new AddTimeOffRequest(
                    devId,
                    to1Start,
                    to1End,
                    List.of(),
                    "Vacation Day",
                    pVer1
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/time-off")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(timeOffReq1)))
                    .andExpect(status().isOk());

            // Verify in DB that time_off array contains the interval
            String timeOffJson = jdbc.queryForObject(
                    "SELECT time_off::text FROM developer_capacity WHERE project_id = ? AND user_id = ?",
                    String.class, projectId, devId
            );
            assertThat(timeOffJson).contains("2026-12-10T09:00:00Z");

            // 2. Attempt to add overlapping time off: 2026-12-10 14:00:00Z to 2026-12-10 18:00:00Z -> rejected with 409 TIME_OFF_OVERLAP
            Long pVer2 = getProjectVersion(projectId);
            AddTimeOffRequest timeOffReqOverlap = new AddTimeOffRequest(
                    devId,
                    Instant.parse("2026-12-10T14:00:00Z"),
                    Instant.parse("2026-12-10T18:00:00Z"),
                    List.of(),
                    "Overlapping Doctor Appointment",
                    pVer2
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/time-off")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(timeOffReqOverlap)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TIME_OFF_OVERLAP"));
        }
    }

    // =========================================================================
    // 5. Calendar Expansion Tests
    // =========================================================================

    @Nested
    @DisplayName("Calendar Expansion Tests")
    class CalendarExpansionTests {

        @Test
        @DisplayName("GET /calendar returns daily available minutes subtracting time off from weekly slots according to project timezone")
        void calendarExpansionSubtractsTimeOffCorrectly() throws Exception {
            UUID ownerId = createUser("pm-cal@example.test", "PM Cal", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(ownerId);
            UUID devId = createUser("dev-cal@example.test", "Dev Cal", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "ws-cal", "Workspace Cal");
            addWorkspaceMember(workspaceId, devId, "MEMBER");
            UUID projectId = createProject(workspaceId, "CAL", "Cal Project", ownerId);
            addProjectMemberWithRole(projectId, workspaceId, devId, "DEVELOPER", ownerId);

            Long pVer0 = getProjectVersion(projectId);

            // Project timezone is 'Asia/Ho_Chi_Minh' (UTC+7).
            // Friday, 2026-12-04:
            // Standard slot: 9:00 to 17:00 local time (540 to 1020 minutes) = 480 minutes.
            // In UTC: 9:00 local is 02:00:00Z, 17:00 local is 10:00:00Z.
            // Add time off: 13:00 to 15:00 local time (06:00:00Z to 08:00:00Z) = 120 minutes of time off.
            // Expected available minutes for Friday 2026-12-04: 480 - 120 = 360 minutes!
            TimeOff fridayTimeOff = new TimeOff(
                    Instant.parse("2026-12-04T06:00:00Z"),
                    Instant.parse("2026-12-04T08:00:00Z")
            );

            PutResourceCapacityRequest req = new PutResourceCapacityRequest(
                    devId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("fullstack", 5)),
                    createStandardWeekdaySlots(),
                    List.of(fridayTimeOff),
                    480,
                    new BigDecimal("100.00"),
                    pVer0,
                    null
            );

            mvc.perform(put("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isCreated());

            // Query calendar for 2026-12-03 (Thursday) to 2026-12-06 (Sunday)
            MvcResult calRes = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/resources/" + devId + "/calendar")
                            .param("from", "2026-12-03")
                            .param("to", "2026-12-06")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode calNode = json.readTree(calRes.getResponse().getContentAsString());
            assertThat(calNode.get("timezone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
            JsonNode days = calNode.get("days");
            assertThat(days).hasSize(4);

            // Day 0: Thursday 2026-12-03 (Working day, no time off)
            JsonNode thursday = days.get(0);
            assertThat(thursday.get("date").asText()).isEqualTo("2026-12-03");
            assertThat(thursday.get("rawAvailableMinutes").asInt()).isEqualTo(480);
            assertThat(thursday.get("dailyBudgetMinutes").asInt()).isEqualTo(480);

            // Day 1: Friday 2026-12-04 (Working day with 2 hours time off: 480 - 120 = 360 minutes)
            JsonNode friday = days.get(1);
            assertThat(friday.get("date").asText()).isEqualTo("2026-12-04");
            assertThat(friday.get("rawAvailableMinutes").asInt()).isEqualTo(360);
            assertThat(friday.get("dailyBudgetMinutes").asInt()).isEqualTo(480);
            assertThat(friday.get("availableSegments")).hasSize(2); // Split into 9:00-13:00 and 15:00-17:00

            // Day 2: Saturday 2026-12-05 (Weekend, non-working day)
            JsonNode saturday = days.get(2);
            assertThat(saturday.get("date").asText()).isEqualTo("2026-12-05");
            assertThat(saturday.get("rawAvailableMinutes").asInt()).isEqualTo(0);
            assertThat(saturday.get("exclusions").toString()).contains("Non-working day");

            // Day 3: Sunday 2026-12-06 (Weekend, non-working day)
            JsonNode sunday = days.get(3);
            assertThat(sunday.get("date").asText()).isEqualTo("2026-12-06");
            assertThat(sunday.get("rawAvailableMinutes").asInt()).isEqualTo(0);
            assertThat(sunday.get("exclusions").toString()).contains("Non-working day");
        }
    }
}
