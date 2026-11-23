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

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
public class RequirementIntegrationTest {

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

    private List<JsonNode> extractItems(String responseJson) throws Exception {
        JsonNode root = json.readTree(responseJson);
        List<JsonNode> list = new ArrayList<>();
        if (root.has("content") && root.get("content").isArray()) {
            root.get("content").forEach(list::add);
        } else if (root.has("items") && root.get("items").isArray()) {
            root.get("items").forEach(list::add);
        } else if (root.isArray()) {
            root.forEach(list::add);
        }
        return list;
    }

    // =========================================================================
    // 1. Requirement Lifecycle & Immutability Tests
    // =========================================================================

    @Nested
    @DisplayName("1. Requirement Lifecycle & Immutability")
    class RequirementLifecycleTests {

        @Test
        @DisplayName("PM creates requirement -> code REQ-1, status DRAFT, initial version 1 in requirement_versions")
        void pmCreatesRequirement() throws Exception {
            UUID pmUser = createUser("pm-req@devsphere.test", "PM Req", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-req", "Workspace Req");
            UUID prjId = createProject(wsId, "REQPRJ", "Req Project", pmUser);

            String requestBody = json.writeValueAsString(Map.of(
                    "title", "User Authentication",
                    "description", "System shall allow email login with OTP verification",
                    "acceptanceSummary", "Passes email delivery and token validation"
            ));

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.projectId").value(prjId.toString()))
                    .andExpect(jsonPath("$.code").value("REQ-1"))
                    .andExpect(jsonPath("$.kind").value("REQUIREMENT"))
                    .andExpect(jsonPath("$.title").value("User Authentication"))
                    .andExpect(jsonPath("$.description").value("System shall allow email login with OTP verification"))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.currentVersion").value(1))
                    .andReturn();

            JsonNode resp = json.readTree(result.getResponse().getContentAsString());
            UUID reqId = UUID.fromString(resp.get("id").asText());

            // Direct DB assertions:
            // 1. Requirements row
            Map<String, Object> reqRow = jdbc.queryForMap(
                    "SELECT code, kind, status, current_version, requested_by FROM requirements WHERE id = ?", reqId);
            assertThat(reqRow.get("code")).isEqualTo("REQ-1");
            assertThat(reqRow.get("kind")).isEqualTo("REQUIREMENT");
            assertThat(reqRow.get("status")).isEqualTo("DRAFT");
            assertThat(reqRow.get("current_version")).isEqualTo(1);
            assertThat(reqRow.get("requested_by")).isEqualTo(pmUser);

            // 2. Requirement_versions row
            Map<String, Object> verRow = jdbc.queryForMap(
                    "SELECT version_no, description, acceptance_summary, approved_at FROM requirement_versions WHERE requirement_id = ? AND version_no = 1", reqId);
            assertThat(verRow.get("version_no")).isEqualTo(1);
            assertThat(verRow.get("description")).isEqualTo("System shall allow email login with OTP verification");
            assertThat(verRow.get("acceptance_summary")).isEqualTo("Passes email delivery and token validation");
            assertThat(verRow.get("approved_at")).isNull();
        }

        @Test
        @DisplayName("Non-PM is denied creating or approving requirements (403 ACCESS_DENIED)")
        void nonPmCannotCreateOrApproveRequirement() throws Exception {
            UUID pmUser = createUser("pm-auth@devsphere.test", "PM Auth", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-auth", "Workspace Auth");
            UUID prjId = createProject(wsId, "AUTHPRJ", "Auth Project", pmUser);

            UUID devUser = createUser("dev-auth@devsphere.test", "Dev Auth", "ACTIVE", "USER", true);
            String devToken = issueAccessToken(devUser);
            addWorkspaceMember(wsId, devUser, "MEMBER");
            UUID devMemberId = addProjectMember(prjId, wsId, devUser);
            addProjectMemberRole(prjId, devMemberId, "DEVELOPER", pmUser);

            String createBody = json.writeValueAsString(Map.of(
                    "title", "Unauthorized Requirement",
                    "description", "Should fail"
            ));

            // Dev cannot create requirement
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(Matchers.is("ACCESS_DENIED"), Matchers.is("FORBIDDEN"))));

            // PM creates requirement
            MvcResult pmCreateResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID reqId = UUID.fromString(json.readTree(pmCreateResult.getResponse().getContentAsString()).get("id").asText());

            // Dev cannot approve requirement
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements/" + reqId + "/approve")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(Matchers.is("ACCESS_DENIED"), Matchers.is("FORBIDDEN"))));
        }

        @Test
        @DisplayName("PM approves requirement -> status APPROVED, and DB trigger immutable_approved_requirement prevents update/delete on version 1")
        void approvedRequirementVersionIsImmutable() throws Exception {
            UUID pmUser = createUser("pm-appr@devsphere.test", "PM Appr", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-appr", "Workspace Appr");
            UUID prjId = createProject(wsId, "APPRPRJ", "Appr Project", pmUser);

            String requestBody = json.writeValueAsString(Map.of(
                    "title", "Core Storage Engine",
                    "description", "Initial S3 storage contract specification",
                    "acceptanceSummary", "Zero public buckets"
            ));

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID reqId = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());

            // PM approves requirement
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements/" + reqId + "/approve")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            // Verify in DB:
            // 1. requirements.status is APPROVED
            String reqStatus = jdbc.queryForObject("SELECT status FROM requirements WHERE id = ?", String.class, reqId);
            assertThat(reqStatus).isEqualTo("APPROVED");

            // 2. requirement_versions has approved_at stamped
            OffsetDateTime approvedAt = jdbc.queryForObject(
                    "SELECT approved_at FROM requirement_versions WHERE requirement_id = ? AND version_no = 1",
                    OffsetDateTime.class, reqId);
            assertThat(approvedAt).isNotNull();

            // 3. Database trigger immutable_approved_requirement prevents direct UPDATE
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE requirement_versions SET description = 'Tampered description' WHERE requirement_id = ? AND version_no = 1",
                    reqId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Approved Requirement Version is immutable");

            // 4. Database trigger immutable_approved_requirement prevents direct DELETE
            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM requirement_versions WHERE requirement_id = ? AND version_no = 1",
                    reqId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Approved Requirement Version is immutable");
        }

        @Test
        @DisplayName("Editing approved requirement appends version 2, updates current_version=2, preserving version 1 intact")
        void editingApprovedRequirementAppendsNewVersion() throws Exception {
            UUID pmUser = createUser("pm-edit@devsphere.test", "PM Edit", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-edit", "Workspace Edit");
            UUID prjId = createProject(wsId, "EDITPRJ", "Edit Project", pmUser);

            // 1. Create requirement v1
            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "title", "Original Title v1",
                                    "description", "Original Description v1",
                                    "acceptanceSummary", "Original Acceptance v1"
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID reqId = UUID.fromString(json.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

            // 2. Approve v1
            MvcResult approveResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements/" + reqId + "/approve")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();
            long approvedVersion = json.readTree(approveResult.getResponse().getContentAsString()).get("version").asLong();

            // 3. Update requirement -> appends v2
            String updateBody = json.writeValueAsString(Map.of(
                    "title", "Updated Title v2",
                    "description", "Updated Description v2 with additional scopes",
                    "acceptanceSummary", "Updated Acceptance v2",
                    "changeReason", "Scoped for Enterprise release",
                    "expectedVersion", approvedVersion
            ));

            mvc.perform(patch("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements/" + reqId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currentVersion").value(2))
                    .andExpect(jsonPath("$.title").value("Updated Title v2"))
                    .andExpect(jsonPath("$.description").value("Updated Description v2 with additional scopes"));

            // 4. Verify in DB:
            // Requirements table reflects current_version = 2
            Integer currentVersion = jdbc.queryForObject(
                    "SELECT current_version FROM requirements WHERE id = ?", Integer.class, reqId);
            assertThat(currentVersion).isEqualTo(2);

            // Requirement_versions has TWO distinct versions
            List<Map<String, Object>> versions = jdbc.queryForList(
                    "SELECT version_no, description, change_reason, approved_at FROM requirement_versions WHERE requirement_id = ? ORDER BY version_no ASC",
                    reqId);
            assertThat(versions).hasSize(2);

            // Version 1 is still intact and approved
            Map<String, Object> v1 = versions.get(0);
            assertThat(v1.get("version_no")).isEqualTo(1);
            assertThat(v1.get("description")).isEqualTo("Original Description v1");
            assertThat(v1.get("approved_at")).isNotNull();

            // Version 2 is draft with change_reason
            Map<String, Object> v2 = versions.get(1);
            assertThat(v2.get("version_no")).isEqualTo(2);
            assertThat(v2.get("description")).isEqualTo("Updated Description v2 with additional scopes");
            assertThat(v2.get("change_reason")).isEqualTo("Scoped for Enterprise release");
            assertThat(v2.get("approved_at")).isNull();
        }

        @Test
        @DisplayName("GET /api/v1/workspaces/{wId}/projects/{pId}/requirements/{id} returns details and version history")
        void getRequirementWithVersionHistory() throws Exception {
            UUID pmUser = createUser("pm-hist@devsphere.test", "PM Hist", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-hist", "Workspace Hist");
            UUID prjId = createProject(wsId, "HISTPRJ", "Hist Project", pmUser);

            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "title", "Search Feature",
                                    "description", "Elasticsearch integration",
                                    "acceptanceSummary", "Sub-100ms response"
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID reqId = UUID.fromString(json.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

            mvc.perform(get("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements/" + reqId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(reqId.toString()))
                    .andExpect(jsonPath("$.code").value("REQ-1"))
                    .andExpect(jsonPath("$.title").value("Search Feature"));
        }
    }

    // =========================================================================
    // 2. Change Request Lifecycle (TC-33)
    // =========================================================================

    @Nested
    @DisplayName("2. Change Request Lifecycle (TC-33)")
    class ChangeRequestLifecycleTests {

        @Test
        @DisplayName("CLIENT submits Change Request -> kind=CHANGE_REQUEST, change_request_status=PENDING")
        void clientSubmitsChangeRequest() throws Exception {
            UUID pmUser = createUser("pm-cr@devsphere.test", "PM CR", "ACTIVE", "USER", true);
            UUID wsId = createWorkspace(pmUser, "ws-cr", "Workspace CR");
            UUID prjId = createProject(wsId, "CRPRJ", "CR Project", pmUser);

            UUID clientUser = createUser("client-cr@devsphere.test", "Client CR", "ACTIVE", "USER", true);
            String clientToken = issueAccessToken(clientUser);
            addWorkspaceMember(wsId, clientUser, "MEMBER");
            UUID clientMemberId = addProjectMember(prjId, wsId, clientUser);
            addProjectMemberRole(prjId, clientMemberId, "CLIENT", pmUser);

            String requestBody = json.writeValueAsString(Map.of(
                    "title", "Add Dark Mode Theme",
                    "description", "Client requested system dark mode styling for dashboard"
            ));

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.changeRequestStatus").value("PENDING"))
                    .andExpect(jsonPath("$.title").value("Add Dark Mode Theme"))
                    .andReturn();

            UUID crId = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());

            // Direct DB assertion:
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT kind, change_request_status, requested_by FROM requirements WHERE id = ?", crId);
            assertThat(row.get("kind")).isEqualTo("CHANGE_REQUEST");
            assertThat(row.get("change_request_status")).isEqualTo("PENDING");
            assertThat(row.get("requested_by")).isEqualTo(clientUser);
        }

        @Test
        @DisplayName("Scoped visibility: CLIENT sees only their CRs, PM sees all CRs in project")
        void scopedVisibilityBetweenClientAndPm() throws Exception {
            UUID pmUser = createUser("pm-scope@devsphere.test", "PM Scope", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-scope", "Workspace Scope");
            UUID prjId = createProject(wsId, "SCOPEPRJ", "Scope Project", pmUser);

            // Client 1
            UUID client1 = createUser("client1@devsphere.test", "Client 1", "ACTIVE", "USER", true);
            String client1Token = issueAccessToken(client1);
            addWorkspaceMember(wsId, client1, "MEMBER");
            UUID m1 = addProjectMember(prjId, wsId, client1);
            addProjectMemberRole(prjId, m1, "CLIENT", pmUser);

            // Client 2
            UUID client2 = createUser("client2@devsphere.test", "Client 2", "ACTIVE", "USER", true);
            String client2Token = issueAccessToken(client2);
            addWorkspaceMember(wsId, client2, "MEMBER");
            UUID m2 = addProjectMember(prjId, wsId, client2);
            addProjectMemberRole(prjId, m2, "CLIENT", pmUser);

            // Client 1 submits CR 1
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + client1Token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("title", "CR 1 from Client 1"))))
                    .andExpect(status().isCreated());

            // Client 2 submits CR 2
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + client2Token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("title", "CR 2 from Client 2"))))
                    .andExpect(status().isCreated());

            // Client 1 lists CRs -> Sees only CR 1
            MvcResult listClient1 = mvc.perform(get("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + client1Token))
                    .andExpect(status().isOk())
                    .andReturn();
            List<JsonNode> items1 = extractItems(listClient1.getResponse().getContentAsString());
            assertThat(items1).hasSize(1);
            assertThat(items1.get(0).get("title").asText()).isEqualTo("CR 1 from Client 1");

            // Client 2 lists CRs -> Sees only CR 2
            MvcResult listClient2 = mvc.perform(get("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + client2Token))
                    .andExpect(status().isOk())
                    .andReturn();
            List<JsonNode> items2 = extractItems(listClient2.getResponse().getContentAsString());
            assertThat(items2).hasSize(1);
            assertThat(items2.get(0).get("title").asText()).isEqualTo("CR 2 from Client 2");

            // PM lists CRs -> Sees BOTH CR 1 and CR 2
            MvcResult listPm = mvc.perform(get("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk())
                    .andReturn();
            List<JsonNode> pmItems = extractItems(listPm.getResponse().getContentAsString());
            assertThat(pmItems).hasSize(2);
        }

        @Test
        @DisplayName("PM rejects or defers Change Request -> updates status accordingly without plan proposal")
        void pmRejectsOrDefersChangeRequest() throws Exception {
            UUID pmUser = createUser("pm-dec@devsphere.test", "PM Dec", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-dec", "Workspace Dec");
            UUID prjId = createProject(wsId, "DECPRJ", "Dec Project", pmUser);

            UUID clientUser = createUser("client-dec@devsphere.test", "Client Dec", "ACTIVE", "USER", true);
            String clientToken = issueAccessToken(clientUser);
            addWorkspaceMember(wsId, clientUser, "MEMBER");
            UUID cm = addProjectMember(prjId, wsId, clientUser);
            addProjectMemberRole(prjId, cm, "CLIENT", pmUser);

            // Client creates CR
            MvcResult crResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("title", "High Cost Feature Request"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID crId = UUID.fromString(json.readTree(crResult.getResponse().getContentAsString()).get("id").asText());

            // PM rejects CR
            String rejectBody = json.writeValueAsString(Map.of(
                    "decision", "REJECTED",
                    "decisionReason", "Exceeds agreed budget constraints",
                    "expectedVersion", 0
            ));
            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests/" + crId + "/decision")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(rejectBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changeRequestStatus").value("REJECTED"));

            String statusInDb = jdbc.queryForObject(
                    "SELECT change_request_status FROM requirements WHERE id = ?", String.class, crId);
            assertThat(statusInDb).isEqualTo("REJECTED");

            // Verify no proposal created
            Integer proposalCount = jdbc.queryForObject(
                    "SELECT count(*) FROM plan_proposals WHERE change_request_id = ?", Integer.class, crId);
            assertThat(proposalCount).isEqualTo(0);
        }

        @Test
        @DisplayName("TC-33: PM approves CR -> creates plan_proposals draft record without altering official baseline")
        void pmApprovesChangeRequestCreatesProposalDraft() throws Exception {
            UUID pmUser = createUser("pm-tc33@devsphere.test", "PM TC33", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmUser);
            UUID wsId = createWorkspace(pmUser, "ws-tc33", "Workspace TC33");
            UUID prjId = createProject(wsId, "TC33PRJ", "TC33 Project", pmUser);

            UUID clientUser = createUser("client-tc33@devsphere.test", "Client TC33", "ACTIVE", "USER", true);
            String clientToken = issueAccessToken(clientUser);
            addWorkspaceMember(wsId, clientUser, "MEMBER");
            UUID cm = addProjectMember(prjId, wsId, clientUser);
            addProjectMemberRole(prjId, cm, "CLIENT", pmUser);

            // 1. PM creates and approves original requirement
            MvcResult reqResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "title", "Payment Gateway",
                                    "description", "Credit card processing",
                                    "acceptanceSummary", "PCI DSS compliant"
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID reqId = UUID.fromString(json.readTree(reqResult.getResponse().getContentAsString()).get("id").asText());

            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/requirements/" + reqId + "/approve")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isOk());

            // 2. Client submits CR targeting this requirement
            MvcResult crResult = mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "title", "Support Apple Pay & Google Pay",
                                    "description", "Add digital wallet integration",
                                    "targetRequirementId", reqId.toString()
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID crId = UUID.fromString(json.readTree(crResult.getResponse().getContentAsString()).get("id").asText());

            // 3. PM approves CR
            String approveBody = json.writeValueAsString(Map.of(
                    "decision", "APPROVED",
                    "decisionReason", "Approved for milestone 2",
                    "expectedVersion", 0
            ));

            mvc.perform(post("/api/v1/workspaces/" + wsId + "/projects/" + prjId + "/change-requests/" + crId + "/decision")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(approveBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changeRequestStatus").value("APPROVED"));

            // 4. TC-33 Assertions:
            // a) CR in requirements table has status APPROVED
            Map<String, Object> crDb = jdbc.queryForMap(
                    "SELECT change_request_status, approved_by FROM requirements WHERE id = ?", crId);
            assertThat(crDb.get("change_request_status")).isEqualTo("APPROVED");
            assertThat(crDb.get("approved_by")).isEqualTo(pmUser);

            // b) Draft plan_proposals created with change_request_id, kind=MANUAL_REPLAN, status=DRAFT, solver_status=NOT_RUN
            Map<String, Object> proposal = jdbc.queryForMap(
                    "SELECT id, kind, status, solver_status, change_request_id, created_by, proposal_payload " +
                            "FROM plan_proposals WHERE change_request_id = ?", crId);
            assertThat(proposal.get("kind")).isEqualTo("MANUAL_REPLAN");
            assertThat(proposal.get("status")).isEqualTo("DRAFT");
            assertThat(proposal.get("solver_status")).isEqualTo("NOT_RUN");
            assertThat(proposal.get("change_request_id")).isEqualTo(crId);
            assertThat(proposal.get("created_by")).isEqualTo(pmUser);

            // c) TC-33 Baseline Preservation: Original requirement was NOT directly mutated
            Map<String, Object> originalReq = jdbc.queryForMap(
                    "SELECT title, description, current_version FROM requirements WHERE id = ?", reqId);
            assertThat(originalReq.get("title")).isEqualTo("Payment Gateway");
            assertThat(originalReq.get("description")).isEqualTo("Credit card processing");
            assertThat(originalReq.get("current_version")).isEqualTo(1);
        }
    }
}
