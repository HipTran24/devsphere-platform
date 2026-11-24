package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.domain.SessionTokens;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
public class ReportS3ExportIntegrationTest {

    private static final HttpServer S3_SERVER;
    private static final int S3_PORT;
    private static final Map<String, byte[]> S3_STORE = new ConcurrentHashMap<>();
    private static String previousAccessKey;
    private static String previousSecretKey;

    static {
        try {
            S3_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            S3_PORT = S3_SERVER.getAddress().getPort();
            S3_SERVER.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if ("PUT".equalsIgnoreCase(method)) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    S3_STORE.put(path, body);
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                    return;
                }
                if ("HEAD".equalsIgnoreCase(method)) {
                    byte[] existing = S3_STORE.get(path);
                    if (existing != null) {
                        exchange.getResponseHeaders().add("Content-Length", String.valueOf(existing.length));
                        exchange.sendResponseHeaders(200, -1);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                    exchange.close();
                    return;
                }
                if ("GET".equalsIgnoreCase(method)) {
                    byte[] existing = S3_STORE.get(path);
                    if (existing != null) {
                        exchange.sendResponseHeaders(200, existing.length);
                        exchange.getResponseBody().write(existing);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            });
            S3_SERVER.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start embedded S3 HttpServer", ex);
        }
    }

    @BeforeAll
    static void installTestAwsCredentials() {
        previousAccessKey = System.getProperty("aws.accessKeyId");
        previousSecretKey = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE");
        System.setProperty("aws.secretAccessKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
    }

    @AfterAll
    static void cleanupServerAndCredentials() {
        S3_SERVER.stop(0);
        if (previousAccessKey == null) {
            System.clearProperty("aws.accessKeyId");
        } else {
            System.setProperty("aws.accessKeyId", previousAccessKey);
        }
        if (previousSecretKey == null) {
            System.clearProperty("aws.secretAccessKey");
        } else {
            System.setProperty("aws.secretAccessKey", previousSecretKey);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] sessionKey = new byte[32];
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        sessionKey[0] = 75;
        hmac[0] = 33;
        encryption[0] = 45;
        registry.add("devsphere.auth.session.signing-key", () -> Base64.getEncoder().encodeToString(sessionKey));
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
        registry.add("devsphere.storage.s3.mode", () -> "aws");
        registry.add("devsphere.storage.s3.endpoint", () -> "http://127.0.0.1:" + S3_PORT);
        registry.add("devsphere.storage.s3.path-style-access", () -> "true");
        registry.add("devsphere.storage.s3.allow-insecure-endpoint", () -> "true");
        registry.add("devsphere.storage.s3.verify-on-confirm", () -> "true");
        registry.add("devsphere.storage.s3.bucket", () -> "devsphere-attachments");
        registry.add("devsphere.storage.s3.region", () -> "us-east-1");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @MockitoBean private JavaMailSender mailSender;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void resetState() {
        S3_STORE.clear();
        var tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class
        );
        jdbc.execute("TRUNCATE TABLE " + String.join(",", tables) + " CASCADE");
    }

    private String issueAccessToken(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        byte[] refreshHashBytes = new byte[32];
        new SecureRandom().nextBytes(refreshHashBytes);
        String refreshHash = Base64.getEncoder().encodeToString(refreshHashBytes);
        jdbc.update(
                "INSERT INTO auth_sessions (id, user_id, refresh_hash, issued_at, expires_at, session_family_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessionId, userId, refreshHash, now, now.plusDays(7), familyId, now
        );
        return sessionTokens.issueAccess(userId, sessionId).value();
    }

    private UUID createUser(String email, String displayName) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, password_hash, system_role, status, email_verified_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'argon2_dummy_hash', 'USER', 'ACTIVE', ?, ?, ?, 0)",
                id, email, displayName, now, now, now
        );
        return id;
    }

    private UUID createWorkspace(UUID ownerId, String slug) {
        return tx.execute(s -> {
            UUID workspaceId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO workspaces (id, slug, name, owner_id, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'Workspace S3', ?, 'ACTIVE', ?, ?, 0)",
                    workspaceId, slug, ownerId, now, now
            );
            jdbc.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'OWNER', 'ACTIVE', ?, ?, ?, 0)",
                    workspaceId, ownerId, now, now, now
            );
            return workspaceId;
        });
    }

    private void addWorkspaceMember(UUID workspaceId, UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, 'MEMBER', 'ACTIVE', ?, ?, ?, 0)",
                workspaceId, userId, now, now, now
        );
    }

    private UUID createProject(UUID workspaceId, UUID pmUserId) {
        return tx.execute(s -> {
            UUID projectId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, committed_deadline, sprint_length_days, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'S3EXP', 'Project S3 Export', 'desc', 'obj', 'PLANNING', 'UTC', '2026-12-01', '2026-12-31', 14, ?, ?, 0)",
                    projectId, workspaceId, now, now
            );
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
            return projectId;
        });
    }

    private void addClientMember(UUID projectId, UUID workspaceId, UUID clientUserId, UUID grantedBy) {
        tx.executeWithoutResult(s -> {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID memberId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                    memberId, projectId, workspaceId, clientUserId, now, now, now
            );
            jdbc.update(
                    "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                            "VALUES (?, ?, ?, 'CLIENT', ?, ?, ?, ?)",
                    UUID.randomUUID(), projectId, memberId, grantedBy, now, clientUserId, now
            );
        });
    }

    private UUID insertReport(UUID projectId, String type, String status, boolean visibleToClient, UUID generatedBy, UUID approvedBy, String contentJson) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime pubAt = "PUBLISHED".equals(status) ? now : null;
        jdbc.update("""
                INSERT INTO reports (
                    id, project_id, type, period_start, period_end, status, content,
                    visible_to_client, generated_by, approved_by, published_at,
                    created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?::jsonb,
                    ?, ?, ?, ?,
                    ?, ?, 0
                )
                """,
                id, projectId, type, Date.valueOf(LocalDate.of(2026, 11, 1)), Date.valueOf(LocalDate.of(2026, 11, 14)),
                status, contentJson, visibleToClient, generatedBy, approvedBy, pubAt, now, now
        );
        return id;
    }

    @Test
    @DisplayName("CLIENT getReports returns real JSONB content and honors typeFilter")
    void clientGetReportsReturnsRealContentAndHonorsTypeFilter() throws Exception {
        UUID owner = createUser("owner-r1@example.test", "Owner");
        UUID pm = createUser("pm-r1@example.test", "PM");
        UUID client = createUser("client-r1@example.test", "Client");
        UUID workspaceId = createWorkspace(owner, "ws-r1");
        addWorkspaceMember(workspaceId, pm);
        addWorkspaceMember(workspaceId, client);
        UUID projectId = createProject(workspaceId, pm);
        addClientMember(projectId, workspaceId, client, pm);

        insertReport(projectId, "WEEKLY", "PUBLISHED", true, pm, pm,
                "{\"summary\":\"Real weekly milestone delivery\",\"metrics\":{\"velocity\":34}}");
        insertReport(projectId, "SPRINT", "PUBLISHED", true, pm, pm,
                "{\"summary\":\"Real sprint 8 wrap-up\",\"metrics\":{\"completed\":15}}");
        insertReport(projectId, "WEEKLY", "DRAFT", false, pm, null,
                "{\"summary\":\"Internal draft hidden from client\"}");

        String clientToken = issueAccessToken(client);

        mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/reports", workspaceId, projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.type=='WEEKLY')].content.summary").value("Real weekly milestone delivery"))
                .andExpect(jsonPath("$[?(@.type=='SPRINT')].content.summary").value("Real sprint 8 wrap-up"))
                .andExpect(jsonPath("$[?(@.type=='SPRINT')].content.metrics.completed").value(15));

        mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/reports", workspaceId, projectId)
                        .param("type", "SPRINT")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("SPRINT"))
                .andExpect(jsonPath("$[0].content.summary").value("Real sprint 8 wrap-up"));
    }

    @Test
    @DisplayName("POST .../export-attachment uploads PDF & XLSX to S3 mock, persists REPORT_EXPORT attachment with matching SHA-256, and returns presigned downloadUrl")
    void exportAttachmentPdfAndXlsxUploadsToS3AndPersistsAttachment() throws Exception {
        UUID owner = createUser("owner-r2@example.test", "Owner");
        UUID pm = createUser("pm-r2@example.test", "PM");
        UUID client = createUser("client-r2@example.test", "Client");
        UUID workspaceId = createWorkspace(owner, "ws-r2");
        addWorkspaceMember(workspaceId, pm);
        addWorkspaceMember(workspaceId, client);
        UUID projectId = createProject(workspaceId, pm);
        addClientMember(projectId, workspaceId, client, pm);

        UUID publishedReport = insertReport(projectId, "SPRINT", "PUBLISHED", true, pm, pm,
                "{\"summary\":\"Published sprint report\",\"metrics\":{\"velocity\":42,\"formula\":\"=CMD()\"}}");
        UUID draftReport = insertReport(projectId, "RISK", "DRAFT", false, pm, null,
                "{\"summary\":\"Internal risk draft\"}");

        String pmToken = issueAccessToken(pm);
        String clientToken = issueAccessToken(client);

        // 1. Export published report as PDF -> uploaded to S3, CLIENT_SHARED, CLEAN
        MvcResult pdfRes = mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/export-attachment",
                        workspaceId, projectId, publishedReport)
                        .param("format", "PDF")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value(publishedReport.toString()))
                .andExpect(jsonPath("$.format").value("PDF"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                .andReturn();

        JsonNode pdfJson = json.readTree(pdfRes.getResponse().getContentAsString());
        UUID pdfAttachmentId = UUID.fromString(pdfJson.get("attachmentId").asText());
        String pdfSha = pdfJson.get("sha256").asText();
        String pdfDownloadUrl = pdfJson.get("downloadUrl").asText();
        assertThat(pdfDownloadUrl).startsWith("http://127.0.0.1:" + S3_PORT + "/devsphere-attachments/");
        assertThat(pdfDownloadUrl).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256");

        var pdfRow = jdbc.queryForMap("SELECT kind, storage_key, sha256, file_size, visibility, scan_status, report_id FROM attachments WHERE id = ?", pdfAttachmentId);
        assertThat(pdfRow.get("kind")).isEqualTo("REPORT_EXPORT");
        assertThat(pdfRow.get("visibility")).isEqualTo("CLIENT_SHARED");
        assertThat(pdfRow.get("scan_status")).isEqualTo("CLEAN");
        assertThat(pdfRow.get("report_id")).isEqualTo(publishedReport);
        assertThat(pdfRow.get("sha256")).isEqualTo(pdfSha);

        byte[] storedPdf = S3_STORE.get("/devsphere-attachments/" + pdfRow.get("storage_key"));
        assertThat(storedPdf).isNotNull();
        assertThat(sha256Hex(storedPdf)).isEqualTo(pdfSha);
        assertThat(new String(storedPdf, StandardCharsets.US_ASCII))
                .startsWith("%PDF-1.4\n")
                .endsWith("%%EOF\n")
                .contains("Published sprint report");

        // 2. Export published report as XLSX -> CWE-1236 neutralized in S3-stored sheet1.xml
        MvcResult xlsxRes = mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/export-attachment",
                        workspaceId, projectId, publishedReport)
                        .param("format", "XLSX")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.format").value("XLSX"))
                .andExpect(jsonPath("$.contentType").value("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn();

        JsonNode xlsxJson = json.readTree(xlsxRes.getResponse().getContentAsString());
        UUID xlsxAttachmentId = UUID.fromString(xlsxJson.get("attachmentId").asText());
        var xlsxRow = jdbc.queryForMap("SELECT storage_key, sha256 FROM attachments WHERE id = ?", xlsxAttachmentId);
        byte[] storedXlsx = S3_STORE.get("/devsphere-attachments/" + xlsxRow.get("storage_key"));
        assertThat(storedXlsx).isNotNull();
        assertThat(sha256Hex(storedXlsx)).isEqualTo(xlsxRow.get("sha256"));
        assertThat(readZipEntry(storedXlsx, "xl/worksheets/sheet1.xml")).contains("&apos;=CMD()");

        // 3. CLIENT blocked on draft export-attachment, allowed on published download
        mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/reports/{r}/export-attachment",
                        workspaceId, projectId, draftReport)
                        .param("format", "PDF")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/workspaces/{w}/projects/{p}/attachments/{a}/download",
                        workspaceId, projectId, pdfAttachmentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentId").value(pdfAttachmentId.toString()))
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty());
    }

    @Test
    @DisplayName("confirmUpload in aws + verifyOnConfirm=true mode rejects missing S3 object (409) and passes once uploaded")
    void confirmUploadVerifiesRemoteS3Object() throws Exception {
        UUID owner = createUser("owner-r3@example.test", "Owner");
        UUID pm = createUser("pm-r3@example.test", "PM");
        UUID workspaceId = createWorkspace(owner, "ws-r3");
        addWorkspaceMember(workspaceId, pm);
        UUID projectId = createProject(workspaceId, pm);
        UUID reportId = insertReport(projectId, "WEEKLY", "DRAFT", false, pm, null, "{\"summary\":\"Report\"}");

        String pmToken = issueAccessToken(pm);
        byte[] payload = "report-bytes".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);

        MvcResult uploadReqRes = mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/attachments/upload-request",
                        workspaceId, projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                        .contentType("application/json")
                        .content("""
                                {
                                  "filename": "notes.txt",
                                  "contentType": "text/plain",
                                  "fileSize": %d,
                                  "sha256": "%s",
                                  "kind": "REPORT_EXPORT",
                                  "parentId": "%s"
                                }
                                """.formatted(payload.length, sha, reportId)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode reqJson = json.readTree(uploadReqRes.getResponse().getContentAsString());
        UUID attachmentId = UUID.fromString(reqJson.get("attachmentId").asText());
        String storageKey = reqJson.get("storageKey").asText();

        // Missing in S3 -> 409 STORAGE_OBJECT_MISSING, stays PENDING
        mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/attachments/{a}/confirm",
                        workspaceId, projectId, attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORAGE_OBJECT_MISSING"));

        String statusBefore = jdbc.queryForObject("SELECT scan_status FROM attachments WHERE id = ?", String.class, attachmentId);
        assertThat(statusBefore).isEqualTo("PENDING");

        // Place object in S3 mock -> confirm passes and marks CLEAN
        S3_STORE.put("/devsphere-attachments/" + storageKey, payload);
        mvc.perform(post("/api/v1/workspaces/{w}/projects/{p}/attachments/{a}/confirm",
                        workspaceId, projectId, attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanStatus").value("CLEAN"));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String readZipEntry(byte[] zipBytes, String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Missing zip entry: " + entryName);
    }
}
