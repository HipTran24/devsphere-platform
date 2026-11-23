package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.reporting.api.dto.CreateReportRequest;
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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
public class ReportAndExportIntegrationTest {

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
        sessionKey[0] = 74;
        hmac[0] = 32;
        encryption[0] = 44;
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
                            "VALUES (?, ?, ?, ?, 'Project description', 'Project objective', 'PLANNING', 'UTC', '2026-12-01', '2026-12-31', 14, ?, ?, 0)",
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

    private UUID createAiJob(UUID workspaceId, UUID projectId, UUID requestedBy, String status, String validationStatus) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO ai_jobs (
                    id, workspace_id, project_id, requested_by, job_type, status,
                    model_name, connection_id, route_snapshot, prompt_version, output_schema_version,
                    input_refs, output_json, validation_status, validation_report, validation_attempts,
                    created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, ?, 'REPORT_SUMMARY', ?,
                    'gemini-1.5-pro', 'conn-1', '{"route":"default"}'::jsonb, 'v1', '1',
                    '{"schemaVersion":1}'::jsonb, '{"executive_summary":"Validated facts"}'::jsonb,
                    ?, '{"schemaVersion":1,"valid":true}'::jsonb, 1,
                    ?, ?, 0
                )
                """,
                id, workspaceId, projectId, requestedBy, status, validationStatus, now, now
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
            UUID approvedBy,
            UUID aiJobId) {

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime pubAt = "PUBLISHED".equals(status) ? now : null;

        jdbc.update("""
                INSERT INTO reports (
                    id, project_id, type, period_start, period_end, status, content,
                    visible_to_client, generated_by, approved_by, ai_job_id, published_at,
                    created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, '{"summary":"Sprint Velocity Report","metrics":{"points":42}}'::jsonb,
                    ?, ?, ?, ?, ?,
                    ?, ?, 0
                )
                """,
                id, projectId, type, Date.valueOf(start), Date.valueOf(end), status,
                visibleToClient, generatedBy, approvedBy, aiJobId, pubAt, now, now
        );
        return id;
    }

    // =========================================================================
    // 1. Report Lifecycle & Approval Trigger Tests (ds_report_approval)
    // =========================================================================
    @Nested
    @DisplayName("1. Report Lifecycle & Approval Trigger Tests (ds_report_approval)")
    class ReportLifecycleTests {

        @Test
        @DisplayName("PM creates draft report and approves it via API (200 OK)")
        void testCreateAndApproveReport() throws Exception {
            UUID owner = createUser("owner-rep1@example.test", "Owner Rep1", "ACTIVE", "USER");
            UUID pm = createUser("pm-rep1@example.test", "PM Rep1", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-rep1", "Workspace Rep1");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            UUID projectId = createProject(workspaceId, "REP1", "Project Rep1", pm);

            String pmToken = issueAccessToken(pm);

            // 1. Create DRAFT report
            CreateReportRequest createReq = new CreateReportRequest(
                    "WEEKLY",
                    LocalDate.of(2026, 12, 1),
                    LocalDate.of(2026, 12, 7),
                    Map.of("summary", "Weekly sprint progress", "storiesDone", 5),
                    null,
                    null
            );

            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reports", workspaceId, projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.type").value("WEEKLY"))
                    .andReturn();

            UUID reportId = UUID.fromString(json.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

            // 2. Approve report as PM
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/approve", workspaceId, projectId, reportId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.approvedBy").value(pm.toString()));

            var row = jdbc.queryForMap("SELECT status, approved_by FROM reports WHERE id = ?", reportId);
            assertThat(row.get("status")).isEqualTo("APPROVED");
            assertThat(row.get("approved_by")).isEqualTo(pm);
        }

        @Test
        @DisplayName("Non-PM roles (DEVELOPER, CLIENT) cannot approve reports (403 Forbidden)")
        void testNonPmCannotApproveReport() throws Exception {
            UUID owner = createUser("owner-rep2@example.test", "Owner Rep2", "ACTIVE", "USER");
            UUID pm = createUser("pm-rep2@example.test", "PM Rep2", "ACTIVE", "USER");
            UUID dev = createUser("dev-rep2@example.test", "Dev Rep2", "ACTIVE", "USER");
            UUID client = createUser("client-rep2@example.test", "Client Rep2", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-rep2", "Workspace Rep2");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, dev, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");

            UUID projectId = createProject(workspaceId, "REP2", "Project Rep2", pm);
            addProjectMemberWithRole(projectId, workspaceId, dev, "DEVELOPER", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);

            UUID reportId = createReport(projectId, "WEEKLY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7), "DRAFT", false, pm, null, null);

            // 1. Developer attempt
            String devToken = issueAccessToken(dev);
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/approve", workspaceId, projectId, reportId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isForbidden());

            // 2. Client attempt
            String clientToken = issueAccessToken(client);
            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/approve", workspaceId, projectId, reportId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Direct SQL approval with non-PM approved_by is rejected by trigger ds_report_approval (DB 23514)")
        void testDirectDbApprovalRejectsNonPm() {
            UUID owner = createUser("owner-rep3@example.test", "Owner Rep3", "ACTIVE", "USER");
            UUID pm = createUser("pm-rep3@example.test", "PM Rep3", "ACTIVE", "USER");
            UUID dev = createUser("dev-rep3@example.test", "Dev Rep3", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-rep3", "Workspace Rep3");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, dev, "MEMBER");

            UUID projectId = createProject(workspaceId, "REP3", "Project Rep3", pm);
            addProjectMemberWithRole(projectId, workspaceId, dev, "DEVELOPER", pm);

            UUID reportId = createReport(projectId, "SPRINT", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 14), "DRAFT", false, pm, null, null);

            // Attempt direct SQL approval with approved_by = dev
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE reports
                    SET status = 'APPROVED',
                        approved_by = ?
                    WHERE id = ?
                    """, dev, reportId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Report approval requires active PM");
        }

        @Test
        @DisplayName("PM can publish approved report via API (200 OK)")
        void testPublishReportLifecycle() throws Exception {
            UUID owner = createUser("owner-rep4@example.test", "Owner Rep4", "ACTIVE", "USER");
            UUID pm = createUser("pm-rep4@example.test", "PM Rep4", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-rep4", "Workspace Rep4");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            UUID projectId = createProject(workspaceId, "REP4", "Project Rep4", pm);

            UUID reportId = createReport(projectId, "WEEKLY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7), "APPROVED", false, pm, pm, null);

            String pmToken = issueAccessToken(pm);

            mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/publish", workspaceId, projectId, reportId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.visibleToClient").value(true))
                    .andExpect(jsonPath("$.publishedAt").isNotEmpty());

            var row = jdbc.queryForMap("SELECT status, visible_to_client, published_at FROM reports WHERE id = ?", reportId);
            assertThat(row.get("status")).isEqualTo("PUBLISHED");
            assertThat(row.get("visible_to_client")).isEqualTo(true);
            assertThat(row.get("published_at")).isNotNull();
        }
    }

    // =========================================================================
    // 2. AI Validation Gate In Approval Tests (ds_report_approval)
    // =========================================================================
    @Nested
    @DisplayName("2. AI Validation Gate In Approval Tests")
    class AiValidationGateTests {

        @Test
        @DisplayName("Approving report linked to unvalidated AI job fails (DB 23514 / AI report requires validated output)")
        void testApprovalFailsWhenAiJobNotValidated() {
            UUID owner = createUser("owner-ai1@example.test", "Owner AI1", "ACTIVE", "USER");
            UUID pm = createUser("pm-ai1@example.test", "PM AI1", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-ai1", "Workspace AI1");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            UUID projectId = createProject(workspaceId, "AI1", "Project AI1", pm);

            // 1. AI job with status RUNNING and validation_status PENDING
            UUID pendingAiJob = createAiJob(workspaceId, projectId, pm, "RUNNING", "PENDING");
            UUID report1 = createReport(projectId, "RISK", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 15), "DRAFT", false, pm, null, pendingAiJob);

            // Attempt direct SQL approval
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE reports
                    SET status = 'APPROVED',
                        approved_by = ?
                    WHERE id = ?
                    """, pm, report1))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("AI report requires validated output");

            // 2. AI job with status SUCCESS but validation_status INVALID
            UUID invalidAiJob = createAiJob(workspaceId, projectId, pm, "SUCCESS", "INVALID");
            UUID report2 = createReport(projectId, "RISK", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 15), "DRAFT", false, pm, null, invalidAiJob);

            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE reports
                    SET status = 'APPROVED',
                        approved_by = ?
                    WHERE id = ?
                    """, pm, report2))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("AI report requires validated output");
        }

        @Test
        @DisplayName("Approving report linked to SUCCESS + VALID AI job succeeds")
        void testApprovalSucceedsWhenAiJobValid() {
            UUID owner = createUser("owner-ai2@example.test", "Owner AI2", "ACTIVE", "USER");
            UUID pm = createUser("pm-ai2@example.test", "PM AI2", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-ai2", "Workspace AI2");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            UUID projectId = createProject(workspaceId, "AI2", "Project AI2", pm);

            UUID validAiJob = createAiJob(workspaceId, projectId, pm, "SUCCESS", "VALID");
            UUID report = createReport(projectId, "CLIENT_ROADMAP", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), "DRAFT", false, pm, null, validAiJob);

            // Approval succeeds!
            jdbc.update("""
                    UPDATE reports
                    SET status = 'APPROVED',
                        approved_by = ?
                    WHERE id = ?
                    """, pm, report);

            String status = jdbc.queryForObject("SELECT status FROM reports WHERE id = ?", String.class, report);
            assertThat(status).isEqualTo("APPROVED");
        }
    }

    // =========================================================================
    // 3. Content Modification Invariant Tests (ds_report_approval)
    // =========================================================================
    @Nested
    @DisplayName("3. Content Modification Resets to DRAFT Tests (ds_report_approval)")
    class ContentModificationTests {

        @Test
        @DisplayName("Editing content of APPROVED report automatically resets status to DRAFT and clears approved_by")
        void testContentEditResetsApprovedReportToDraft() {
            UUID owner = createUser("owner-mod1@example.test", "Owner Mod1", "ACTIVE", "USER");
            UUID pm = createUser("pm-mod1@example.test", "PM Mod1", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-mod1", "Workspace Mod1");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            UUID projectId = createProject(workspaceId, "MOD1", "Project Mod1", pm);

            UUID reportId = createReport(projectId, "DAILY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1), "APPROVED", false, pm, pm, null);

            // Mutate report content in DB
            jdbc.update("""
                    UPDATE reports
                    SET content = '{"summary":"Updated daily standup findings"}'::jsonb
                    WHERE id = ?
                    """, reportId);

            var row = jdbc.queryForMap("SELECT status, approved_by, published_at FROM reports WHERE id = ?", reportId);
            assertThat(row.get("status")).isEqualTo("DRAFT");
            assertThat(row.get("approved_by")).isNull();
            assertThat(row.get("published_at")).isNull();
        }

        @Test
        @DisplayName("Modifying period_end or type of APPROVED report automatically resets status to DRAFT")
        void testPeriodOrTypeEditResetsToDraft() {
            UUID owner = createUser("owner-mod2@example.test", "Owner Mod2", "ACTIVE", "USER");
            UUID pm = createUser("pm-mod2@example.test", "PM Mod2", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-mod2", "Workspace Mod2");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            UUID projectId = createProject(workspaceId, "MOD2", "Project Mod2", pm);

            UUID reportId = createReport(projectId, "WEEKLY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7), "APPROVED", false, pm, pm, null);

            // Change period_end
            jdbc.update("""
                    UPDATE reports
                    SET period_end = '2026-12-08'
                    WHERE id = ?
                    """, reportId);

            var row = jdbc.queryForMap("SELECT status, approved_by FROM reports WHERE id = ?", reportId);
            assertThat(row.get("status")).isEqualTo("DRAFT");
            assertThat(row.get("approved_by")).isNull();
        }
    }

    // =========================================================================
    // 4. Multi-format Export & Client Access Guard Tests
    // =========================================================================
    @Nested
    @DisplayName("4. Export Binary Generation & Client Access Guard Tests")
    class ExportAndAccessGuardTests {

        @Test
        @DisplayName("PM exports report to PDF; verifies valid PDF binary header (%PDF-)")
        void testExportPdfProducesValidPdfBinary() throws Exception {
            UUID owner = createUser("owner-exp1@example.test", "Owner Exp1", "ACTIVE", "USER");
            UUID pm = createUser("pm-exp1@example.test", "PM Exp1", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-exp1", "Workspace Exp1");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            UUID projectId = createProject(workspaceId, "EXP1", "Project Exp1", pm);

            UUID reportId = createReport(projectId, "WEEKLY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7), "APPROVED", false, pm, pm, null);

            String pmToken = issueAccessToken(pm);

            MvcResult result = mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/export", workspaceId, projectId, reportId)
                            .param("format", "PDF")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-" + reportId + ".pdf\""))
                    .andReturn();

            byte[] pdfBytes = result.getResponse().getContentAsByteArray();
            assertThat(pdfBytes).isNotEmpty();

            // Verify standard PDF magic header %PDF-
            String header = new String(pdfBytes, 0, Math.min(pdfBytes.length, 5), StandardCharsets.US_ASCII);
            assertThat(header).isEqualTo("%PDF-");
        }

        @Test
        @DisplayName("PM exports report to XLSX; verifies valid XLSX binary zip header (PK\\x03\\x04)")
        void testExportXlsxProducesValidXlsxBinary() throws Exception {
            UUID owner = createUser("owner-exp2@example.test", "Owner Exp2", "ACTIVE", "USER");
            UUID pm = createUser("pm-exp2@example.test", "PM Exp2", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-exp2", "Workspace Exp2");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            UUID projectId = createProject(workspaceId, "EXP2", "Project Exp2", pm);

            UUID reportId = createReport(projectId, "SPRINT", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 14), "APPROVED", false, pm, pm, null);

            String pmToken = issueAccessToken(pm);

            MvcResult result = mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/export", workspaceId, projectId, reportId)
                            .param("format", "XLSX")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-" + reportId + ".xlsx\""))
                    .andReturn();

            byte[] xlsxBytes = result.getResponse().getContentAsByteArray();
            assertThat(xlsxBytes).isNotEmpty();

            // Verify standard ZIP/XLSX magic header PK\x03\x04
            assertThat(xlsxBytes[0]).isEqualTo((byte) 'P');
            assertThat(xlsxBytes[1]).isEqualTo((byte) 'K');
            assertThat(xlsxBytes[2]).isEqualTo((byte) 0x03);
            assertThat(xlsxBytes[3]).isEqualTo((byte) 0x04);
        }

        @Test
        @DisplayName("Client can only export published reports (visible_to_client = true); unpublished returns 403")
        void testClientAccessGuardOnExport() throws Exception {
            UUID owner = createUser("owner-exp3@example.test", "Owner Exp3", "ACTIVE", "USER");
            UUID pm = createUser("pm-exp3@example.test", "PM Exp3", "ACTIVE", "USER");
            UUID client = createUser("client-exp3@example.test", "Client Exp3", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(owner, "ws-exp3", "Workspace Exp3");
            addWorkspaceMember(workspaceId, pm, "MEMBER");
            addWorkspaceMember(workspaceId, client, "MEMBER");

            UUID projectId = createProject(workspaceId, "EXP3", "Project Exp3", pm);
            addProjectMemberWithRole(projectId, workspaceId, client, "CLIENT", pm);

            // Report 1: Internal DRAFT (visible_to_client = false)
            UUID draftReport = createReport(projectId, "WEEKLY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7), "DRAFT", false, pm, null, null);

            // Report 2: Internal APPROVED but NOT published (visible_to_client = false)
            UUID approvedReport = createReport(projectId, "WEEKLY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7), "APPROVED", false, pm, pm, null);

            // Report 3: PUBLISHED and visible to client
            UUID publishedReport = createReport(projectId, "WEEKLY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7), "PUBLISHED", true, pm, pm, null);

            String clientToken = issueAccessToken(client);

            // 1. Client attempts to export DRAFT -> 403 Forbidden
            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/export", workspaceId, projectId, draftReport)
                            .param("format", "PDF")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                    .andExpect(status().isForbidden());

            // 2. Client attempts to export internal APPROVED -> 403 Forbidden
            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/export", workspaceId, projectId, approvedReport)
                            .param("format", "PDF")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                    .andExpect(status().isForbidden());

            // 3. Client exports PUBLISHED report -> 200 OK
            mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/export", workspaceId, projectId, publishedReport)
                            .param("format", "PDF")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE));
        }
    }
}
