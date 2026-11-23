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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
public class ProjectRoleAndPmTransferIntegrationTest {

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
        jdbc.update(
                "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, 'ACTIVE', now(), now(), now(), 0)",
                memberId, projectId, workspaceId, userId
        );
        return memberId;
    }

    private UUID addProjectMemberRole(UUID projectId, UUID projectMemberId, String role, UUID grantedBy) {
        UUID roleId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                        "VALUES (?, ?, ?, ?, ?, now(), ?, now())",
                roleId, projectId, projectMemberId, role, grantedBy, grantedBy
        );
        return roleId;
    }

    // =========================================================================
    // 1. Multiple Project Roles Tests
    // =========================================================================

    @Nested
    @DisplayName("Multiple Project Roles Tests")
    class MultipleProjectRolesTests {

        @Test
        @DisplayName("PM can grant multiple distinct roles (DEVELOPER, CLIENT, VIEWER) to an active workspace member; roles accumulate")
        void pmCanGrantMultipleRolesAndAccumulate() throws Exception {
            UUID ownerId = createUser("owner-mr@example.test", "Owner MR", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-mr@example.test", "PM MR", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-mr@example.test", "Dev MR", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            UUID workspaceId = createWorkspace(ownerId, "ws-mr", "Multi Role WS", "ACTIVE");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJMR", "Multi Role Project", pmId);

            // 1. PM grants DEVELOPER role to devId
            Map<String, Object> grantDevReq = Map.of(
                    "userId", devId.toString(),
                    "role", "DEVELOPER"
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/members")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(grantDevReq)))
                    .andExpect(status().isCreated());

            // 2. PM grants CLIENT role to the same devId
            Map<String, Object> grantClientReq = Map.of(
                    "userId", devId.toString(),
                    "role", "CLIENT"
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/members")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(grantClientReq)))
                    .andExpect(status().isCreated());

            // 3. Verify user accumulates multiple active roles simultaneously in project_member_roles
            List<String> activeRoles = jdbc.queryForList(
                    "SELECT pr.role FROM project_member_roles pr " +
                            "JOIN project_members pm ON pm.id = pr.project_member_id " +
                            "WHERE pr.project_id = ? AND pm.user_id = ? AND pr.revoked_at IS NULL",
                    String.class,
                    projectId,
                    devId
            );

            assertThat(activeRoles).containsExactlyInAnyOrder("DEVELOPER", "CLIENT");
        }

        @Test
        @DisplayName("Revoking one role preserves other accumulated roles")
        void revokingOneRolePreservesOthers() throws Exception {
            UUID ownerId = createUser("owner-rev@example.test", "Owner Rev", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-rev@example.test", "PM Rev", "ACTIVE", "USER", true);
            UUID targetId = createUser("target-rev@example.test", "Target Rev", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            UUID workspaceId = createWorkspace(ownerId, "ws-rev", "Revoke WS", "ACTIVE");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, targetId, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJREVO", "Revoke Project", pmId);
            UUID targetMemberId = addProjectMember(projectId, workspaceId, targetId);
            addProjectMemberRole(projectId, targetMemberId, "DEVELOPER", pmId);
            addProjectMemberRole(projectId, targetMemberId, "CLIENT", pmId);

            // PM revokes DEVELOPER role from targetId
            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/members/" + targetId + "/roles/DEVELOPER")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isNoContent());

            // Verify DEVELOPER is revoked, but CLIENT remains active
            List<String> activeRoles = jdbc.queryForList(
                    "SELECT pr.role FROM project_member_roles pr " +
                            "JOIN project_members pm ON pm.id = pr.project_member_id " +
                            "WHERE pr.project_id = ? AND pm.user_id = ? AND pr.revoked_at IS NULL",
                    String.class,
                    projectId,
                    targetId
            );

            assertThat(activeRoles).containsExactly("CLIENT");
        }

        @Test
        @DisplayName("Revoking last CLIENT role from user who is deadline_approver_id automatically clears deadline_approver_id")
        void revokingLastClientRoleClearsDeadlineApprover() throws Exception {
            UUID ownerId = createUser("owner-dl@example.test", "Owner DL", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-dl@example.test", "PM DL", "ACTIVE", "USER", true);
            UUID clientId = createUser("client-dl@example.test", "Client DL", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            UUID workspaceId = createWorkspace(ownerId, "ws-dl-role", "DL Role WS", "ACTIVE");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, clientId, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJDLROLE", "DL Project", pmId);
            UUID clientMemberId = addProjectMember(projectId, workspaceId, clientId);
            addProjectMemberRole(projectId, clientMemberId, "CLIENT", pmId);

            // Set deadline_approver_id to clientId
            jdbc.update("UPDATE projects SET deadline_approver_id = ? WHERE id = ?", clientId, projectId);

            UUID approverBefore = jdbc.queryForObject("SELECT deadline_approver_id FROM projects WHERE id = ?", UUID.class, projectId);
            assertThat(approverBefore).isEqualTo(clientId);

            // Revoke CLIENT role
            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/members/" + clientId + "/roles/CLIENT")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken))
                    .andExpect(status().isNoContent());

            // Verify deadline_approver_id was automatically cleared
            UUID approverAfter = jdbc.queryForObject("SELECT deadline_approver_id FROM projects WHERE id = ?", UUID.class, projectId);
            assertThat(approverAfter).isNull();
        }

        @Test
        @DisplayName("General role grant API rejects role PM with 400 PM_ROLE_RESTRICTED")
        void roleGrantRejectsDirectPmGrant() throws Exception {
            UUID ownerId = createUser("owner-rej-pm@example.test", "Owner Rej PM", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-rej-pm@example.test", "PM Rej PM", "ACTIVE", "USER", true);
            UUID targetId = createUser("target-rej-pm@example.test", "Target Rej PM", "ACTIVE", "USER", true);

            String pmToken = issueAccessToken(pmId);
            UUID workspaceId = createWorkspace(ownerId, "ws-rej-pm", "Rej PM WS", "ACTIVE");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, targetId, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJREJPM", "Rej PM Project", pmId);

            Map<String, Object> grantPmReq = Map.of(
                    "userId", targetId.toString(),
                    "role", "PM"
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/members")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + pmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(grantPmReq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PM_ROLE_RESTRICTED"));
        }

        @Test
        @DisplayName("Non-PM (including Workspace OWNER without PM) attempting to grant roles receives 403 ACCESS_DENIED")
        void nonPmCannotGrantProjectRoles() throws Exception {
            UUID ownerId = createUser("owner-nopm-grant@example.test", "Owner No PM Grant", "ACTIVE", "USER", true);
            UUID pmId = createUser("pm-grant@example.test", "PM Grant", "ACTIVE", "USER", true);
            UUID targetId = createUser("target-grant@example.test", "Target Grant", "ACTIVE", "USER", true);

            String ownerToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-nopm-grant", "No PM Grant WS", "ACTIVE");
            addWorkspaceMember(workspaceId, pmId, "MEMBER");
            addWorkspaceMember(workspaceId, targetId, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJNOPMGR", "No PM Grant Project", pmId);

            Map<String, Object> grantReq = Map.of(
                    "userId", targetId.toString(),
                    "role", "DEVELOPER"
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/members")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(grantReq)))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // 2. Continuous Coverage PM Transfer Protocol (TC-29 & ds_accept_pm_assignment)
    // =========================================================================

    @Nested
    @DisplayName("Continuous Coverage PM Transfer Protocol Tests")
    class PmTransferProtocolTests {

        @Test
        @DisplayName("Proposing a PM: OWNER proposes candidate via POST pm-assignments")
        void ownerCanProposePmAssignment() throws Exception {
            UUID ownerId = createUser("owner-prop@example.test", "Owner Propose", "ACTIVE", "USER", true);
            UUID currentPmId = createUser("cur-pm@example.test", "Current PM", "ACTIVE", "USER", true);
            UUID candidateId = createUser("cand-pm@example.test", "Candidate PM", "ACTIVE", "USER", true);

            String ownerToken = issueAccessToken(ownerId);
            UUID workspaceId = createWorkspace(ownerId, "ws-prop", "Prop WS", "ACTIVE");
            addWorkspaceMember(workspaceId, currentPmId, "MEMBER");
            addWorkspaceMember(workspaceId, candidateId, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJPROP", "Prop Project", currentPmId);

            Long currentVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
            Map<String, Object> proposeReq = Map.of(
                    "candidateUserId", candidateId.toString(),
                    "expectedVersion", currentVersion
            );

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/pm-assignments")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(proposeReq)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.candidateUserId").value(candidateId.toString()))
                    .andReturn();

            JsonNode respNode = json.readTree(result.getResponse().getContentAsString());
            assertThat(respNode.get("id")).isNotNull();
        }

        @Test
        @DisplayName("Continuous Coverage: While nomination is PENDING, outgoing PM retains 100% active operational PM permissions")
        void outgoingPmRetainsFullPermissionsWhileNominationIsPending() throws Exception {
            UUID ownerId = createUser("owner-cov@example.test", "Owner Coverage", "ACTIVE", "USER", true);
            UUID outgoingPmId = createUser("out-pm@example.test", "Outgoing PM", "ACTIVE", "USER", true);
            UUID candidateId = createUser("cand-cov@example.test", "Candidate Cov", "ACTIVE", "USER", true);
            UUID devId = createUser("dev-cov@example.test", "Dev Cov", "ACTIVE", "USER", true);

            String ownerToken = issueAccessToken(ownerId);
            String outgoingPmToken = issueAccessToken(outgoingPmId);

            UUID workspaceId = createWorkspace(ownerId, "ws-cov", "Coverage WS", "ACTIVE");
            addWorkspaceMember(workspaceId, outgoingPmId, "MEMBER");
            addWorkspaceMember(workspaceId, candidateId, "MEMBER");
            addWorkspaceMember(workspaceId, devId, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJCOV", "Coverage Project", outgoingPmId);

            // OWNER proposes candidate
            Long initVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
            Map<String, Object> proposeReq = Map.of(
                    "candidateUserId", candidateId.toString(),
                    "expectedVersion", initVersion
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/pm-assignments")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(proposeReq)))
                    .andExpect(status().isCreated());

            // CONTINUOUS COVERAGE CHECK:
            // Outgoing PM can still perform operational PM tasks while nomination is PENDING!
            // 1. Outgoing PM grants DEVELOPER role to devId
            Map<String, Object> grantReq = Map.of(
                    "userId", devId.toString(),
                    "role", "DEVELOPER"
            );

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/members")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outgoingPmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(grantReq)))
                    .andExpect(status().isCreated());

            // 2. Outgoing PM updates project metadata
            Long currentVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
            Map<String, Object> updateReq = Map.of(
                    "name", "Coverage Updated Project Name",
                    "expectedVersion", currentVersion
            );

            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outgoingPmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(updateReq)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Non-candidate attempting to accept receives 403 ACCESS_DENIED")
        void nonCandidateCannotAcceptNomination() throws Exception {
            UUID ownerId = createUser("owner-imposter@example.test", "Owner Imposter", "ACTIVE", "USER", true);
            UUID currentPmId = createUser("cur-pm-imp@example.test", "Cur PM Imp", "ACTIVE", "USER", true);
            UUID candidateId = createUser("cand-real@example.test", "Cand Real", "ACTIVE", "USER", true);
            UUID imposterId = createUser("imposter@example.test", "Imposter User", "ACTIVE", "USER", true);

            String ownerToken = issueAccessToken(ownerId);
            String imposterToken = issueAccessToken(imposterId);

            UUID workspaceId = createWorkspace(ownerId, "ws-imp", "Imp WS", "ACTIVE");
            addWorkspaceMember(workspaceId, currentPmId, "MEMBER");
            addWorkspaceMember(workspaceId, candidateId, "MEMBER");
            addWorkspaceMember(workspaceId, imposterId, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJIMP", "Imp Project", currentPmId);

            Long initVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/pm-assignments")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("candidateUserId", candidateId.toString(), "expectedVersion", initVersion))))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID assignmentId = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
            Long currentVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);

            // Imposter attempts to accept
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/pm-assignments/" + assignmentId + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + imposterToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("expectedVersion", currentVersion))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Candidate accepts nomination: atomically revokes old PM role, grants new PM role, preserves old PM non-PM roles, activates workspace if SETUP_INCOMPLETE")
        void candidateAcceptsNominationAtomicallyAndTransfersRole() throws Exception {
            UUID ownerId = createUser("owner-xfer@example.test", "Owner Xfer", "ACTIVE", "USER", true);
            UUID outgoingPmId = createUser("outgoing-pm@example.test", "Outgoing PM Xfer", "ACTIVE", "USER", true);
            UUID candidateId = createUser("candidate-pm@example.test", "Candidate PM Xfer", "ACTIVE", "USER", true);

            String ownerToken = issueAccessToken(ownerId);
            String candidateToken = issueAccessToken(candidateId);
            String outgoingPmToken = issueAccessToken(outgoingPmId);

            // Create workspace initially in SETUP_INCOMPLETE
            UUID workspaceId = createWorkspace(ownerId, "ws-xfer", "Xfer WS", "SETUP_INCOMPLETE");
            addWorkspaceMember(workspaceId, outgoingPmId, "MEMBER");
            addWorkspaceMember(workspaceId, candidateId, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJXFER", "Xfer Project", outgoingPmId);

            // Also grant outgoing PM a non-PM role (DEVELOPER)
            UUID outgoingMemberId = jdbc.queryForObject(
                    "SELECT id FROM project_members WHERE project_id = ? AND user_id = ?",
                    UUID.class,
                    projectId,
                    outgoingPmId
            );
            addProjectMemberRole(projectId, outgoingMemberId, "DEVELOPER", outgoingPmId);

            // Verify initial state:
            // - outgoing PM has 2 roles: PM and DEVELOPER
            // - candidate has 0 roles
            // - workspace is SETUP_INCOMPLETE
            assertThat(jdbc.queryForObject("SELECT ds_has_project_role(?, ?, 'PM')", Boolean.class, projectId, outgoingPmId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT ds_has_project_role(?, ?, 'DEVELOPER')", Boolean.class, projectId, outgoingPmId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT ds_has_project_role(?, ?, 'PM')", Boolean.class, projectId, candidateId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM workspaces WHERE id = ?", String.class, workspaceId)).isEqualTo("SETUP_INCOMPLETE");

            // 1. OWNER proposes candidate
            Long initVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
            MvcResult propResult = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/pm-assignments")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("candidateUserId", candidateId.toString(), "expectedVersion", initVersion))))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID assignmentId = UUID.fromString(json.readTree(propResult.getResponse().getContentAsString()).get("id").asText());
            Long currentVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);

            // 2. Candidate accepts nomination
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/pm-assignments/" + assignmentId + "/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + candidateToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("expectedVersion", currentVersion))))
                    .andExpect(status().isOk());

            // 3. Post-acceptance invariants verification:
            // a) New PM has active PM role
            Boolean candidateHasPm = jdbc.queryForObject("SELECT ds_has_project_role(?, ?, 'PM')", Boolean.class, projectId, candidateId);
            assertThat(candidateHasPm).isTrue();

            // b) Old PM's PM role is revoked
            Boolean oldHasPm = jdbc.queryForObject("SELECT ds_has_project_role(?, ?, 'PM')", Boolean.class, projectId, outgoingPmId);
            assertThat(oldHasPm).isFalse();

            // c) Old PM's non-PM role (DEVELOPER) is preserved
            Boolean oldHasDev = jdbc.queryForObject("SELECT ds_has_project_role(?, ?, 'DEVELOPER')", Boolean.class, projectId, outgoingPmId);
            assertThat(oldHasDev).isTrue();

            // d) Exactly 1 PM is active across the project
            int pmCount = jdbc.queryForObject(
                    "SELECT count(*) FROM project_members pm " +
                            "JOIN project_member_roles pr ON pr.project_member_id = pm.id " +
                            "WHERE pm.project_id = ? AND pr.role = 'PM' AND pr.revoked_at IS NULL",
                    Integer.class,
                    projectId
            );
            assertThat(pmCount).isEqualTo(1);

            // e) Workspace status transitioned to ACTIVE
            String wsStatus = jdbc.queryForObject("SELECT status FROM workspaces WHERE id = ?", String.class, workspaceId);
            assertThat(wsStatus).isEqualTo("ACTIVE");

            // f) Old PM can NO LONGER perform PM actions
            Long newVersion = jdbc.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + outgoingPmToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("name", "Old PM Unauthorized Update", "expectedVersion", newVersion))))
                    .andExpect(status().isForbidden());

            // g) New PM CAN perform PM actions
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + candidateToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("name", "New PM Authorized Update", "expectedVersion", newVersion))))
                    .andExpect(status().isOk());
        }
    }
}
