package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.clientportal.api.dto.PublishMilestoneRequest;
import com.project.devsphere.clientportal.api.dto.ReviewMilestoneRequest;
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

import java.security.SecureRandom;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
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
public class ClientPortalIntegrationTest {

    @Autowired private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @MockitoBean private JavaMailSender mailSender;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] sessionKey = new byte[32];
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        sessionKey[0] = 73;
        hmac[0] = 31;
        encryption[0] = 43;
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

    // --- Helpers ---

    private String issueAccessToken(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusDays(7);
        byte[] refreshHashBytes = new byte[32];
        new SecureRandom().nextBytes(refreshHashBytes);
        String refreshHash = Base64.getEncoder().encodeToString(refreshHashBytes);

        jdbc.update(
                "INSERT INTO auth_sessions (id, user_id, refresh_hash, issued_at, expires_at, session_family_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessionId, userId, refreshHash, now, expiresAt, familyId, now
        );

        return sessionTokens.issueAccess(userId, sessionId).value();
    }

    private UUID createUser(String email, String displayName, String status, String systemRole) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, password_hash, system_role, status, email_verified_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'argon2_dummy_hash', ?, ?, ?, ?, ?, 0)",
                id, email, displayName, systemRole, status, now, now, now
        );
        return id;
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
                    "INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, committed_deadline, sprint_length_days, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'Enterprise Project Description', 'Milestone Objective', 'PLANNING', 'UTC', '2026-12-01', '2026-12-31', 14, ?, ?, 0)",
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
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
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
            jdbc.update(
                    "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), projectId, memberId, role, grantedBy, now, userId, now
            );
        });
    }

    private UUID createMilestone(
            UUID projectId,
            String code,
            String name,
            LocalDate dueDate,
            String status,
            String acceptanceStatus,
            boolean publishedToClient) {

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO milestones (
                    id, project_id, code, name, description, due_date, forecast_date,
                    status, acceptance_status, published_to_client, acceptance_history,
                    created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, ?, 'Milestone Description', ?, ?,
                    ?, ?, ?, '[]'::jsonb,
                    ?, ?, 0
                )
                """,
                id, projectId, code, name, Date.valueOf(dueDate), Date.valueOf(dueDate),
                status, acceptanceStatus, publishedToClient, now, now
        );
        return id;
    }

    private UUID createReport(
            UUID projectId,
            String type,
            LocalDate start,
            LocalDate end,
            String status,
            boolean visibleToClient,
            UUID generatedBy,
            UUID approvedBy) {

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime pubAt = "PUBLISHED".equals(status) ? now : null;

        jdbc.update("""
                INSERT INTO reports (
                    id, project_id, type, period_start, period_end, status, content,
                    visible_to_client, generated_by, approved_by, published_at,
                    created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, '{"summary":"Sprint Report","velocity":42}'::jsonb,
                    ?, ?, ?, ?,
                    ?, ?, 0
                )
                """,
                id, projectId, type, Date.valueOf(start), Date.valueOf(end), status,
                visibleToClient, generatedBy, approvedBy, pubAt, now, now
        );
        return id;
    }

    // =========================================================================
    // 1. Zero Data Leak Invariant Tests
    // =========================================================================
    @Nested
    @DisplayName("1. Zero Data Leak Invariant Tests")
    class ZeroDataLeakTests {

        @Test
        @DisplayName("Client overview endpoint returns only published milestones and reports, strictly hiding internal data")
        void testClientOverviewZeroDataLeak() throws Exception {
            UUID owner = createUser("owner-leak@example.test", "Owner Leak", "ACTIVE", "USER");
            UUID pm = createUser("pm-leak@example.test", "PM Leak", "ACTIVE", "USER");
            UUID client = createUser("client-leak@example.test", "Client Leak", "ACTIVE", "USER");
            UUID dev = createUser("dev-leak@example.test", "Dev Leak", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-leak", "Workspace Leak");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");
            addWorkspaceMember(workspaceId, dev, "MEMBER");

            UUID projectId = createProject(workspaceId, "LEAK", "Project Leak Proof", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);
            addProjectMemberWithRole(projectId, workspaceId, dev, "DEVELOPER", pm);

            // 1. Milestones: 1 published, 1 internal
            UUID publishedM = createMilestone(projectId, "M1", "Release V1", LocalDate.of(2026, 12, 15), "PLANNED", "NOT_SUBMITTED", true);
            UUID internalM = createMilestone(projectId, "M2-INT", "Internal Architecture Refactor", LocalDate.of(2026, 12, 20), "PLANNED", "NOT_SUBMITTED", false);

            // 2. Reports: 1 published & visible, 1 internal draft, 1 internal approved
            UUID pubReport = createReport(projectId, "WEEKLY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7), "PUBLISHED", true, pm, pm);
            UUID draftReport = createReport(projectId, "WEEKLY", LocalDate.of(2026, 12, 8), LocalDate.of(2026, 12, 14), "DRAFT", false, pm, null);
            UUID intApprovedReport = createReport(projectId, "RISK", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 15), "APPROVED", false, pm, pm);

            // 3. Populate internal data that must NEVER leak to Client:
            // a) Developer capacity
            jdbc.update("""
                    INSERT INTO developer_capacity (
                        id, project_id, user_id, minutes_per_workday, allocation_pct,
                        skills, weekly_slots, time_off, effective_from, effective_to, created_at, updated_at, version
                    ) VALUES (
                        gen_random_uuid(), ?, ?, 480, 100,
                        '[{"skill":"confidential-kernel","level":5}]'::jsonb,
                        '[{"dayOfWeek":1,"startMinute":540,"endMinute":1020}]'::jsonb,
                        '[]'::jsonb, '2026-11-01', '2027-06-01', now(), now(), 0
                    )
                    """, projectId, dev);

            // b) Chat channel & internal message
            UUID channelId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO chat_channels (id, workspace_id, project_id, type, name, created_by, created_at, updated_at, version)
                    VALUES (?, ?, ?, 'PROJECT', 'internal-pm-dev', ?, now(), now(), 0)
                    """, channelId, workspaceId, projectId, pm);

            jdbc.update("""
                    INSERT INTO chat_channel_members (id, workspace_id, project_id, channel_id, user_id, joined_at, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, now(), now(), now(), 0)
                    """, workspaceId, projectId, channelId, pm);

            jdbc.update("""
                    INSERT INTO chat_messages (id, workspace_id, project_id, channel_id, sender_id, sender_kind, body, provenance, sent_at, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, 'USER', 'Confidential salary discussion and client negotiation', '{"schemaVersion":1}'::jsonb, now(), now(), now(), 0)
                    """, workspaceId, projectId, channelId, pm);

            // Call GET Client Portal Overview as CLIENT
            String clientToken = issueAccessToken(client);

            MvcResult result = mvc.perform(get("/api/v1/client-portal/workspaces/{w}/projects/{p}/overview", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                    .andExpect(jsonPath("$.publishedMilestonesCount").value(1))
                    .andExpect(jsonPath("$.acceptedMilestonesCount").value(0))
                    .andExpect(jsonPath("$.publishedReportsCount").value(1))
                    .andExpect(jsonPath("$.milestones[0].id").value(publishedM.toString()))
                    .andExpect(jsonPath("$.milestones[0].code").value("M1"))
                    .andExpect(jsonPath("$.reports[0].id").value(pubReport.toString()))
                    .andReturn();

            String responseBody = result.getResponse().getContentAsString();

            // Strict Zero Data Leak Assertions:
            // 1. Unpublished milestone M2 must NOT appear
            assertThat(responseBody).doesNotContain(internalM.toString());
            assertThat(responseBody).doesNotContain("Internal Architecture Refactor");

            // 2. Draft and internal reports must NOT appear
            assertThat(responseBody).doesNotContain(draftReport.toString());
            assertThat(responseBody).doesNotContain(intApprovedReport.toString());

            // 3. Absolute information barrier: no internal messages, capacity, or skills
            assertThat(responseBody).doesNotContain("confidential-kernel");
            assertThat(responseBody).doesNotContain("Confidential salary discussion");
            assertThat(responseBody).doesNotContain("minutesPerWorkday");
            assertThat(responseBody).doesNotContain("weeklySlots");
        }

        @Test
        @DisplayName("Unrelated external user receives 403 Forbidden when requesting Client Portal overview")
        void testExternalUserForbiddenFromClientPortal() throws Exception {
            UUID owner = createUser("owner-ext@example.test", "Owner Ext", "ACTIVE", "USER");
            UUID pm = createUser("pm-ext@example.test", "PM Ext", "ACTIVE", "USER");
            UUID stranger = createUser("stranger@example.test", "Stranger", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-ext", "Workspace Ext");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            UUID projectId = createProject(workspaceId, "EXT", "Project Ext", pm);

            String strangerToken = issueAccessToken(stranger);

            mvc.perform(get("/api/v1/client-portal/workspaces/{w}/projects/{p}/overview", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // 2. Milestone Review Protocol Tests (API Layer)
    // =========================================================================
    @Nested
    @DisplayName("2. Milestone Review Protocol API Tests")
    class MilestoneReviewProtocolTests {

        @Test
        @DisplayName("CLIENT role reviews and accepts published milestone successfully (200 OK)")
        void testClientCanReviewAndAcceptPublishedMilestone() throws Exception {
            UUID owner = createUser("owner-rev1@example.test", "Owner Rev1", "ACTIVE", "USER");
            UUID pm = createUser("pm-rev1@example.test", "PM Rev1", "ACTIVE", "USER");
            UUID client = createUser("client-rev1@example.test", "Client Rev1", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-rev1", "Workspace Rev1");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");

            UUID projectId = createProject(workspaceId, "REV1", "Project Rev1", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);

            UUID milestoneId = createMilestone(projectId, "M-REV1", "Alpha Delivery", LocalDate.of(2026, 12, 10), "READY_FOR_ACCEPTANCE", "PENDING_REVIEW", true);

            String clientToken = issueAccessToken(client);
            ReviewMilestoneRequest request = new ReviewMilestoneRequest("ACCEPTED", "All deliverables verified and accepted");

            mvc.perform(post("/api/v1/client-portal/workspaces/{w}/projects/{p}/milestones/{m}/review", workspaceId, projectId, milestoneId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.milestoneId").value(milestoneId.toString()))
                    .andExpect(jsonPath("$.decision").value("ACCEPTED"))
                    .andExpect(jsonPath("$.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.acceptanceStatus").value("ACCEPTED"))
                    .andExpect(jsonPath("$.reviewedBy").value(client.toString()));

            // Database state verification
            var row = jdbc.queryForMap(
                    "SELECT status, acceptance_status, accepted_by, accepted_at, acceptance_history FROM milestones WHERE id = ?",
                    milestoneId
            );
            assertThat(row.get("status")).isEqualTo("ACCEPTED");
            assertThat(row.get("acceptance_status")).isEqualTo("ACCEPTED");
            assertThat(row.get("accepted_by")).isEqualTo(client);
            assertThat(row.get("accepted_at")).isNotNull();
            assertThat(row.get("acceptance_history").toString()).contains("All deliverables verified and accepted");
        }

        @Test
        @DisplayName("CLIENT role reviews and requests changes on published milestone (200 OK)")
        void testClientCanRequestChangesOnPublishedMilestone() throws Exception {
            UUID owner = createUser("owner-rev2@example.test", "Owner Rev2", "ACTIVE", "USER");
            UUID pm = createUser("pm-rev2@example.test", "PM Rev2", "ACTIVE", "USER");
            UUID client = createUser("client-rev2@example.test", "Client Rev2", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-rev2", "Workspace Rev2");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");

            UUID projectId = createProject(workspaceId, "REV2", "Project Rev2", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);

            UUID milestoneId = createMilestone(projectId, "M-REV2", "Beta Delivery", LocalDate.of(2026, 12, 20), "READY_FOR_ACCEPTANCE", "PENDING_REVIEW", true);

            String clientToken = issueAccessToken(client);
            ReviewMilestoneRequest request = new ReviewMilestoneRequest("CHANGES_REQUESTED", "Missing export to PDF feature");

            mvc.perform(post("/api/v1/client-portal/workspaces/{w}/projects/{p}/milestones/{m}/review", workspaceId, projectId, milestoneId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decision").value("CHANGES_REQUESTED"))
                    .andExpect(jsonPath("$.acceptanceStatus").value("CHANGES_REQUESTED"));

            String acceptanceStatus = jdbc.queryForObject("SELECT acceptance_status FROM milestones WHERE id = ?", String.class, milestoneId);
            assertThat(acceptanceStatus).isEqualTo("CHANGES_REQUESTED");
        }

        @Test
        @DisplayName("Non-CLIENT roles (PM, DEVELOPER) are rejected from reviewing milestone (403 Forbidden)")
        void testNonClientRolesForbiddenFromReviewingMilestone() throws Exception {
            UUID owner = createUser("owner-rev3@example.test", "Owner Rev3", "ACTIVE", "USER");
            UUID pm = createUser("pm-rev3@example.test", "PM Rev3", "ACTIVE", "USER");
            UUID dev = createUser("dev-rev3@example.test", "Dev Rev3", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-rev3", "Workspace Rev3");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, dev, "MEMBER");

            UUID projectId = createProject(workspaceId, "REV3", "Project Rev3", pm);
            addProjectMemberWithRole(projectId, workspaceId, dev, "DEVELOPER", pm);

            UUID milestoneId = createMilestone(projectId, "M-REV3", "Delivery", LocalDate.of(2026, 12, 25), "READY_FOR_ACCEPTANCE", "PENDING_REVIEW", true);

            ReviewMilestoneRequest request = new ReviewMilestoneRequest("ACCEPTED", "Self approval attempt");

            // 1. PM attempt -> 403 Forbidden
            String pmToken = issueAccessToken(pm);
            mvc.perform(post("/api/v1/client-portal/workspaces/{w}/projects/{p}/milestones/{m}/review", workspaceId, projectId, milestoneId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            // 2. Developer attempt -> 403 Forbidden
            String devToken = issueAccessToken(dev);
            mvc.perform(post("/api/v1/client-portal/workspaces/{w}/projects/{p}/milestones/{m}/review", workspaceId, projectId, milestoneId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Reviewing an unpublished milestone is rejected (403 Forbidden / MILESTONE_NOT_PUBLISHED)")
        void testReviewingUnpublishedMilestoneRejected() throws Exception {
            UUID owner = createUser("owner-rev4@example.test", "Owner Rev4", "ACTIVE", "USER");
            UUID pm = createUser("pm-rev4@example.test", "PM Rev4", "ACTIVE", "USER");
            UUID client = createUser("client-rev4@example.test", "Client Rev4", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-rev4", "Workspace Rev4");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");

            UUID projectId = createProject(workspaceId, "REV4", "Project Rev4", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);

            // Unpublished milestone
            UUID unpublishedM = createMilestone(projectId, "M-REV4", "Secret Delivery", LocalDate.of(2026, 12, 30), "READY_FOR_ACCEPTANCE", "PENDING_REVIEW", false);

            String clientToken = issueAccessToken(client);
            ReviewMilestoneRequest request = new ReviewMilestoneRequest("ACCEPTED", "Trying to accept unpublished");

            mvc.perform(post("/api/v1/client-portal/workspaces/{w}/projects/{p}/milestones/{m}/review", workspaceId, projectId, unpublishedM)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MILESTONE_NOT_PUBLISHED"));
        }

        @Test
        @DisplayName("PM can publish milestone via API; CLIENT cannot publish (403)")
        void testPublishMilestoneAccess() throws Exception {
            UUID owner = createUser("owner-pub@example.test", "Owner Pub", "ACTIVE", "USER");
            UUID pm = createUser("pm-pub@example.test", "PM Pub", "ACTIVE", "USER");
            UUID client = createUser("client-pub@example.test", "Client Pub", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-pub", "Workspace Pub");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");

            UUID projectId = createProject(workspaceId, "PUB", "Project Pub", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);

            UUID milestoneId = createMilestone(projectId, "M-PUB", "Milestone Pub", LocalDate.of(2026, 12, 31), "PLANNED", "NOT_SUBMITTED", false);

            // 1. Client cannot publish -> 403 Forbidden
            String clientToken = issueAccessToken(client);
            mvc.perform(patch("/api/v1/workspaces/{w}/projects/{p}/milestones/{m}/publish", workspaceId, projectId, milestoneId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new PublishMilestoneRequest(true))))
                    .andExpect(status().isForbidden());

            // 2. PM can publish -> 200 OK
            String pmToken = issueAccessToken(pm);
            mvc.perform(patch("/api/v1/workspaces/{w}/projects/{p}/milestones/{m}/publish", workspaceId, projectId, milestoneId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new PublishMilestoneRequest(true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.publishedToClient").value(true));

            Boolean published = jdbc.queryForObject("SELECT published_to_client FROM milestones WHERE id = ?", Boolean.class, milestoneId);
            assertThat(published).isTrue();
        }
    }

    // =========================================================================
    // 3. Database Trigger Invariants Tests (ds_milestone_acceptance)
    // =========================================================================
    @Nested
    @DisplayName("3. Database Trigger Invariants Tests (ds_milestone_acceptance)")
    class DatabaseTriggerInvariantsTests {

        @Test
        @DisplayName("Trigger ds_milestone_acceptance rejects acceptance when published_to_client = false (DB error 23514)")
        void testDirectDbAcceptanceFailsWhenUnpublished() {
            UUID owner = createUser("owner-trig1@example.test", "Owner Trig1", "ACTIVE", "USER");
            UUID pm = createUser("pm-trig1@example.test", "PM Trig1", "ACTIVE", "USER");
            UUID client = createUser("client-trig1@example.test", "Client Trig1", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-trig1", "Workspace Trig1");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");

            UUID projectId = createProject(workspaceId, "TR1", "Project Trig1", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);

            UUID milestoneId = createMilestone(projectId, "M-TR1", "Unpublished M", LocalDate.of(2026, 12, 10), "READY_FOR_ACCEPTANCE", "NOT_SUBMITTED", false);

            // Attempt direct SQL acceptance while published_to_client = false
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE milestones
                    SET status = 'ACCEPTED',
                        acceptance_status = 'ACCEPTED',
                        accepted_by = ?,
                        accepted_at = now()
                    WHERE id = ?
                    """, client, milestoneId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Milestone acceptance requires published scope and CLIENT");
        }

        @Test
        @DisplayName("Trigger ds_milestone_acceptance rejects acceptance when accepted_by is PM (not CLIENT) (DB error 23514)")
        void testDirectDbAcceptanceFailsWhenAcceptedByPm() {
            UUID owner = createUser("owner-trig2@example.test", "Owner Trig2", "ACTIVE", "USER");
            UUID pm = createUser("pm-trig2@example.test", "PM Trig2", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-trig2", "Workspace Trig2");
            addWorkspaceMember(workspaceId, pm, "MEMBER");

            UUID projectId = createProject(workspaceId, "TR2", "Project Trig2", pm);

            UUID milestoneId = createMilestone(projectId, "M-TR2", "Published M", LocalDate.of(2026, 12, 10), "READY_FOR_ACCEPTANCE", "NOT_SUBMITTED", true);

            // Attempt direct SQL acceptance with accepted_by = PM
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE milestones
                    SET status = 'ACCEPTED',
                        acceptance_status = 'ACCEPTED',
                        accepted_by = ?,
                        accepted_at = now()
                    WHERE id = ?
                    """, pm, milestoneId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Milestone acceptance requires published scope and CLIENT");
        }

        @Test
        @DisplayName("Check constraint enforces (status = 'ACCEPTED') = (acceptance_status = 'ACCEPTED')")
        void testStatusAndAcceptanceStatusCheckConstraint() {
            UUID owner = createUser("owner-trig3@example.test", "Owner Trig3", "ACTIVE", "USER");
            UUID pm = createUser("pm-trig3@example.test", "PM Trig3", "ACTIVE", "USER");
            UUID client = createUser("client-trig3@example.test", "Client Trig3", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-trig3", "Workspace Trig3");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");

            UUID projectId = createProject(workspaceId, "TR3", "Project Trig3", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);

            UUID milestoneId = createMilestone(projectId, "M-TR3", "Check M", LocalDate.of(2026, 12, 10), "READY_FOR_ACCEPTANCE", "NOT_SUBMITTED", true);

            // Mismatched: status='ACCEPTED' but acceptance_status='NOT_SUBMITTED'
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE milestones
                    SET status = 'ACCEPTED'
                    WHERE id = ?
                    """, milestoneId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Check constraint enforces accepted_by and accepted_at are both NOT NULL when acceptance_status = 'ACCEPTED'")
        void testAcceptedByAndAtRequiredWhenAccepted() {
            UUID owner = createUser("owner-trig4@example.test", "Owner Trig4", "ACTIVE", "USER");
            UUID pm = createUser("pm-trig4@example.test", "PM Trig4", "ACTIVE", "USER");
            UUID client = createUser("client-trig4@example.test", "Client Trig4", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-trig4", "Workspace Trig4");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");

            UUID projectId = createProject(workspaceId, "TR4", "Project Trig4", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);

            UUID milestoneId = createMilestone(projectId, "M-TR4", "Check Nulls M", LocalDate.of(2026, 12, 10), "READY_FOR_ACCEPTANCE", "NOT_SUBMITTED", true);

            // acceptance_status='ACCEPTED' but accepted_at is NULL
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE milestones
                    SET status = 'ACCEPTED',
                        acceptance_status = 'ACCEPTED',
                        accepted_by = ?,
                        accepted_at = NULL
                    WHERE id = ?
                    """, client, milestoneId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
