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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "devsphere.github.webhook-secret=test-webhook-secret-key-12345"
})
@AutoConfigureMockMvc
public class GitHubConnectionIntegrationTest {

    private static final String WEBHOOK_SECRET = "test-webhook-secret-key-12345";

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
        sessionKey[0] = 21;
        hmac[0] = 7;
        encryption[0] = 11;
        registry.add("devsphere.auth.session.signing-key", () -> Base64.getEncoder().encodeToString(sessionKey));
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
        registry.add("devsphere.github.webhook-secret", () -> WEBHOOK_SECRET);
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

    private String computeHmacSignature(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body);
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC signature", e);
        }
    }

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

    private UUID insertDirectRepository(UUID workspaceId, UUID projectId, Long githubRepoId, String fullName, boolean active) {
        UUID repoId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO github_repositories (id, workspace_id, project_id, github_installation_id, github_repo_id, full_name, default_branch, private, active, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 12345, ?, ?, 'main', true, ?, ?, ?, 0)",
                repoId, workspaceId, projectId, githubRepoId, fullName, active, now, now
        );
        return repoId;
    }

    // =========================================================================
    // 1. Repository Connection & Scoping Tests
    // =========================================================================

    @Nested
    @DisplayName("1. Repository Connection & Scoping")
    class RepositoryConnectionTests {

        @Test
        @DisplayName("PM connects repository successfully and persists active state")
        void pmConnectsRepositorySuccessfully() throws Exception {
            UUID pmUserId = createUser("pm-conn@example.test", "PM Conn", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID workspaceId = createWorkspace(pmUserId, "ws-conn-1", "Workspace Conn 1");
            UUID projectId = createProject(workspaceId, "CONN", "Conn Project", pmUserId);

            var payload = Map.of(
                    "githubInstallationId", 12345L,
                    "githubRepoId", 67890L,
                    "fullName", "devsphere-org/backend-service",
                    "defaultBranch", "main",
                    "isPrivate", true
            );

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.githubRepoId").value(67890L))
                    .andExpect(jsonPath("$.fullName").value("devsphere-org/backend-service"))
                    .andExpect(jsonPath("$.defaultBranch").value("main"))
                    .andExpect(jsonPath("$.active").value(true))
                    .andReturn();

            JsonNode node = json.readTree(result.getResponse().getContentAsString());
            UUID repoId = UUID.fromString(node.get("id").asText());

            // Verify in PostgreSQL database
            Boolean active = jdbc.queryForObject(
                    "SELECT active FROM github_repositories WHERE id = ?",
                    Boolean.class, repoId);
            assertThat(active).isTrue();

            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM github_repositories WHERE project_id = ? AND active = true",
                    Long.class, projectId);
            assertThat(count).isEqualTo(1L);
        }

        @Test
        @DisplayName("Attempting to connect duplicate repo ID across projects returns 409")
        void duplicateRepositoryAcrossProjectsRejectedWith409() throws Exception {
            UUID pm1 = createUser("pm-dup1@example.test", "PM Dup1", "ACTIVE", "USER", true);
            String pmToken1 = issueAccessToken(pm1);
            UUID ws1 = createWorkspace(pm1, "ws-dup-1", "Workspace Dup 1");
            UUID proj1 = createProject(ws1, "DUP1", "Project Dup 1", pm1);

            UUID pm2 = createUser("pm-dup2@example.test", "PM Dup2", "ACTIVE", "USER", true);
            String pmToken2 = issueAccessToken(pm2);
            UUID ws2 = createWorkspace(pm2, "ws-dup-2", "Workspace Dup 2");
            UUID proj2 = createProject(ws2, "DUP2", "Project Dup 2", pm2);

            // Connect repo to proj1
            var payload1 = Map.of(
                    "githubInstallationId", 12345L,
                    "githubRepoId", 77777L,
                    "fullName", "devsphere-org/shared-repo",
                    "defaultBranch", "main",
                    "isPrivate", true
            );
            mvc.perform(post("/api/v1/workspaces/" + ws1 + "/projects/" + proj1 + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload1)))
                    .andExpect(status().isCreated());

            // Attempt to connect identical repo to proj2
            var payload2 = Map.of(
                    "githubInstallationId", 12345L,
                    "githubRepoId", 77777L,
                    "fullName", "devsphere-org/shared-repo",
                    "defaultBranch", "main",
                    "isPrivate", true
            );
            mvc.perform(post("/api/v1/workspaces/" + ws2 + "/projects/" + proj2 + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken2)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_REPOSITORY"));
        }

        @Test
        @DisplayName("Attempting to connect 2nd repository to same project returns 409")
        void connectingSecondRepositoryToSameProjectRejectedWith409() throws Exception {
            UUID pmUserId = createUser("pm-single@example.test", "PM Single", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID workspaceId = createWorkspace(pmUserId, "ws-single", "Workspace Single");
            UUID projectId = createProject(workspaceId, "SNGL", "Single Repo Project", pmUserId);

            // Connect 1st repo
            var payload1 = Map.of(
                    "githubInstallationId", 12345L,
                    "githubRepoId", 88881L,
                    "fullName", "devsphere-org/primary-repo",
                    "defaultBranch", "main",
                    "isPrivate", true
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload1)))
                    .andExpect(status().isCreated());

            // Attempt to connect 2nd repo to same project
            var payload2 = Map.of(
                    "githubInstallationId", 12345L,
                    "githubRepoId", 88882L,
                    "fullName", "devsphere-org/secondary-repo",
                    "defaultBranch", "main",
                    "isPrivate", true
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REPOSITORY_ALREADY_CONNECTED"));
        }

        @Test
        @DisplayName("Non-PM attempting to connect repository returns 403 ACCESS_DENIED")
        void nonPmCannotConnectRepository() throws Exception {
            UUID pmUserId = createUser("pm-auth@example.test", "PM Auth", "ACTIVE", "USER", true);
            UUID devUserId = createUser("dev-auth@example.test", "Dev Auth", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUserId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-auth", "Workspace Auth");
            UUID projectId = createProject(workspaceId, "AUTH", "Auth Project", pmUserId);

            addWorkspaceMember(workspaceId, devUserId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, devUserId, "DEVELOPER", pmUserId);

            var payload = Map.of(
                    "githubInstallationId", 12345L,
                    "githubRepoId", 99901L,
                    "fullName", "devsphere-org/unauthorized-repo",
                    "defaultBranch", "main",
                    "isPrivate", true
            );

            // Regular developer cannot connect repo
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            // Non-member caller cannot connect repo
            UUID outsiderId = createUser("outsider@example.test", "Outsider", "ACTIVE", "USER", true);
            String outsiderToken = issueAccessToken(outsiderId);
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("PM and members can list repositories, outsiders denied")
        void listRepositoriesScoping() throws Exception {
            UUID pmUserId = createUser("pm-list@example.test", "PM List", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID devUserId = createUser("dev-list@example.test", "Dev List", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUserId);
            UUID outsiderId = createUser("out-list@example.test", "Out List", "ACTIVE", "USER", true);
            String outsiderToken = issueAccessToken(outsiderId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-list", "Workspace List");
            UUID projectId = createProject(workspaceId, "LIST", "List Project", pmUserId);

            addWorkspaceMember(workspaceId, devUserId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, devUserId, "DEVELOPER", pmUserId);

            insertDirectRepository(workspaceId, projectId, 50001L, "devsphere-org/list-repo", true);

            // PM can list
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].githubRepoId").value(50001L));

            // Developer (project member) can list
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));

            // Outsider denied
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("PM can disconnect repository, allowing new connection")
        void pmCanDisconnectRepository() throws Exception {
            UUID pmUserId = createUser("pm-disc@example.test", "PM Disc", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUserId);
            UUID devUserId = createUser("dev-disc@example.test", "Dev Disc", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUserId);

            UUID workspaceId = createWorkspace(pmUserId, "ws-disc", "Workspace Disc");
            UUID projectId = createProject(workspaceId, "DISC", "Disc Project", pmUserId);

            addWorkspaceMember(workspaceId, devUserId, "MEMBER");
            addProjectMemberWithRole(projectId, workspaceId, devUserId, "DEVELOPER", pmUserId);

            UUID repoId = insertDirectRepository(workspaceId, projectId, 60001L, "devsphere-org/disc-repo", true);

            // Non-PM disconnect rejected
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories/" + repoId + "/disconnect")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            // PM disconnect succeeds
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories/" + repoId + "/disconnect")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));

            // Verify in DB that active is false
            Boolean active = jdbc.queryForObject("SELECT active FROM github_repositories WHERE id = ?", Boolean.class, repoId);
            assertThat(active).isFalse();

            // After disconnecting, PM can connect a new repo to the project
            var newPayload = Map.of(
                    "githubInstallationId", 12345L,
                    "githubRepoId", 60002L,
                    "fullName", "devsphere-org/new-repo",
                    "defaultBranch", "main",
                    "isPrivate", true
            );
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/github/repositories")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(newPayload)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.githubRepoId").value(60002L))
                    .andExpect(jsonPath("$.active").value(true));
        }
    }

    // =========================================================================
    // 2. Webhook Signature Verification Tests
    // =========================================================================

    @Nested
    @DisplayName("2. Webhook Signature Verification")
    class WebhookSignatureTests {

        @Test
        @DisplayName("Valid HMAC-SHA256 signature with matching secret returns 200 OK")
        void validSignatureSucceeds() throws Exception {
            UUID ownerId = createUser("wh-sig@example.test", "WH Sig", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "ws-wh-sig", "Workspace WH Sig");
            UUID projectId = createProject(workspaceId, "WHSIG", "WH Sig Project", ownerId);
            insertDirectRepository(workspaceId, projectId, 70001L, "devsphere-org/sig-repo", true);

            Map<String, Object> payload = Map.of(
                    "zen", "Design for failure.",
                    "hook_id", 123456,
                    "repository", Map.of("id", 70001L, "full_name", "devsphere-org/sig-repo"),
                    "sender", Map.of("login", "octocat")
            );
            byte[] bodyBytes = json.writeValueAsBytes(payload);
            String signature = computeHmacSignature(bodyBytes, WEBHOOK_SECRET);
            String deliveryId = UUID.randomUUID().toString();

            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "ping")
                            .header("X-GitHub-Delivery", deliveryId)
                            .header("X-Hub-Signature-256", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Missing X-Hub-Signature-256 header returns 401 WEBHOOK_SIGNATURE_INVALID")
        void missingSignatureHeaderReturns401() throws Exception {
            Map<String, Object> payload = Map.of("zen", "Non-blocking is better.");
            byte[] bodyBytes = json.writeValueAsBytes(payload);

            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "ping")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        }

        @Test
        @DisplayName("Tampered signature or wrong secret returns 401 WEBHOOK_SIGNATURE_INVALID")
        void tamperedSignatureOrWrongSecretReturns401() throws Exception {
            Map<String, Object> payload = Map.of("zen", "Mind your corners.");
            byte[] bodyBytes = json.writeValueAsBytes(payload);
            String wrongSecretSignature = computeHmacSignature(bodyBytes, "completely-wrong-secret-key");

            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "ping")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .header("X-Hub-Signature-256", wrongSecretSignature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        }

        @Test
        @DisplayName("Tampered payload with mismatched signature returns 401 WEBHOOK_SIGNATURE_INVALID")
        void tamperedPayloadReturns401() throws Exception {
            byte[] originalBytes = json.writeValueAsBytes(Map.of("message", "original"));
            byte[] tamperedBytes = json.writeValueAsBytes(Map.of("message", "tampered-content"));
            String originalSignature = computeHmacSignature(originalBytes, WEBHOOK_SECRET);

            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "ping")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .header("X-Hub-Signature-256", originalSignature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tamperedBytes))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        }

        @Test
        @DisplayName("Malformed signature prefix or invalid hex returns 401 WEBHOOK_SIGNATURE_INVALID")
        void malformedSignatureFormatReturns401() throws Exception {
            byte[] bodyBytes = json.writeValueAsBytes(Map.of("foo", "bar"));

            // Missing "sha256=" prefix
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "ping")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .header("X-Hub-Signature-256", "1234567890abcdef")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));

            // Invalid hex characters
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "ping")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .header("X-Hub-Signature-256", "sha256=NOT_VALID_HEX_ZZZZ")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        }
    }

    // =========================================================================
    // 3. Webhook Deduplication & Idempotency Tests
    // =========================================================================

    @Nested
    @DisplayName("3. Webhook Deduplication & Idempotency")
    class WebhookDeduplicationTests {

        @Test
        @DisplayName("First delivery stores event in github_events table, replay is idempotent ignore")
        void webhookDeduplicationAndIdempotency() throws Exception {
            UUID ownerId = createUser("wh-dedup@example.test", "WH Dedup", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(ownerId, "ws-dedup", "Workspace Dedup");
            UUID projectId = createProject(workspaceId, "DDUP", "Dedup Project", ownerId);
            insertDirectRepository(workspaceId, projectId, 80001L, "devsphere-org/dedup-repo", true);

            String deliveryId = "del-test-unique-" + UUID.randomUUID();
            Map<String, Object> payload = Map.of(
                    "action", "created",
                    "repository", Map.of("id", 80001L, "full_name", "devsphere-org/dedup-repo"),
                    "sender", Map.of("login", "octocat")
            );
            byte[] bodyBytes = json.writeValueAsBytes(payload);
            String signature = computeHmacSignature(bodyBytes, WEBHOOK_SECRET);

            // First delivery: returns 200 OK and stores event
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "ping")
                            .header("X-GitHub-Delivery", deliveryId)
                            .header("X-Hub-Signature-256", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());

            Long eventCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM github_events WHERE delivery_id = ?",
                    Long.class, deliveryId);
            assertThat(eventCount).isEqualTo(1L);

            // Replay delivery with identical X-GitHub-Delivery
            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "ping")
                            .header("X-GitHub-Delivery", deliveryId)
                            .header("X-Hub-Signature-256", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());

            // Database count remains strictly 1 (no duplicate row)
            Long eventCountAfterReplay = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM github_events WHERE delivery_id = ?",
                    Long.class, deliveryId);
            assertThat(eventCountAfterReplay).isEqualTo(1L);
        }

        @Test
        @DisplayName("Webhook for unknown repository is acknowledged gracefully without error")
        void unknownRepositoryDeliveryAcknowledgedGracefully() throws Exception {
            String deliveryId = "del-unknown-" + UUID.randomUUID();
            Map<String, Object> payload = Map.of(
                    "action", "created",
                    "repository", Map.of("id", 999999999L, "full_name", "unknown/unregistered-repo"),
                    "sender", Map.of("login", "octocat")
            );
            byte[] bodyBytes = json.writeValueAsBytes(payload);
            String signature = computeHmacSignature(bodyBytes, WEBHOOK_SECRET);

            mvc.perform(post("/api/v1/github/webhook")
                            .header("X-GitHub-Event", "push")
                            .header("X-GitHub-Delivery", deliveryId)
                            .header("X-Hub-Signature-256", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyBytes))
                    .andExpect(status().isOk());

            Long eventCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM github_events WHERE delivery_id = ?",
                    Long.class, deliveryId);
            assertThat(eventCount).isEqualTo(0L);
        }
    }
}
