package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
public class WorkspaceScopingIntegrationTest {

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
        byte[] sessionKey = new byte[32];
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        sessionKey[0] = 9;
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

    private UUID createWorkspaceDirectly(UUID ownerId, String name, String slug) {
        return tx.execute(status -> {
            UUID workspaceId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO workspaces (id, slug, name, owner_id, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'SETUP_INCOMPLETE', ?, ?, 0)",
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

    private void addWorkspaceMemberDirectly(UUID workspaceId, UUID userId, String role, String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                workspaceId, userId, role, status, now, now, now
        );
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

    // --- Tests ---

    @Nested
    @DisplayName("1. Atomic Workspace Creation")
    class AtomicWorkspaceCreationTests {

        @Test
        @DisplayName("POST /api/v1/workspaces creates workspace (SETUP_INCOMPLETE) and workspace_members (OWNER, ACTIVE) atomically")
        void creatingWorkspaceCreatesWorkspaceAndOwnerMembershipAtomically() throws Exception {
            UUID userA = createUser("usera-atomic@devsphere.test", "User A", "ACTIVE", "USER", true);
            String tokenA = issueAccessToken(userA);

            String requestBody = json.writeValueAsString(Map.of(
                    "name", "Acme Corporation",
                    "slug", "acme-corp",
                    "description", "Primary engineering workspace"
            ));

            MvcResult result = mvc.perform(post("/api/v1/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.name").value("Acme Corporation"))
                    .andExpect(jsonPath("$.slug").value("acme-corp"))
                    .andExpect(jsonPath("$.description").value("Primary engineering workspace"))
                    .andExpect(jsonPath("$.status").value("SETUP_INCOMPLETE"))
                    .andExpect(jsonPath("$.ownerId").value(userA.toString()))
                    .andReturn();

            JsonNode resp = json.readTree(result.getResponse().getContentAsString());
            UUID workspaceId = UUID.fromString(resp.get("id").asText());

            // Verify database state directly:
            // 1. Workspace table entry
            String wsStatus = jdbc.queryForObject(
                    "SELECT status FROM workspaces WHERE id = ?", String.class, workspaceId);
            assertThat(wsStatus).isEqualTo("SETUP_INCOMPLETE");

            UUID ownerIdInDb = jdbc.queryForObject(
                    "SELECT owner_id FROM workspaces WHERE id = ?", UUID.class, workspaceId);
            assertThat(ownerIdInDb).isEqualTo(userA);

            // 2. Workspace_members table entry
            String memberRole = jdbc.queryForObject(
                    "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    String.class, workspaceId, userA);
            assertThat(memberRole).isEqualTo("OWNER");

            String memberStatus = jdbc.queryForObject(
                    "SELECT status FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    String.class, workspaceId, userA);
            assertThat(memberStatus).isEqualTo("ACTIVE");

            // 3. PostgreSQL constraint trigger workspace_owner_on_workspace passed (exactly 1 active OWNER)
            Integer ownerCount = jdbc.queryForObject(
                    "SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
                    Integer.class, workspaceId);
            assertThat(ownerCount).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("2. Slug Validation & Uniqueness")
    class SlugValidationAndUniquenessTests {

        @Test
        @DisplayName("Valid slugs (lowercase alphanumeric with hyphens, 3..80 chars) return 201 Created")
        void validSlugsReturn201Created() throws Exception {
            UUID user = createUser("user-slugs@devsphere.test", "User Slugs", "ACTIVE", "USER", true);
            String token = issueAccessToken(user);

            String[] validSlugs = {"test-workspace-1", "engineering", "my-super-team-100"};
            for (String slug : validSlugs) {
                String body = json.writeValueAsString(Map.of(
                        "name", "Workspace " + slug,
                        "slug", slug
                ));
                mvc.perform(post("/api/v1/workspaces")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.slug").value(slug));
            }
        }

        @Test
        @DisplayName("Invalid slugs (uppercase, consecutive dashes, special chars, too short) return 400 VALIDATION_FAILED")
        void invalidSlugsReturn400ValidationFailed() throws Exception {
            UUID user = createUser("user-invalidslugs@devsphere.test", "User Invalid Slugs", "ACTIVE", "USER", true);
            String token = issueAccessToken(user);

            String[] invalidSlugs = {
                    "UpperCase-Slug",
                    "consecutive--dashes",
                    "-leading-dash",
                    "trailing-dash-",
                    "special@char",
                    "ab" // too short (< 3 chars)
            };

            for (String invalidSlug : invalidSlugs) {
                String body = json.writeValueAsString(Map.of(
                        "name", "Invalid Workspace",
                        "slug", invalidSlug
                ));
                mvc.perform(post("/api/v1/workspaces")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            }
        }

        @Test
        @DisplayName("Duplicate slug attempt returns 409 DUPLICATE_RESOURCE")
        void duplicateSlugReturns409DuplicateResource() throws Exception {
            UUID userA = createUser("usera-dup@devsphere.test", "User A", "ACTIVE", "USER", true);
            String tokenA = issueAccessToken(userA);

            UUID userB = createUser("userb-dup@devsphere.test", "User B", "ACTIVE", "USER", true);
            String tokenB = issueAccessToken(userB);

            String bodyA = json.writeValueAsString(Map.of(
                    "name", "Original Workspace",
                    "slug", "unique-platform"
            ));

            // User A creates it first -> 201 Created
            mvc.perform(post("/api/v1/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyA))
                    .andExpect(status().isCreated());

            // User B attempts to create with the same slug -> 409 DUPLICATE_RESOURCE
            String bodyB = json.writeValueAsString(Map.of(
                    "name", "Duplicate Workspace Attempt",
                    "slug", "unique-platform"
            ));
            mvc.perform(post("/api/v1/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyB))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        }
    }

    @Nested
    @DisplayName("3. Strict Membership Scoping (Tenant Isolation)")
    class StrictMembershipScopingTests {

        @Test
        @DisplayName("GET /api/v1/workspaces only returns workspaces where caller is an active member")
        void workspacesListingStrictTenantScoping() throws Exception {
            UUID userA = createUser("usera-scope@devsphere.test", "User A", "ACTIVE", "USER", true);
            String tokenA = issueAccessToken(userA);

            UUID userB = createUser("userb-scope@devsphere.test", "User B", "ACTIVE", "USER", true);
            String tokenB = issueAccessToken(userB);

            // User A creates Workspace A
            createWorkspaceDirectly(userA, "Workspace A", "workspace-alpha");

            // User B creates Workspace B
            createWorkspaceDirectly(userB, "Workspace B", "workspace-beta");

            // User A calls GET /api/v1/workspaces
            MvcResult resultA = mvc.perform(get("/api/v1/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                    .andExpect(status().isOk())
                    .andReturn();

            List<JsonNode> itemsA = extractItems(resultA.getResponse().getContentAsString());
            assertThat(itemsA).hasSize(1);
            assertThat(itemsA.get(0).get("slug").asText()).isEqualTo("workspace-alpha");

            // User B calls GET /api/v1/workspaces
            MvcResult resultB = mvc.perform(get("/api/v1/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                    .andExpect(status().isOk())
                    .andReturn();

            List<JsonNode> itemsB = extractItems(resultB.getResponse().getContentAsString());
            assertThat(itemsB).hasSize(1);
            assertThat(itemsB.get(0).get("slug").asText()).isEqualTo("workspace-beta");
        }

        @Test
        @DisplayName("GET /api/v1/workspaces/{workspaceId}: Non-member receives 403 ACCESS_DENIED; Member receives 200 OK")
        void workspaceGetByIdIsolation() throws Exception {
            UUID userA = createUser("usera-get@devsphere.test", "User A", "ACTIVE", "USER", true);
            String tokenA = issueAccessToken(userA);

            UUID userB = createUser("userb-get@devsphere.test", "User B", "ACTIVE", "USER", true);
            String tokenB = issueAccessToken(userB);

            UUID workspaceA = createWorkspaceDirectly(userA, "Workspace A", "workspace-get-a");

            // User A (member/owner) can read Workspace A
            mvc.perform(get("/api/v1/workspaces/" + workspaceA)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(workspaceA.toString()))
                    .andExpect(jsonPath("$.slug").value("workspace-get-a"));

            // User B (non-member) calling Workspace A receives 403 ACCESS_DENIED
            mvc.perform(get("/api/v1/workspaces/" + workspaceA)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(Matchers.is("ACCESS_DENIED"), Matchers.is("FORBIDDEN"))));
        }
    }

    @Nested
    @DisplayName("4. Member Removal Loss of Access (TC-37)")
    class MemberRemovalLossOfAccessTests {

        @Test
        @DisplayName("When member status becomes REMOVED, member immediately loses read access (zero positive cache)")
        void removedMemberImmediatelyLosesAccess() throws Exception {
            UUID owner = createUser("owner-tc37@devsphere.test", "Owner", "ACTIVE", "USER", true);
            UUID member = createUser("member-tc37@devsphere.test", "Member", "ACTIVE", "USER", true);
            String memberToken = issueAccessToken(member);

            UUID workspaceId = createWorkspaceDirectly(owner, "TC37 Workspace", "tc37-workspace");
            addWorkspaceMemberDirectly(workspaceId, member, "MEMBER", "ACTIVE");

            // 1. Member can read workspace while ACTIVE
            mvc.perform(get("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                    .andExpect(status().isOk());

            // Member sees workspace in listing
            MvcResult listActive = mvc.perform(get("/api/v1/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(extractItems(listActive.getResponse().getContentAsString())).hasSize(1);

            // 2. Status updated to REMOVED in workspace_members
            jdbc.update("UPDATE workspace_members SET status = 'REMOVED' WHERE workspace_id = ? AND user_id = ?",
                    workspaceId, member);

            // 3. Immediately denied (zero positive cache)
            mvc.perform(get("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(Matchers.is("ACCESS_DENIED"), Matchers.is("FORBIDDEN"))));

            // Listing returns empty
            MvcResult listRemoved = mvc.perform(get("/api/v1/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(extractItems(listRemoved.getResponse().getContentAsString())).isEmpty();
        }
    }

    @Nested
    @DisplayName("5. Workspace Update & Optimistic Locking")
    class WorkspaceUpdateAndOptimisticLockingTests {

        @Test
        @DisplayName("OWNER can update workspace name and description; Non-owner receives 403 ACCESS_DENIED")
        void workspaceUpdateAuthorization() throws Exception {
            UUID owner = createUser("owner-upd@devsphere.test", "Owner", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);

            UUID member = createUser("member-upd@devsphere.test", "Member", "ACTIVE", "USER", true);
            String memberToken = issueAccessToken(member);

            UUID workspaceId = createWorkspaceDirectly(owner, "Initial Name", "upd-workspace");
            addWorkspaceMemberDirectly(workspaceId, member, "MEMBER", "ACTIVE");

            // Non-owner attempts update -> 403 ACCESS_DENIED
            String memberUpdateBody = json.writeValueAsString(Map.of(
                    "name", "Hacked Name",
                    "expectedVersion", 0
            ));
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(memberUpdateBody))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(Matchers.is("ACCESS_DENIED"), Matchers.is("FORBIDDEN"))));

            // Owner performs update -> 200 OK
            String ownerUpdateBody = json.writeValueAsString(Map.of(
                    "name", "Renamed Engineering Workspace",
                    "description", "Updated description",
                    "expectedVersion", 0
            ));
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ownerUpdateBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Renamed Engineering Workspace"))
                    .andExpect(jsonPath("$.description").value("Updated description"))
                    .andExpect(jsonPath("$.version").value(1));

            // Verify DB updated
            String nameInDb = jdbc.queryForObject("SELECT name FROM workspaces WHERE id = ?", String.class, workspaceId);
            assertThat(nameInDb).isEqualTo("Renamed Engineering Workspace");
        }

        @Test
        @DisplayName("Mismatched expectedVersion returns 409 PLAN_STALE")
        void optimisticLockingMismatchReturns409PlanStale() throws Exception {
            UUID owner = createUser("owner-stale@devsphere.test", "Owner Stale", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);

            UUID workspaceId = createWorkspaceDirectly(owner, "Version 0 Name", "stale-workspace");

            // Successful update moves version from 0 to 1
            String update1 = json.writeValueAsString(Map.of(
                    "name", "Version 1 Name",
                    "expectedVersion", 0
            ));
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(update1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1));

            // Stale update with expectedVersion: 0 fails with 409 PLAN_STALE
            String staleUpdate = json.writeValueAsString(Map.of(
                    "name", "Stale Version Attempt",
                    "expectedVersion", 0
            ));
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(staleUpdate))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_STALE"));
        }
    }

    @Nested
    @DisplayName("6. Workspace Setup Endpoint")
    class WorkspaceSetupEndpointTests {

        @Test
        @DisplayName("OWNER can access GET /api/v1/workspaces/{workspaceId}/setup returning setup diagnostic facts")
        void ownerCanAccessSetupEndpoint() throws Exception {
            UUID owner = createUser("owner-setup@devsphere.test", "Owner Setup", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);

            UUID workspaceId = createWorkspaceDirectly(owner, "Setup WS", "setup-ws");

            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/setup")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                    .andExpect(jsonPath("$.status").value("SETUP_INCOMPLETE"))
                    .andExpect(jsonPath("$.projectConfigured").value(false))
                    .andExpect(jsonPath("$.pmAssigned").value(false));
        }

        @Test
        @DisplayName("Non-OWNER receives 403 ACCESS_DENIED on setup endpoint")
        void nonOwnerCannotAccessSetupEndpoint() throws Exception {
            UUID owner = createUser("owner-setup-no@devsphere.test", "Owner Setup No", "ACTIVE", "USER", true);
            UUID member = createUser("member-setup-no@devsphere.test", "Member Setup No", "ACTIVE", "USER", true);
            String memberToken = issueAccessToken(member);

            UUID workspaceId = createWorkspaceDirectly(owner, "Setup WS 2", "setup-ws-2");
            addWorkspaceMemberDirectly(workspaceId, member, "MEMBER", "ACTIVE");

            mvc.perform(get("/api/v1/workspaces/" + workspaceId + "/setup")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(Matchers.is("ACCESS_DENIED"), Matchers.is("FORBIDDEN"))));
        }
    }

    @Nested
    @DisplayName("7. Audit Attribution")
    class AuditAttributionTests {

        @Test
        @DisplayName("Workspace creation and updates record audit logs attributed to authenticated user ID")
        void auditAttributionOnWorkspaceMutations() throws Exception {
            UUID owner = createUser("owner-audit@devsphere.test", "Owner Audit", "ACTIVE", "USER", true);
            String ownerToken = issueAccessToken(owner);

            // 1. Create workspace via API
            String createBody = json.writeValueAsString(Map.of(
                    "name", "Audited Workspace",
                    "slug", "audited-workspace",
                    "description", "Audit attribution test"
            ));
            MvcResult createResult = mvc.perform(post("/api/v1/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID workspaceId = UUID.fromString(
                    json.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

            // Verify creation audit log entry
            Integer creationLogs = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND actor_id = ?",
                    Integer.class, workspaceId, owner);
            assertThat(creationLogs).isGreaterThanOrEqualTo(1);

            // 2. Update workspace via API
            String updateBody = json.writeValueAsString(Map.of(
                    "name", "Audited Workspace Updated",
                    "expectedVersion", 0
            ));
            mvc.perform(patch("/api/v1/workspaces/" + workspaceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody))
                    .andExpect(status().isOk());

            // Verify update audit log entry
            Integer totalLogs = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND actor_id = ?",
                    Integer.class, workspaceId, owner);
            assertThat(totalLogs).isGreaterThanOrEqualTo(2);
        }
    }
}
