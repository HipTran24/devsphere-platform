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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
public class WorkspaceMembershipIntegrationTest {

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
        sessionKey[0] = 11;
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

    private UUID createActiveProjectWithPm(UUID workspaceId, String projectKey, String name, UUID pmUserId) {
        return tx.execute(s -> {
            UUID projectId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            UUID roleId = UUID.randomUUID();
            UUID workItemId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ? AND status = 'ACTIVE'",
                    Integer.class, workspaceId, pmUserId);
            if (count == null || count == 0) {
                jdbc.update(
                        "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at) " +
                                "VALUES (?, ?, 'MEMBER', 'ACTIVE', ?)",
                        workspaceId, pmUserId, now
                );
            }

            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, sprint_length_days, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'Desc', 'Objective for active project', 'PLANNING', 'Asia/Ho_Chi_Minh', '2026-12-01', 14, ?, ?, 0)",
                    projectId, workspaceId, projectKey, name, now, now
            );

            jdbc.update(
                    "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                    memberId, projectId, workspaceId, pmUserId, now, now, now
            );
            jdbc.update(
                    "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                            "VALUES (?, ?, ?, 'PM', ?, ?, ?, ?)",
                    roleId, projectId, memberId, pmUserId, now, pmUserId, now
            );

            jdbc.update(
                    "INSERT INTO work_items (id, project_id, item_key, title, type, status, priority, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 'Initial Task', 'TASK', 'TO_DO', 'MEDIUM', ?, ?, 0)",
                    workItemId, projectId, projectKey + "-1", now, now
            );

            jdbc.update("UPDATE projects SET status = 'ACTIVE' WHERE id = ?", projectId);

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

    private String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // =========================================================================
    // 1. Invitation Lifecycle Tests
    // =========================================================================

    @Nested
    @DisplayName("1. Invitation Lifecycle Tests")
    class InvitationLifecycleTests {

        @Test
        @DisplayName("OWNER creates invitation -> generates raw token and records hashed token in DB (status=PENDING)")
        void ownerCreatesInvitation() throws Exception {
            UUID owner = createUser("owner-inv@devsphere.test", "Owner Inv", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-inv-test", "Workspace Inv Test");

            String requestBody = json.writeValueAsString(Map.of(
                    "email", "colleague@devsphere.test",
                    "role", "MEMBER"
            ));

            MvcResult result = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                    .andExpect(jsonPath("$.email").value("colleague@devsphere.test"))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.token").isNotEmpty())
                    .andReturn();

            JsonNode resp = json.readTree(result.getResponse().getContentAsString());
            UUID invitationId = UUID.fromString(resp.get("id").asText());
            String rawToken = resp.get("token").asText();

            // Verify in database:
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT email, token_hash, status FROM workspace_invitations WHERE id = ?", invitationId);
            assertThat(String.valueOf(row.get("email"))).isEqualTo("colleague@devsphere.test");
            assertThat(row.get("status")).isEqualTo("PENDING");

            // Verify that the database stores the SHA-256 hash of the raw token, not the raw token itself
            String storedHash = (String) row.get("token_hash");
            assertThat(storedHash).isNotEqualTo(rawToken);
            assertThat(storedHash).isEqualTo(sha256Hex(rawToken));
        }

        @Test
        @DisplayName("Duplicate pending invitation or inviting existing member returns 409 DUPLICATE_RESOURCE")
        void duplicatePendingInvitationOrMemberRejected() throws Exception {
            UUID owner = createUser("owner-dup@devsphere.test", "Owner Dup", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-dup-inv", "Workspace Dup Test");

            String inviteBody = json.writeValueAsString(Map.of("email", "candidate@devsphere.test"));

            // 1. First invitation succeeds
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(inviteBody))
                    .andExpect(status().isCreated());

            // 2. Second pending invitation for same email fails with 409 DUPLICATE_RESOURCE
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(inviteBody))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));

            // 3. Inviting existing member fails with 409 DUPLICATE_RESOURCE
            UUID existingMember = createUser("existing@devsphere.test", "Existing", "ACTIVE", "USER", true);
            addWorkspaceMember(workspaceId, existingMember, "MEMBER");

            String existingMemberInvite = json.writeValueAsString(Map.of("email", "existing@devsphere.test"));
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(existingMemberInvite))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        }

        @Test
        @DisplayName("GET /api/v1/workspaces/{workspaceId}/invitations returns sanitized list without token or token_hash")
        void listInvitationsIsSanitized() throws Exception {
            UUID owner = createUser("owner-list@devsphere.test", "Owner List", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-list-inv", "Workspace List Test");

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", "inv1@devsphere.test"))))
                    .andExpect(status().isCreated());

            MvcResult listResult = mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseJson = listResult.getResponse().getContentAsString();
            assertThat(responseJson).doesNotContain("token_hash");
            JsonNode root = json.readTree(responseJson);
            JsonNode items = root.has("content") ? root.get("content") : (root.isArray() ? root : root.get("items"));
            assertThat(items.size()).isGreaterThanOrEqualTo(1);
            assertThat(items.get(0).has("token")).isFalse();
            assertThat(items.get(0).has("tokenHash")).isFalse();
        }

        @Test
        @DisplayName("OWNER can revoke invitation -> status becomes REVOKED and cannot be accepted")
        void ownerCanRevokeInvitation() throws Exception {
            UUID owner = createUser("owner-rev@devsphere.test", "Owner Rev", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-rev-inv", "Workspace Rev Test");

            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", "invitee-rev@devsphere.test"))))
                    .andExpect(status().isCreated())
                    .andReturn();

            JsonNode createResp = json.readTree(createResult.getResponse().getContentAsString());
            UUID invitationId = UUID.fromString(createResp.get("id").asText());
            String rawToken = createResp.get("token").asText();

            // Revoke invitation
            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/invitations/" + invitationId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            // In DB status is REVOKED
            String statusInDb = jdbc.queryForObject(
                    "SELECT status FROM workspace_invitations WHERE id = ?", String.class, invitationId);
            assertThat(statusInDb).isEqualTo("REVOKED");

            // Attempting to accept revoked token is rejected
            UUID invitee = createUser("invitee-rev@devsphere.test", "Invitee Rev", "ACTIVE", "USER", true);
            String inviteeToken = issueAccessToken(invitee);

            mvc.perform(post("/api/v1/workspaces/invitations/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + inviteeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("token", rawToken))))
                    .andExpect(status().is(Matchers.anyOf(Matchers.is(400), Matchers.is(404), Matchers.is(410))));
        }

        @Test
        @DisplayName("Expired invitation -> attempt to accept returns 410 INVITATION_EXPIRED")
        void expiredInvitationAcceptanceReturns410() throws Exception {
            UUID owner = createUser("owner-exp@devsphere.test", "Owner Exp", "ACTIVE", "USER", true);
            UUID workspaceId = createWorkspace(owner, "ws-exp-inv", "Workspace Exp Test");

            String rawToken = "expired-token-" + UUID.randomUUID();
            String hashedToken = sha256Hex(rawToken);
            OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);

            jdbc.update(
                    "INSERT INTO workspace_invitations (id, workspace_id, email, token_hash, invited_by, role, status, expires_at, created_at, updated_at, version) " +
                            "VALUES (gen_random_uuid(), ?, 'expired-invitee@devsphere.test', ?, ?, 'MEMBER', 'PENDING', ?, ?, ?, 0)",
                    workspaceId, hashedToken, owner, past, past.minusHours(1), past.minusHours(1)
            );

            UUID invitee = createUser("expired-invitee@devsphere.test", "Invitee Exp", "ACTIVE", "USER", true);
            String inviteeToken = issueAccessToken(invitee);

            mvc.perform(post("/api/v1/workspaces/invitations/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + inviteeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("token", rawToken))))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
        }

        @Test
        @DisplayName("Non-matching email caller attempting to accept returns 403 ACCESS_DENIED")
        void mismatchedCallerEmailCannotAccept() throws Exception {
            UUID owner = createUser("owner-mismatch@devsphere.test", "Owner Mismatch", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-mismatch-inv", "Workspace Mismatch Test");

            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", "intended@devsphere.test"))))
                    .andExpect(status().isCreated())
                    .andReturn();

            String rawToken = json.readTree(createResult.getResponse().getContentAsString()).get("token").asText();

            // Attacker / wrong user attempts to accept with different verified email
            UUID attacker = createUser("attacker@devsphere.test", "Attacker", "ACTIVE", "USER", true);
            String attackerToken = issueAccessToken(attacker);

            mvc.perform(post("/api/v1/workspaces/invitations/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + attackerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("token", rawToken))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(Matchers.is("ACCESS_DENIED"), Matchers.is("FORBIDDEN"))));

            // Verify member was NOT created
            Integer memberCount = jdbc.queryForObject(
                    "SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    Integer.class, workspaceId, attacker);
            assertThat(memberCount).isEqualTo(0);
        }

        @Test
        @DisplayName("Valid acceptance creates workspace_members(MEMBER, ACTIVE) and marks invitation ACCEPTED; replay fails")
        void validAcceptanceCreatesMemberAndReplayFails() throws Exception {
            UUID owner = createUser("owner-acc@devsphere.test", "Owner Acc", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-acc-inv", "Workspace Acc Test");

            MvcResult createResult = mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/invitations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", "invited-user@devsphere.test"))))
                    .andExpect(status().isCreated())
                    .andReturn();

            String rawToken = json.readTree(createResult.getResponse().getContentAsString()).get("token").asText();

            UUID invitee = createUser("invited-user@devsphere.test", "Invited User", "ACTIVE", "USER", true);
            String inviteeToken = issueAccessToken(invitee);

            // 1. Successful accept
            mvc.perform(post("/api/v1/workspaces/invitations/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + inviteeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("token", rawToken))))
                    .andExpect(status().isOk());

            // 2. Database verification:
            // a) Invitation status is ACCEPTED with accepted_at stamped
            Map<String, Object> invRow = jdbc.queryForMap(
                    "SELECT status, accepted_at FROM workspace_invitations WHERE token_hash = ?", sha256Hex(rawToken));
            assertThat(invRow.get("status")).isEqualTo("ACCEPTED");
            assertThat(invRow.get("accepted_at")).isNotNull();

            // b) Member row created
            String role = jdbc.queryForObject(
                    "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    String.class, workspaceId, invitee);
            assertThat(role).isEqualTo("MEMBER");

            String memberStatus = jdbc.queryForObject(
                    "SELECT status FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    String.class, workspaceId, invitee);
            assertThat(memberStatus).isEqualTo("ACTIVE");

            // 3. User can now read workspace
            mvc.perform(get("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + inviteeToken))
                    .andExpect(status().isOk());

            // 4. Replay attempt fails (cannot accept consumed token)
            mvc.perform(post("/api/v1/workspaces/invitations/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + inviteeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("token", rawToken))))
                    .andExpect(status().is(Matchers.anyOf(Matchers.is(400), Matchers.is(404), Matchers.is(409), Matchers.is(410))));
        }
    }

    // =========================================================================
    // 2. Member Removal & Trigger Cascades
    // =========================================================================

    @Nested
    @DisplayName("2. Member Removal & Cascades Tests")
    class MemberRemovalAndCascadesTests {

        @Test
        @DisplayName("OWNER removes member -> status=REMOVED, immediately loses access (TC-37)")
        void ownerRemovesMemberImmediatelyLosesAccess() throws Exception {
            UUID owner = createUser("owner-rm@devsphere.test", "Owner RM", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-rm-test", "Workspace RM Test");

            UUID member = createUser("member-rm@devsphere.test", "Member RM", "ACTIVE", "USER", true);
            String memberToken = issueAccessToken(member);
            addWorkspaceMember(workspaceId, member, "MEMBER");

            // Verify active access first
            mvc.perform(get("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                    .andExpect(status().isOk());

            // OWNER deletes member
            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/members/" + member)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            // DB status is REMOVED
            String memberStatus = jdbc.queryForObject(
                    "SELECT status FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    String.class, workspaceId, member);
            assertThat(memberStatus).isEqualTo("REMOVED");

            // Immediate 403 ACCESS_DENIED (TC-37)
            mvc.perform(get("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(Matchers.is("ACCESS_DENIED"), Matchers.is("FORBIDDEN"))));
        }

        @Test
        @DisplayName("Removing workspace member cascades to project_members and revokes active project roles")
        void removingMemberCascadesToProjectsAndRoles() throws Exception {
            UUID owner = createUser("owner-casc@devsphere.test", "Owner Casc", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-casc-test", "Workspace Casc Test");

            UUID member = createUser("member-casc@devsphere.test", "Member Casc", "ACTIVE", "USER", true);
            addWorkspaceMember(workspaceId, member, "MEMBER");

            UUID projectId = createProject(workspaceId, "PRJC", "Cascade Project", owner);
            UUID pmMemberId = addProjectMember(projectId, workspaceId, member);
            UUID roleId = addProjectMemberRole(projectId, pmMemberId, "DEVELOPER", owner);

            // Verify member and role are active
            String initPrjMemberStatus = jdbc.queryForObject(
                    "SELECT status FROM project_members WHERE id = ?", String.class, pmMemberId);
            assertThat(initPrjMemberStatus).isEqualTo("ACTIVE");

            // OWNER removes member from workspace
            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/members/" + member)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            // Trigger ds_workspace_member_removal cascades:
            // 1. project_members status becomes REMOVED
            String postPrjMemberStatus = jdbc.queryForObject(
                    "SELECT status FROM project_members WHERE id = ?", String.class, pmMemberId);
            assertThat(postPrjMemberStatus).isEqualTo("REMOVED");

            // 2. Active project role is revoked (revoked_at is NOT null)
            OffsetDateTime revokedAt = jdbc.queryForObject(
                    "SELECT revoked_at FROM project_member_roles WHERE id = ?", OffsetDateTime.class, roleId);
            assertThat(revokedAt).isNotNull();
        }

        @Test
        @DisplayName("Removing workspace OWNER is rejected with 409 LAST_OWNER")
        void removingOwnerIsRejected() throws Exception {
            UUID owner = createUser("owner-selfrm@devsphere.test", "Owner SelfRM", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-selfrm-test", "Workspace SelfRM Test");

            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/members/" + owner)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LAST_OWNER"));

            // Verify owner is still active
            String role = jdbc.queryForObject(
                    "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    String.class, workspaceId, owner);
            assertThat(role).isEqualTo("OWNER");
        }

        @Test
        @DisplayName("Removing active PM from ACTIVE project is rejected with 409 ACTIVE_PROJECT_REQUIRES_PM")
        void removingActivePmFromActiveProjectIsRejected() throws Exception {
            UUID owner = createUser("owner-pmrm@devsphere.test", "Owner PMRM", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);
            UUID workspaceId = createWorkspace(owner, "ws-pmrm-test", "Workspace PMRM Test");

            UUID pmUser = createUser("pm-user@devsphere.test", "PM User", "ACTIVE", "USER", true);
            createActiveProjectWithPm(workspaceId, "APRJ", "Active Project With PM", pmUser);

            // Attempting to remove active PM from workspace is rejected by trigger / guard
            mvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/members/" + pmUser)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ACTIVE_PROJECT_REQUIRES_PM"));

            // Verify PM remains active in workspace
            String memberStatus = jdbc.queryForObject(
                    "SELECT status FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    String.class, workspaceId, pmUser);
            assertThat(memberStatus).isEqualTo("ACTIVE");
        }
    }

    // =========================================================================
    // 3. Owner Transfer (Independent of PM - TC-30)
    // =========================================================================

    @Nested
    @DisplayName("3. Owner Transfer Tests (TC-30)")
    class OwnerTransferTests {

        @Test
        @DisplayName("OWNER transfers workspace to active MEMBER -> swap verified, owner_id updated, ds_check_workspace_owner passes")
        void ownerTransfersWorkspaceToMember() throws Exception {
            UUID ownerA = createUser("ownera-trf@devsphere.test", "Owner A", "ACTIVE", "USER", true);
            String tokenA = issueAccessToken(ownerA);
            UUID workspaceId = createWorkspace(ownerA, "ws-trf-test", "Workspace Transfer Test");

            UUID memberB = createUser("memberb-trf@devsphere.test", "Member B", "ACTIVE", "USER", true);
            String tokenB = issueAccessToken(memberB);
            addWorkspaceMember(workspaceId, memberB, "MEMBER");

            String transferBody = json.writeValueAsString(Map.of(
                    "targetUserId", memberB.toString(),
                    "expectedVersion", 0,
                    "reason", "Stepping down as workspace owner"
            ));

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/transfer-ownership")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody))
                    .andExpect(status().isOk());

            // Verify in DB:
            // 1. workspaces.owner_id is now Member B
            UUID currentOwnerInDb = jdbc.queryForObject(
                    "SELECT owner_id FROM workspaces WHERE id = ?", UUID.class, workspaceId);
            assertThat(currentOwnerInDb).isEqualTo(memberB);

            // 2. Member B is now OWNER in workspace_members
            String roleB = jdbc.queryForObject(
                    "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    String.class, workspaceId, memberB);
            assertThat(roleB).isEqualTo("OWNER");

            // 3. Prior Owner A is now MEMBER in workspace_members
            String roleA = jdbc.queryForObject(
                    "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    String.class, workspaceId, ownerA);
            assertThat(roleA).isEqualTo("MEMBER");

            // 4. Exactly 1 active OWNER in workspace (ds_check_workspace_owner trigger satisfied)
            Integer ownerCount = jdbc.queryForObject(
                    "SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
                    Integer.class, workspaceId);
            assertThat(ownerCount).isEqualTo(1);

            // 5. New Owner B can access owner endpoints
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/setup")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                    .andExpect(status().isOk());

            // 6. Former Owner A cannot access owner endpoints anymore
            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/setup")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC-30: Owner transfer does not alter existing project PM assignments")
        void ownerTransferPreservesProjectPm() throws Exception {
            UUID ownerA = createUser("ownera-tc30@devsphere.test", "Owner A TC30", "ACTIVE", "USER", true);
            String tokenA = issueAccessToken(ownerA);
            UUID workspaceId = createWorkspace(ownerA, "ws-tc30-test", "Workspace TC30 Test");

            UUID memberB = createUser("memberb-tc30@devsphere.test", "Member B TC30", "ACTIVE", "USER", true);
            addWorkspaceMember(workspaceId, memberB, "MEMBER");

            // Create project with Owner A as the active PM
            UUID projectId = createProject(workspaceId, "PRJA", "Project A TC30", ownerA);

            // Verify Owner A is PM
            Boolean hasPm = jdbc.queryForObject(
                    "SELECT ds_has_project_role(?, ?, 'PM')", Boolean.class, projectId, ownerA);
            assertThat(hasPm).isTrue();

            // Owner A transfers workspace ownership to Member B
            String transferBody = json.writeValueAsString(Map.of(
                    "targetUserId", memberB.toString(),
                    "expectedVersion", 0
            ));
            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/transfer-ownership")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody))
                    .andExpect(status().isOk());

            // TC-30 Invariant check: Owner A is still PM in Project A!
            Boolean stillPm = jdbc.queryForObject(
                    "SELECT ds_has_project_role(?, ?, 'PM')", Boolean.class, projectId, ownerA);
            assertThat(stillPm).isTrue();

            // Member B is NOT PM (unless explicitly transferred via PM transfer protocol)
            Boolean bIsPm = jdbc.queryForObject(
                    "SELECT ds_has_project_role(?, ?, 'PM')", Boolean.class, projectId, memberB);
            assertThat(bIsPm).isFalse();
        }

        @Test
        @DisplayName("Transfer ownership with stale expectedVersion returns 409 PLAN_STALE")
        void ownerTransferWithStaleVersionFails() throws Exception {
            UUID ownerA = createUser("ownera-stale@devsphere.test", "Owner A Stale", "ACTIVE", "USER", true);
            String tokenA = issueAccessToken(ownerA);
            UUID workspaceId = createWorkspace(ownerA, "ws-stale-trf", "Workspace Stale Trf Test");

            UUID memberB = createUser("memberb-stale@devsphere.test", "Member B Stale", "ACTIVE", "USER", true);
            addWorkspaceMember(workspaceId, memberB, "MEMBER");

            String transferBody = json.writeValueAsString(Map.of(
                    "targetUserId", memberB.toString(),
                    "expectedVersion", 99
            ));

            mvc.perform(post("/api/v1/workspaces/" + workspaceId + "/transfer-ownership")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_STALE"));
        }
    }
}
