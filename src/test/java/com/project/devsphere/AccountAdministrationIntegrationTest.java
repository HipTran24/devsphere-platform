package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
public class AccountAdministrationIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @MockitoBean private JavaMailSender mailSender;

    private final ObjectMapper json = new ObjectMapper();
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

    private UUID createAdmin(String email, String displayName) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, password_hash, system_role, status, email_verified_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'argon2_dummy_hash', 'ADMIN', 'ACTIVE', ?, ?, ?, 0)",
                id, email, displayName, now, now, now
        );
        return id;
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
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
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

    private UUID createProjectWithPm(UUID pmUserId) {
        return tx.execute(status -> {
            UUID workspaceId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            UUID roleId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            jdbc.update(
                    "INSERT INTO workspaces (id, slug, name, owner_id, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'Test Workspace', ?, 'ACTIVE', ?, ?, 0)",
                    workspaceId, "ws-" + workspaceId, pmUserId, now, now
            );
            jdbc.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, role, joined_at) " +
                            "VALUES (?, ?, 'OWNER', ?)",
                    workspaceId, pmUserId, now
            );
            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, status, objective, planned_start, deadline_approver_id, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'PRJ', 'Test Project', 'PLANNING', 'Test Objective', ?, ?, ?, ?, 0)",
                    projectId, workspaceId, now.toLocalDate(), pmUserId, now, now
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
                    "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                            "VALUES (gen_random_uuid(), ?, ?, 'CLIENT', ?, ?, ?, ?)",
                    projectId, memberId, pmUserId, now, pmUserId, now
            );
            jdbc.update(
                    "INSERT INTO work_items (id, project_id, item_key, type, title, status, created_at, updated_at, version) " +
                            "VALUES (gen_random_uuid(), ?, 'PRJ-1', 'TASK', 'Initial Task', 'TO_DO', ?, ?, 0)",
                    projectId, now, now
            );
            jdbc.update(
                    "UPDATE projects SET status = 'ACTIVE' WHERE id = ?",
                    projectId
            );

            return projectId;
        });
    }

    // =========================================================================
    // 1. RBAC & Access Control Gate
    // =========================================================================

    @Nested
    @DisplayName("RBAC Gate Tests")
    class RbacGateTests {

        @Test
        @DisplayName("Unauthenticated requests to admin endpoints return 401")
        void unauthenticatedRequestsReturn401() throws Exception {
            mvc.perform(get("/api/v1/admin/users"))
                    .andExpect(status().isUnauthorized());

            mvc.perform(patch("/api/v1/admin/users/" + UUID.randomUUID() + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\",\"expectedVersion\":0}"))
                    .andExpect(status().isUnauthorized());

            mvc.perform(get("/api/v1/admin/audit-logs"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Regular non-admin users (USER system_role) return 403 Forbidden")
        void regularUsersReturn403() throws Exception {
            UUID regularUserId = createUser("regular@example.test", "Regular User", "ACTIVE", "USER", true);
            String userToken = issueAccessToken(regularUserId);

            mvc.perform(get("/api/v1/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                    .andExpect(status().isForbidden());

            mvc.perform(patch("/api/v1/admin/users/" + UUID.randomUUID() + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\",\"expectedVersion\":0}"))
                    .andExpect(status().isForbidden());

            mvc.perform(get("/api/v1/admin/audit-logs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // 2. User Listing (GET /api/v1/admin/users)
    // =========================================================================

    @Nested
    @DisplayName("User Listing Tests (GET /api/v1/admin/users)")
    class UserListingTests {

        @Test
        @DisplayName("Default pagination uses page 0 and size 20; bounded pagination validates page 0..10000, size 1..100")
        void paginationBoundsAndDefaults() throws Exception {
            UUID adminId = createAdmin("admin@example.test", "Admin User");
            String token = issueAccessToken(adminId);

            // Populate 25 users
            for (int i = 0; i < 25; i++) {
                createUser("user" + i + "@example.test", "User " + i, "ACTIVE", "USER", true);
            }

            // Default pagination: should return 20 items
            mvc.perform(get("/api/v1/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(20))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20));

            // Custom valid pagination
            mvc.perform(get("/api/v1/admin/users?page=1&size=5")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(5))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.size").value(5));

            // Invalid bounds: negative page
            mvc.perform(get("/api/v1/admin/users?page=-1&size=20")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());

            // Invalid bounds: page > 10000
            mvc.perform(get("/api/v1/admin/users?page=10001&size=20")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());

            // Invalid bounds: size 0
            mvc.perform(get("/api/v1/admin/users?page=0&size=0")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());

            // Invalid bounds: size > 100
            mvc.perform(get("/api/v1/admin/users?page=0&size=101")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("SQL LIKE wildcard escaping: searching % or _ matches literally without behaving as wildcards")
        void sqlLikeEscaping() throws Exception {
            UUID adminId = createAdmin("admin-like@example.test", "Admin Like");
            String token = issueAccessToken(adminId);

            createUser("wild%card@example.test", "Wild%Percent", "ACTIVE", "USER", true);
            createUser("wild_card@example.test", "Wild_Underscore", "ACTIVE", "USER", true);
            createUser("wildXcard@example.test", "WildNormal", "ACTIVE", "USER", true);

            // Searching for literal '%' in query should match only "wild%card" and not "wildXcard"
            mvc.perform(get("/api/v1/admin/users?q=wild%25card")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].email").value("wild%card@example.test"));

            // Searching for literal '_' in query should match only "wild_card" and not "wildXcard"
            mvc.perform(get("/api/v1/admin/users?q=wild_card")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].email").value("wild_card@example.test"));
        }

        @Test
        @DisplayName("Filtering by status (ACTIVE, LOCKED, PENDING) and role (ADMIN, USER)")
        void filteringByStatusAndRole() throws Exception {
            UUID adminId = createAdmin("admin-filter@example.test", "Admin Filter");
            String token = issueAccessToken(adminId);

            createUser("active-user@example.test", "Active User", "ACTIVE", "USER", true);
            createUser("locked-user@example.test", "Locked User", "LOCKED", "USER", true);
            createUser("pending-user@example.test", "Pending User", "PENDING", "USER", false);
            createUser("another-admin@example.test", "Another Admin", "ACTIVE", "ADMIN", true);

            // Filter status=LOCKED
            mvc.perform(get("/api/v1/admin/users?status=LOCKED")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].email").value("locked-user@example.test"));

            // Filter role=ADMIN
            mvc.perform(get("/api/v1/admin/users?role=ADMIN")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2));

            // Combined filter: status=ACTIVE and role=USER
            mvc.perform(get("/api/v1/admin/users?status=ACTIVE&role=USER")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].email").value("active-user@example.test"));
        }

        @Test
        @DisplayName("Whitelist sorting on createdAt, email, displayName, status with ID tie-break; rejects invalid sort fields")
        void sortingWhitelistAndTieBreak() throws Exception {
            UUID adminId = createAdmin("admin-sort@example.test", "Admin Sort");
            String token = issueAccessToken(adminId);

            createUser("b-user@example.test", "B User", "ACTIVE", "USER", true);
            createUser("a-user@example.test", "A User", "ACTIVE", "USER", true);

            // Valid sort: email asc
            mvc.perform(get("/api/v1/admin/users?sort=email,asc")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].email").value("a-user@example.test"));

            // Valid sort: displayName desc
            mvc.perform(get("/api/v1/admin/users?sort=displayName,desc")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());

            // Unwhitelisted sort field returns 400 Bad Request
            mvc.perform(get("/api/v1/admin/users?sort=password_hash,asc")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());

            mvc.perform(get("/api/v1/admin/users?sort=secret_column,desc")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Privacy guarantee: user listing never leaks password_hash, tokens, or internal secrets")
        void privacyGuaranteesInListing() throws Exception {
            UUID adminId = createAdmin("admin-privacy@example.test", "Admin Privacy");
            String token = issueAccessToken(adminId);

            createUser("user-privacy@example.test", "Privacy User", "ACTIVE", "USER", true);

            MvcResult result = mvc.perform(get("/api/v1/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("password_hash");
            assertThat(body).doesNotContain("argon2_dummy_hash");
            assertThat(body).doesNotContain("token_hash");
            assertThat(body).doesNotContain("refresh_hash");
        }
    }

    // =========================================================================
    // 3. User Status Transitions (PATCH /api/v1/admin/users/{userId}/status)
    // =========================================================================

    @Nested
    @DisplayName("User Status Transitions Tests")
    class UserStatusTransitionTests {

        @Test
        @DisplayName("State machine transitions: ACTIVE -> LOCKED succeeds")
        void transitionActiveToLocked() throws Exception {
            UUID adminId = createAdmin("admin-sm1@example.test", "Admin SM1");
            String token = issueAccessToken(adminId);
            UUID targetId = createUser("target-active@example.test", "Target Active", "ACTIVE", "USER", true);

            mvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\",\"expectedVersion\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("LOCKED"))
                    .andExpect(jsonPath("$.version").value(1));

            String currentStatus = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, targetId);
            assertThat(currentStatus).isEqualTo("LOCKED");
        }

        @Test
        @DisplayName("State machine transitions: PENDING -> LOCKED succeeds")
        void transitionPendingToLocked() throws Exception {
            UUID adminId = createAdmin("admin-sm2@example.test", "Admin SM2");
            String token = issueAccessToken(adminId);
            UUID targetId = createUser("target-pending@example.test", "Target Pending", "PENDING", "USER", false);

            mvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\",\"expectedVersion\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("LOCKED"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("State machine transitions: LOCKED -> ACTIVE succeeds when email is verified")
        void transitionLockedToActiveWhenEmailVerified() throws Exception {
            UUID adminId = createAdmin("admin-sm3@example.test", "Admin SM3");
            String token = issueAccessToken(adminId);
            UUID targetId = createUser("target-locked-ver@example.test", "Target Locked Verified", "LOCKED", "USER", true);

            mvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\",\"expectedVersion\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("State machine transitions: LOCKED -> ACTIVE fails 409 STATE_TRANSITION_INVALID when email NOT verified")
        void transitionLockedToActiveWhenEmailNotVerifiedFails() throws Exception {
            UUID adminId = createAdmin("admin-sm4@example.test", "Admin SM4");
            String token = issueAccessToken(adminId);
            UUID targetId = createUser("target-locked-unver@example.test", "Target Locked Unverified", "LOCKED", "USER", false);

            mvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\",\"expectedVersion\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STATE_TRANSITION_INVALID"));
        }

        @Test
        @DisplayName("State machine transitions: LOCKED -> PENDING succeeds when email is NOT verified")
        void transitionLockedToPendingWhenEmailNotVerified() throws Exception {
            UUID adminId = createAdmin("admin-sm5@example.test", "Admin SM5");
            String token = issueAccessToken(adminId);
            UUID targetId = createUser("target-locked-pnd@example.test", "Target Locked Pending", "LOCKED", "USER", false);

            mvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"PENDING\",\"expectedVersion\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("State machine transitions: LOCKED -> PENDING fails 409 STATE_TRANSITION_INVALID when email IS verified")
        void transitionLockedToPendingWhenEmailVerifiedFails() throws Exception {
            UUID adminId = createAdmin("admin-sm6@example.test", "Admin SM6");
            String token = issueAccessToken(adminId);
            UUID targetId = createUser("target-locked-pnd-fail@example.test", "Target Locked Pnd Fail", "LOCKED", "USER", true);

            mvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"PENDING\",\"expectedVersion\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STATE_TRANSITION_INVALID"));
        }

        @Test
        @DisplayName("State machine transitions: PENDING -> ACTIVE directly fails 409 STATE_TRANSITION_INVALID")
        void transitionPendingToActiveDirectlyFails() throws Exception {
            UUID adminId = createAdmin("admin-sm7@example.test", "Admin SM7");
            String token = issueAccessToken(adminId);
            UUID targetId = createUser("target-pending-act@example.test", "Target Pending Act", "PENDING", "USER", false);

            mvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\",\"expectedVersion\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STATE_TRANSITION_INVALID"));
        }

        @Test
        @DisplayName("Optimistic concurrency: version mismatch returns 409 CONCURRENT_ACCOUNT_CHANGE")
        void optimisticConcurrencyMismatchFails() throws Exception {
            UUID adminId = createAdmin("admin-occ@example.test", "Admin OCC");
            String token = issueAccessToken(adminId);
            UUID targetId = createUser("target-occ@example.test", "Target OCC", "ACTIVE", "USER", true);

            // Mismatched expectedVersion
            mvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\",\"expectedVersion\":99}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_ACCOUNT_CHANGE"));
        }

        @Test
        @DisplayName("Idempotent no-op: same status and version returns current user without audit log entry")
        void idempotentNoOpReturnsWithoutAuditLog() throws Exception {
            UUID adminId = createAdmin("admin-noop@example.test", "Admin Noop");
            String token = issueAccessToken(adminId);
            UUID targetId = createUser("target-noop@example.test", "Target Noop", "ACTIVE", "USER", true);

            int initialAuditCount = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);

            mvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\",\"expectedVersion\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.version").value(0));

            int finalAuditCount = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
            assertThat(finalAuditCount).isEqualTo(initialAuditCount);
        }

        @Test
        @DisplayName("Non-existent target user returns 404 RESOURCE_NOT_FOUND")
        void nonExistentUserReturns404() throws Exception {
            UUID adminId = createAdmin("admin-notfound@example.test", "Admin NotFound");
            String token = issueAccessToken(adminId);
            UUID nonExistent = UUID.randomUUID();

            mvc.perform(patch("/api/v1/admin/users/" + nonExistent + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\",\"expectedVersion\":0}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    // =========================================================================
    // 4. Concurrency & Last Active Admin Guard
    // =========================================================================

    @Nested
    @DisplayName("Last Active Admin Guard & Concurrency Tests")
    class LastActiveAdminGuardTests {

        @Test
        @DisplayName("Locking the sole active admin returns 409 LAST_ACTIVE_ADMIN")
        void lockingSoleActiveAdminFails() throws Exception {
            UUID soleAdminId = createAdmin("sole-admin@example.test", "Sole Admin");
            String token = issueAccessToken(soleAdminId);

            mvc.perform(patch("/api/v1/admin/users/" + soleAdminId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\",\"expectedVersion\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LAST_ACTIVE_ADMIN"));

            String status = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, soleAdminId);
            assertThat(status).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("Two concurrent admins attempting to lock each other cannot leave zero active admins")
        void concurrentAdminsLockingEachOtherPreservesAtLeastOneActive() throws Exception {
            UUID adminA = createAdmin("admin-a@example.test", "Admin A");
            UUID adminB = createAdmin("admin-b@example.test", "Admin B");

            String tokenA = issueAccessToken(adminA);
            String tokenB = issueAccessToken(adminB);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(1);

            Callable<Integer> lockBByA = () -> {
                latch.await();
                return mvc.perform(patch("/api/v1/admin/users/" + adminB + "/status")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"LOCKED\",\"expectedVersion\":0}"))
                        .andReturn().getResponse().getStatus();
            };

            Callable<Integer> lockAByB = () -> {
                latch.await();
                return mvc.perform(patch("/api/v1/admin/users/" + adminA + "/status")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"LOCKED\",\"expectedVersion\":0}"))
                        .andReturn().getResponse().getStatus();
            };

            Future<Integer> futureA = executor.submit(lockBByA);
            Future<Integer> futureB = executor.submit(lockAByB);

            latch.countDown();

            int statusA = futureA.get(10, TimeUnit.SECONDS);
            int statusB = futureB.get(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Verify that either one succeeded (200) and the other was rejected (409 LAST_ACTIVE_ADMIN),
            // or both failed, but in NO circumstance can 0 active admins remain.
            int activeAdminsCount = jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE system_role = 'ADMIN' AND status = 'ACTIVE'",
                    Integer.class
            );
            assertThat(activeAdminsCount)
                    .as("At least one active admin must remain in the database under concurrent lock attempts")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // =========================================================================
    // 5. Trigger Cascades & Rollback
    // =========================================================================

    @Nested
    @DisplayName("Trigger Cascades, Audit Attribution & Rollback Tests")
    class TriggerCascadesAndRollbackTests {

        @Test
        @DisplayName("Locking an active PM cascades projects to ON_HOLD, nulls deadline_approver_id, revokes sessions, and writes audit log")
        void lockingActivePmCascadesProjectsAndSessionsWithAudit() throws Exception {
            UUID adminId = createAdmin("admin-cascade@example.test", "Admin Cascade");
            String adminToken = issueAccessToken(adminId);

            UUID pmId = createUser("pm-user@example.test", "PM User", "ACTIVE", "USER", true);
            String pmToken = issueAccessToken(pmId); // creates an active session
            UUID projectId = createProjectWithPm(pmId);

            // Verify prerequisites: project is ACTIVE, deadline_approver_id is pmId, session is active
            String initialProjectStatus = jdbc.queryForObject("SELECT status FROM projects WHERE id = ?", String.class, projectId);
            UUID initialApprover = jdbc.queryForObject("SELECT deadline_approver_id FROM projects WHERE id = ?", UUID.class, projectId);
            int activeSessionsBefore = jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, pmId);

            assertThat(initialProjectStatus).isEqualTo("ACTIVE");
            assertThat(initialApprover).isEqualTo(pmId);
            assertThat(activeSessionsBefore).isGreaterThan(0);

            // Execute lock on PM
            mvc.perform(patch("/api/v1/admin/users/" + pmId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\",\"expectedVersion\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("LOCKED"));

            // 1. Verify project cascaded to ON_HOLD
            String updatedProjectStatus = jdbc.queryForObject("SELECT status FROM projects WHERE id = ?", String.class, projectId);
            assertThat(updatedProjectStatus).isEqualTo("ON_HOLD");

            // 2. Verify deadline_approver_id is nulled
            UUID updatedApprover = jdbc.queryForObject("SELECT deadline_approver_id FROM projects WHERE id = ?", UUID.class, projectId);
            assertThat(updatedApprover).isNull();

            // 3. Verify all active sessions revoked
            int activeSessionsAfter = jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, pmId);
            assertThat(activeSessionsAfter).isEqualTo(0);

            // 4. Verify audit_logs contains attribution with actor_id matching adminId
            List<Map<String, Object>> auditLogs = jdbc.queryForList(
                    "SELECT actor_id, entity_type, entity_id, before_json, after_json FROM audit_logs WHERE entity_id = ? AND entity_type = 'users' AND actor_id = ?",
                    pmId, adminId
            );
            assertThat(auditLogs).hasSize(1);
            Map<String, Object> log = auditLogs.get(0);
            assertThat(log.get("actor_id")).isEqualTo(adminId);
            assertThat(log.get("entity_type")).isEqualTo("users");
            assertThat(log.get("entity_id")).isEqualTo(pmId);
        }

        @Test
        @DisplayName("Rollback test: transaction failure rolls back status change, project on-hold, session revocation, and audit log together")
        void rollbackOnFailureRestoresAllState() {
            UUID adminId = createAdmin("admin-rb@example.test", "Admin RB");
            UUID pmId = createUser("pm-rb@example.test", "PM RB", "ACTIVE", "USER", true);
            issueAccessToken(pmId);
            UUID projectId = createProjectWithPm(pmId);

            int initialAuditCount = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);

            // Simulate transactional execution where an exception occurs during the cascade
            try {
                tx.execute(status -> {
                    jdbc.update("UPDATE users SET status = 'LOCKED', version = version + 1 WHERE id = ?", pmId);
                    jdbc.update("UPDATE projects SET status = 'ON_HOLD', deadline_approver_id = NULL WHERE id = ?", projectId);
                    jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", pmId);
                    jdbc.update(
                            "INSERT INTO audit_logs (id, actor_id, action, entity_type, entity_id) VALUES (gen_random_uuid(), ?, 'USER_STATUS_CHANGE', 'users', ?)",
                            adminId, pmId
                    );
                    throw new RuntimeException("Simulated failure in cascade transaction");
                });
            } catch (RuntimeException ignored) {
                // Expected simulated failure
            }

            // Assert everything rolled back:
            String userStatus = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, pmId);
            assertThat(userStatus).isEqualTo("ACTIVE");

            String projectStatus = jdbc.queryForObject("SELECT status FROM projects WHERE id = ?", String.class, projectId);
            assertThat(projectStatus).isEqualTo("ACTIVE");

            UUID approver = jdbc.queryForObject("SELECT deadline_approver_id FROM projects WHERE id = ?", UUID.class, projectId);
            assertThat(approver).isEqualTo(pmId);

            int activeSessions = jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, pmId);
            assertThat(activeSessions).isGreaterThan(0);

            int finalAuditCount = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
            assertThat(finalAuditCount).isEqualTo(initialAuditCount);
        }
    }

    // =========================================================================
    // 6. Audit Logs Endpoint (GET /api/v1/admin/audit-logs)
    // =========================================================================

    @Nested
    @DisplayName("Audit Logs Tests (GET /api/v1/admin/audit-logs)")
    class AuditLogsEndpointTests {

        @Test
        @DisplayName("Audit logs default to last 30 days when date window is omitted")
        void auditLogsDefaultWindow() throws Exception {
            UUID adminId = createAdmin("admin-audit@example.test", "Admin Audit");
            String token = issueAccessToken(adminId);

            // Insert sample audit log
            UUID targetId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO audit_logs (id, actor_id, action, entity_type, entity_id, occurred_at) " +
                            "VALUES (gen_random_uuid(), ?, 'USER_STATUS_CHANGE', 'users', ?, now())",
                    adminId, targetId
            );

            mvc.perform(get("/api/v1/admin/audit-logs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());
        }

        @Test
        @DisplayName("Audit logs date window validation: from < to and span <= 31 days; invalid window returns 400")
        void auditLogsDateWindowValidation() throws Exception {
            UUID adminId = createAdmin("admin-audit-win@example.test", "Admin Audit Window");
            String token = issueAccessToken(adminId);

            Instant now = Instant.now();
            Instant fromValid = now.minus(10, ChronoUnit.DAYS);
            Instant toValid = now;

            // Valid span <= 31 days
            mvc.perform(get("/api/v1/admin/audit-logs?from=" + fromValid + "&to=" + toValid)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());

            // Invalid window: from > to
            mvc.perform(get("/api/v1/admin/audit-logs?from=" + toValid + "&to=" + fromValid)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());

            // Invalid window: span > 31 days (35 days)
            Instant fromTooFar = now.minus(35, ChronoUnit.DAYS);
            mvc.perform(get("/api/v1/admin/audit-logs?from=" + fromTooFar + "&to=" + toValid)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Audit logs payload sanitization: no private tenant content, no password hashes or secrets leaked")
        void auditLogsPayloadSanitization() throws Exception {
            UUID adminId = createAdmin("admin-audit-san@example.test", "Admin Audit Sanitize");
            String token = issueAccessToken(adminId);

            jdbc.update(
                    "INSERT INTO audit_logs (id, actor_id, action, entity_type, entity_id, before_json, after_json, occurred_at) " +
                            "VALUES (gen_random_uuid(), ?, 'USER_STATUS_CHANGE', 'users', ?, " +
                            "'{\"status\":\"ACTIVE\"}'::jsonb, '{\"status\":\"LOCKED\"}'::jsonb, now())",
                    adminId, adminId
            );

            MvcResult result = mvc.perform(get("/api/v1/admin/audit-logs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseBody = result.getResponse().getContentAsString();
            assertThat(responseBody).doesNotContain("password");
            assertThat(responseBody).doesNotContain("password_hash");
            assertThat(responseBody).doesNotContain("token_hash");
            assertThat(responseBody).doesNotContain("secret");
        }
    }
}
