package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.application.WorkspaceScope;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.identity.domain.SessionTokens;
import jakarta.servlet.http.Cookie;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
public class AccessPolicyIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @Autowired private AccessPolicy accessPolicy;
    @MockitoBean private JavaMailSender mailSender;

    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] sessionKey = new byte[32];
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        sessionKey[0] = 7;
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

    // --- Helper Fixture Methods ---

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

    private SessionService.Principal createSession(UUID userId, String displayName, String systemRole, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
        UUID sessionId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime issuedAt = (revokedAt != null && !revokedAt.isAfter(now))
                ? revokedAt.minusMinutes(1)
                : (expiresAt != null && expiresAt.isBefore(now)) ? expiresAt.minusDays(1) : now;
        byte[] refreshHashBytes = new byte[32];
        random.nextBytes(refreshHashBytes);
        String refreshHash = Base64.getEncoder().encodeToString(refreshHashBytes);

        jdbc.update(
                "INSERT INTO auth_sessions (id, user_id, refresh_hash, issued_at, expires_at, revoked_at, session_family_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, userId, refreshHash, issuedAt, expiresAt, revokedAt, familyId, issuedAt
        );

        return new SessionService.Principal(userId, sessionId, displayName, systemRole);
    }

    private String issueAccessToken(UUID userId, UUID sessionId) {
        return sessionTokens.issueAccess(userId, sessionId).value();
    }

    private UUID createWorkspace(UUID ownerId, String name, String slug) {
        return tx.execute(status -> {
            UUID workspaceId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO workspaces (id, slug, name, owner_id, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
                    workspaceId, slug != null ? slug : "ws-" + workspaceId, name, ownerId, now, now
            );
            jdbc.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'OWNER', 'ACTIVE', ?, ?, ?, 0)",
                    workspaceId, ownerId, now, now, now
            );
            return workspaceId;
        });
    }

    private void addWorkspaceMember(UUID workspaceId, UUID userId, String role, String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                workspaceId, userId, role, status, now, now, now
        );
    }

    private UUID createProject(UUID workspaceId, String projectKey, String name) {
        return tx.execute(status -> {
            UUID projectId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'PLANNING', ?, ?, 0)",
                    projectId, workspaceId, projectKey, name, now, now
            );
            return projectId;
        });
    }

    private UUID createProjectWithPm(UUID workspaceId, String projectKey, String name, UUID pmUserId) {
        return tx.execute(status -> {
            UUID projectId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            UUID roleId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ? AND status = 'ACTIVE'",
                    Integer.class, workspaceId, pmUserId);
            if (count == null || count == 0) {
                jdbc.update(
                        "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                                "VALUES (?, ?, 'MEMBER', 'ACTIVE', ?, ?, ?, 0)",
                        workspaceId, pmUserId, now, now, now
                );
            }

            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'PLANNING', ?, ?, 0)",
                    projectId, workspaceId, projectKey, name, now, now
            );
            jdbc.update(
                    "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                    memberId, projectId, workspaceId, pmUserId, now, now, now
            );
            jdbc.update(
                    "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at, created_at) " +
                            "VALUES (?, ?, ?, 'PM', ?, ?, ?, ?, ?)",
                    roleId, projectId, memberId, pmUserId, now, pmUserId, now, now
            );
            return projectId;
        });
    }

    private UUID addProjectMember(UUID projectId, UUID workspaceId, UUID userId, String status) {
        UUID memberId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                memberId, projectId, workspaceId, userId, status, now, now, now
        );
        return memberId;
    }

    private UUID addProjectMemberRole(UUID projectId, UUID projectMemberId, String role, UUID grantedBy, UUID acceptedBy, OffsetDateTime revokedAt) {
        UUID roleId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at, revoked_at, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                roleId, projectId, projectMemberId, role, grantedBy, now, acceptedBy, (acceptedBy != null ? now : null), revokedAt, now
        );
        return roleId;
    }

    // --- Tests ---

    @Nested
    @DisplayName("1. Default-Deny Policy Matrix")
    class DefaultDenyPolicyMatrixTests {

        @Test
        @DisplayName("Unauthenticated caller is rejected across all policy guard methods")
        void unauthenticatedCallerIsRejected() {
            UUID dummyUser = createUser("dummy@devsphere.test", "Dummy", "ACTIVE", "USER", true);
            UUID wsId = createWorkspace(dummyUser, "Dummy WS", "dummy-ws");
            UUID prjId = createProject(wsId, "DMY", "Dummy Project");

            assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(null))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accessPolicy.requireWorkspaceMember(null, wsId))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accessPolicy.requireWorkspaceOwner(null, wsId))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accessPolicy.requireProjectMember(null, wsId, prjId))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accessPolicy.requireProjectRole(null, wsId, prjId, ProjectRole.PM))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accessPolicy.requireAnyProjectRole(null, wsId, prjId, Set.of(ProjectRole.PM)))
                    .isInstanceOf(AccessDeniedException.class);

            SessionService.Principal nullUserPrincipal = new SessionService.Principal(null, UUID.randomUUID(), "Name", "USER");
            assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(nullUserPrincipal))
                    .isInstanceOf(AccessDeniedException.class);

            SessionService.Principal nullSessionPrincipal = new SessionService.Principal(UUID.randomUUID(), null, "Name", "USER");
            assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(nullSessionPrincipal))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Non-existent user or unverified user (email_verified_at IS NULL) is denied (403 ACCESS_DENIED)")
        void nonExistentOrUnverifiedUserIsDenied() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            // Non-existent user
            SessionService.Principal ghostPrincipal = new SessionService.Principal(UUID.randomUUID(), UUID.randomUUID(), "Ghost", "USER");
            assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(ghostPrincipal))
                    .isInstanceOf(AccessDeniedException.class);

            // User exists but unverified (email_verified_at IS NULL)
            UUID unverifiedId = createUser("unverified@devsphere.test", "Unverified", "ACTIVE", "ADMIN", false);
            SessionService.Principal unverifiedPrincipal = createSession(unverifiedId, "Unverified", "ADMIN", now.plusDays(7), null);

            assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(unverifiedPrincipal))
                    .isInstanceOf(AccessDeniedException.class);

            UUID wsId = createWorkspace(unverifiedId, "Unverified WS", "unverified-ws");
            assertThatThrownBy(() -> accessPolicy.requireWorkspaceMember(unverifiedPrincipal, wsId))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Locked user (status = 'LOCKED') is denied access even with active session")
        void lockedUserIsDenied() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID lockedId = createUser("locked@devsphere.test", "Locked User", "LOCKED", "ADMIN", true);
            SessionService.Principal lockedPrincipal = createSession(lockedId, "Locked User", "ADMIN", now.plusDays(7), null);

            assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(lockedPrincipal))
                    .isInstanceOf(AccessDeniedException.class);

            UUID dummyUser = createUser("dummy3@devsphere.test", "Dummy 3", "ACTIVE", "USER", true);
            UUID wsId = createWorkspace(dummyUser, "Dummy WS 3", "dummy-ws-3");
            assertThatThrownBy(() -> accessPolicy.requireWorkspaceMember(lockedPrincipal, wsId))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Revoked or expired session is denied")
        void revokedOrExpiredSessionIsDenied() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID userId = createUser("session@devsphere.test", "Session User", "ACTIVE", "ADMIN", true);
            UUID dummyUser = createUser("dummy4@devsphere.test", "Dummy 4", "ACTIVE", "USER", true);
            UUID wsId = createWorkspace(dummyUser, "Dummy WS 4", "dummy-ws-4");

            // Revoked session
            SessionService.Principal revokedPrincipal = createSession(userId, "Session User", "ADMIN", now.plusDays(7), now.minusMinutes(5));
            assertThatThrownBy(() -> accessPolicy.requireWorkspaceMember(revokedPrincipal, wsId))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(revokedPrincipal))
                    .isInstanceOf(AccessDeniedException.class);

            // Expired session
            SessionService.Principal expiredPrincipal = createSession(userId, "Session User", "ADMIN", now.minusMinutes(10), null);
            assertThatThrownBy(() -> accessPolicy.requireWorkspaceMember(expiredPrincipal, wsId))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(expiredPrincipal))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("User without system_role = 'ADMIN' is denied system admin access")
        void regularUserCannotAccessSystemAdmin() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID userId = createUser("regular@devsphere.test", "Regular User", "ACTIVE", "USER", true);
            SessionService.Principal principal = createSession(userId, "Regular User", "USER", now.plusDays(7), null);

            assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(principal))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("System ADMIN attempting to access private workspace or project without membership is denied (ADMIN has no tenant bypass)")
        void systemAdminHasNoTenantBypass() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID adminId = createUser("sysadmin@devsphere.test", "System Admin", "ACTIVE", "ADMIN", true);
            SessionService.Principal adminPrincipal = createSession(adminId, "System Admin", "ADMIN", now.plusDays(7), null);

            UUID ownerId = createUser("owner@devsphere.test", "Workspace Owner", "ACTIVE", "USER", true);
            UUID wsId = createWorkspace(ownerId, "Private Workspace", "private-ws");
            UUID prjId = createProject(wsId, "PRV", "Private Project");

            // System admin must be rejected since ADMIN does NOT bypass workspace or project tenant boundaries
            assertThatThrownBy(() -> accessPolicy.requireWorkspaceMember(adminPrincipal, wsId))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accessPolicy.requireWorkspaceOwner(adminPrincipal, wsId))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accessPolicy.requireProjectMember(adminPrincipal, wsId, prjId))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Workspace OWNER without PM role attempting to access PM-required policy is denied (OWNER is not PM)")
        void workspaceOwnerWithoutPmRoleIsDeniedPmPolicy() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID ownerId = createUser("wsowner@devsphere.test", "Workspace Owner", "ACTIVE", "USER", true);
            SessionService.Principal ownerPrincipal = createSession(ownerId, "Workspace Owner", "USER", now.plusDays(7), null);

            UUID pmId = createUser("pm@devsphere.test", "Project Manager", "ACTIVE", "USER", true);

            UUID wsId = createWorkspace(ownerId, "Company Workspace", "company-ws");
            UUID prjId = createProjectWithPm(wsId, "PRJ", "Company Project", pmId);

            // Owner is an active member and owner in workspace
            WorkspaceScope wsScope = accessPolicy.requireWorkspaceOwner(ownerPrincipal, wsId);
            assertThat(wsScope.owner()).isTrue();

            // But owner is NOT assigned PM role in project -> must be denied PM policy
            assertThatThrownBy(() -> accessPolicy.requireProjectRole(ownerPrincipal, wsId, prjId, ProjectRole.PM))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Workspace member removed (status = 'REMOVED') is immediately denied (zero positive cache)")
        void removedWorkspaceMemberIsImmediatelyDenied() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID ownerId = createUser("wsowner2@devsphere.test", "Owner 2", "ACTIVE", "USER", true);
            UUID memberUserId = createUser("member@devsphere.test", "Member User", "ACTIVE", "USER", true);
            SessionService.Principal memberPrincipal = createSession(memberUserId, "Member User", "USER", now.plusDays(7), null);

            UUID wsId = createWorkspace(ownerId, "Test WS", "test-ws-cache");
            addWorkspaceMember(wsId, memberUserId, "MEMBER", "ACTIVE");

            // Active member can access
            WorkspaceScope scope = accessPolicy.requireWorkspaceMember(memberPrincipal, wsId);
            assertThat(scope.userId()).isEqualTo(memberUserId);

            // Mark member as REMOVED
            jdbc.update("UPDATE workspace_members SET status = 'REMOVED' WHERE workspace_id = ? AND user_id = ?",
                    wsId, memberUserId);

            // Zero positive cache: subsequent call must immediately throw AccessDeniedException
            assertThatThrownBy(() -> accessPolicy.requireWorkspaceMember(memberPrincipal, wsId))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Project role revoked (revoked_at IS NOT NULL) is immediately denied")
        void revokedProjectRoleIsImmediatelyDenied() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID ownerId = createUser("wsowner3@devsphere.test", "Owner 3", "ACTIVE", "USER", true);
            UUID devId = createUser("dev@devsphere.test", "Developer", "ACTIVE", "USER", true);
            SessionService.Principal devPrincipal = createSession(devId, "Developer", "USER", now.plusDays(7), null);

            UUID wsId = createWorkspace(ownerId, "Project WS", "proj-ws-revocation");
            addWorkspaceMember(wsId, devId, "MEMBER", "ACTIVE");
            UUID prjId = createProject(wsId, "DEV", "Development Project");
            UUID memberId = addProjectMember(prjId, wsId, devId, "ACTIVE");
            UUID roleId = addProjectMemberRole(prjId, memberId, "DEVELOPER", ownerId, null, null);

            // Initially has DEVELOPER role
            ProjectScope scope = accessPolicy.requireProjectRole(devPrincipal, wsId, prjId, ProjectRole.DEVELOPER);
            assertThat(scope.hasRole(ProjectRole.DEVELOPER)).isTrue();

            // Revoke the role in DB
            jdbc.update("UPDATE project_member_roles SET revoked_at = clock_timestamp() WHERE id = ?", roleId);

            // Immediately denied
            assertThatThrownBy(() -> accessPolicy.requireProjectRole(devPrincipal, wsId, prjId, ProjectRole.DEVELOPER))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Cross-tenant mismatch (passing workspace A with project B of workspace C) is denied without disclosing existence")
        void crossTenantMismatchIsDeniedWithoutInformationDisclosure() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID userId = createUser("multitenant@devsphere.test", "Multi Tenant User", "ACTIVE", "USER", true);
            SessionService.Principal principal = createSession(userId, "Multi Tenant User", "USER", now.plusDays(7), null);

            // Workspace A
            UUID wsA = createWorkspace(userId, "Workspace A", "workspace-a");

            // Workspace C and Project B belonging to Workspace C
            UUID wsC = createWorkspace(userId, "Workspace C", "workspace-c");
            UUID prjB = createProject(wsC, "PRJB", "Project B in C");
            UUID memberB = addProjectMember(prjB, wsC, userId, "ACTIVE");
            addProjectMemberRole(prjB, memberB, "DEVELOPER", userId, null, null);

            // Attempting to access Project B using Workspace A must be denied
            assertThatThrownBy(() -> accessPolicy.requireProjectMember(principal, wsA, prjB))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("Access denied");
        }

        @Test
        @DisplayName("Multiple project roles: user with DEVELOPER + VIEWER correctly unioned")
        void multipleProjectRolesCorrectlyUnioned() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID ownerId = createUser("owner4@devsphere.test", "Owner 4", "ACTIVE", "USER", true);
            UUID multiRoleId = createUser("multirole@devsphere.test", "Multi Role User", "ACTIVE", "USER", true);
            SessionService.Principal principal = createSession(multiRoleId, "Multi Role User", "USER", now.plusDays(7), null);

            UUID wsId = createWorkspace(ownerId, "Multi Role WS", "multi-role-ws");
            addWorkspaceMember(wsId, multiRoleId, "MEMBER", "ACTIVE");
            UUID prjId = createProject(wsId, "MLT", "Multi Role Project");
            UUID memberId = addProjectMember(prjId, wsId, multiRoleId, "ACTIVE");

            addProjectMemberRole(prjId, memberId, "DEVELOPER", ownerId, null, null);
            addProjectMemberRole(prjId, memberId, "VIEWER", ownerId, null, null);

            ProjectScope scope = accessPolicy.requireProjectMember(principal, wsId, prjId);

            assertThat(scope.roles()).containsExactlyInAnyOrder(ProjectRole.DEVELOPER, ProjectRole.VIEWER);
            assertThat(scope.hasRole(ProjectRole.DEVELOPER)).isTrue();
            assertThat(scope.hasRole(ProjectRole.VIEWER)).isTrue();
            assertThat(scope.hasRole(ProjectRole.PM)).isFalse();
            assertThat(scope.hasRole(ProjectRole.CLIENT)).isFalse();

            // requireAnyProjectRole succeeds if any candidate matches
            ProjectScope anyScope = accessPolicy.requireAnyProjectRole(principal, wsId, prjId,
                    Set.of(ProjectRole.CLIENT, ProjectRole.DEVELOPER));
            assertThat(anyScope).isNotNull();

            // requireAnyProjectRole fails if none matches
            assertThatThrownBy(() -> accessPolicy.requireAnyProjectRole(principal, wsId, prjId,
                    Set.of(ProjectRole.PM, ProjectRole.CLIENT)))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("2. HTTP Security Chain")
    class HttpSecurityChainTests {

        @Test
        @DisplayName("GET /api/v1/admin/users: 401 AUTH_INVALID if no Bearer token; 403 ACCESS_DENIED if regular USER")
        void adminUsersEndpointSecurityChain() throws Exception {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            // 1. Unauthenticated request -> 401 AUTH_INVALID
            mvc.perform(get("/api/v1/admin/users"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                    .andExpect(jsonPath("$.status").value(401));

            // 2. Regular USER caller with valid Bearer token -> 403 ACCESS_DENIED
            UUID regularUserId = createUser("regularhttp@devsphere.test", "Regular HTTP", "ACTIVE", "USER", true);
            SessionService.Principal userPrincipal = createSession(regularUserId, "Regular HTTP", "USER", now.plusDays(7), null);
            String userBearerToken = issueAccessToken(regularUserId, userPrincipal.sessionId());

            mvc.perform(get("/api/v1/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userBearerToken))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andExpect(jsonPath("$.status").value(403));

            // 3. ADMIN caller with valid Bearer token -> Passes security filter chain (not 401, not 403)
            UUID adminId = createUser("adminhttp@devsphere.test", "Admin HTTP", "ACTIVE", "ADMIN", true);
            SessionService.Principal adminPrincipal = createSession(adminId, "Admin HTTP", "ADMIN", now.plusDays(7), null);
            String adminBearerToken = issueAccessToken(adminId, adminPrincipal.sessionId());

            var adminResult = mvc.perform(get("/api/v1/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminBearerToken))
                    .andReturn();

            assertThat(adminResult.getResponse().getStatus())
                    .as("Admin user must pass security filter chain without 401 or 403")
                    .isNotIn(401, 403);
        }

        @Test
        @DisplayName("PATCH /api/v1/admin/users/{userId}/status: Bearer auth required, cookie-only callers rejected, exempt from cookie CSRF")
        void patchUserStatusSecurityChain() throws Exception {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID targetUserId = createUser("target@devsphere.test", "Target User", "ACTIVE", "USER", true);

            // 1. Unauthenticated or cookie-only caller -> 401 AUTH_INVALID
            mvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/status")
                            .cookie(new Cookie("__Secure-devsphere-refresh", "dummy-refresh-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                    .andExpect(jsonPath("$.status").value(401));

            // 2. Regular USER caller with Bearer token -> 403 ACCESS_DENIED
            UUID regularUserId = createUser("regularpatch@devsphere.test", "Regular Patch", "ACTIVE", "USER", true);
            SessionService.Principal userPrincipal = createSession(regularUserId, "Regular Patch", "USER", now.plusDays(7), null);
            String userBearerToken = issueAccessToken(regularUserId, userPrincipal.sessionId());

            mvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userBearerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andExpect(jsonPath("$.status").value(403));

            // 3. ADMIN caller with Bearer token is exempt from CSRF (no CSRF cookie/header required)
            UUID adminId = createUser("adminpatch@devsphere.test", "Admin Patch", "ACTIVE", "ADMIN", true);
            SessionService.Principal adminPrincipal = createSession(adminId, "Admin Patch", "ADMIN", now.plusDays(7), null);
            String adminBearerToken = issueAccessToken(adminId, adminPrincipal.sessionId());

            var patchResult = mvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminBearerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"LOCKED\"}"))
                    .andReturn();

            assertThat(patchResult.getResponse().getStatus())
                    .as("Bearer-authenticated Admin request must bypass CSRF check and pass security chain without 401 or 403")
                    .isNotIn(401, 403);
        }

        @Test
        @DisplayName("Static error code assertions: 401 AUTH_INVALID and 403 ACCESS_DENIED are strictly guaranteed")
        void staticErrorCodesGuaranteed() throws Exception {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            // Any unauthenticated access to admin endpoint
            mvc.perform(get("/api/v1/admin/users"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                    .andExpect(jsonPath("$.title").value("Unauthorized"));

            // Any non-admin authenticated access to admin endpoint
            UUID regularId = createUser("staticerr@devsphere.test", "Static Err", "ACTIVE", "USER", true);
            SessionService.Principal userPrincipal = createSession(regularId, "Static Err", "USER", now.plusDays(7), null);
            String userBearerToken = issueAccessToken(regularId, userPrincipal.sessionId());

            mvc.perform(get("/api/v1/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userBearerToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andExpect(jsonPath("$.title").value("Forbidden"));
        }
    }
}
