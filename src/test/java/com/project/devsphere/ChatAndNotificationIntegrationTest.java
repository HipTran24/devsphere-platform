package com.project.devsphere;

import com.project.devsphere.identity.domain.SessionTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        "devsphere.auth.mail.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
public class ChatAndNotificationIntegrationTest {

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
        sessionKey[0] = 77;
        hmac[0] = 23;
        encryption[0] = 51;
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

    private void addWorkspaceMember(UUID workspaceId, UUID userId, String role, String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                workspaceId, userId, role, status, now, now, now
        );
    }

    private UUID createProject(UUID workspaceId, String projectKey, String name, UUID pmUserId) {
        return tx.execute(s -> {
            UUID projectId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, committed_deadline, sprint_length_days, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'description', 'objective', 'PLANNING', 'UTC', '2026-12-01', '2026-12-31', 14, ?, ?, 0)",
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

    private UUID createWorkItem(UUID projectId, String itemKey, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO work_items (id, project_id, type, item_key, title, description, status, work_kind, priority, estimate_minutes, remaining_minutes, created_at, updated_at, version) " +
                        "VALUES (?, ?, 'TASK', ?, ?, 'desc', 'TO_DO', 'NON_CODE', 'MEDIUM', 60, 60, now(), now(), 0)",
                id, projectId, itemKey, title
        );
        return id;
    }

    private UUID createChatChannel(UUID workspaceId, UUID projectId, UUID workItemId, String type, String name, UUID createdBy) {
        UUID channelId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO chat_channels (id, workspace_id, project_id, work_item_id, type, name, created_by, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                channelId, workspaceId, projectId, workItemId, type, name, createdBy, now, now
        );
        return channelId;
    }

    private UUID addChannelMember(UUID workspaceId, UUID projectId, UUID channelId, UUID userId) {
        UUID memberId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO chat_channel_members (id, workspace_id, project_id, channel_id, user_id, joined_at, muted, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, false, ?, ?, 0)",
                memberId, workspaceId, projectId, channelId, userId, now, now, now
        );
        return memberId;
    }

    private UUID sendMessage(UUID workspaceId, UUID projectId, UUID channelId, UUID senderId, String body, UUID[] mentions, UUID replyToId) {
        UUID messageId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.execute((Connection conn) -> {
            var ps = conn.prepareStatement(
                    "INSERT INTO chat_messages (id, workspace_id, project_id, channel_id, sender_id, sender_kind, body, mentions, reply_to_id, provenance, sent_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, ?, 'USER', ?, ?, ?, '{\"schemaVersion\":1}'::jsonb, ?, ?, ?, 0)"
            );
            ps.setObject(1, messageId);
            ps.setObject(2, workspaceId);
            ps.setObject(3, projectId);
            ps.setObject(4, channelId);
            ps.setObject(5, senderId);
            ps.setString(6, body);
            ps.setArray(7, conn.createArrayOf("uuid", mentions != null ? mentions : new UUID[0]));
            ps.setObject(8, replyToId);
            ps.setObject(9, now);
            ps.setObject(10, now);
            ps.setObject(11, now);
            ps.executeUpdate();
            return messageId;
        });
        return messageId;
    }

    // =========================================================================
    // 1. Trigger ds_chat_scope() - Channel Scope & Role Constraints
    // =========================================================================
    @Nested
    @DisplayName("1. Trigger ds_chat_scope() Channel Scope and Role Guards")
    class ChatScopeRoleTests {

        @Test
        @DisplayName("Channel member requires active Workspace membership")
        void testChannelMemberRequiresActiveWorkspaceMembership() {
            UUID owner = createUser("owner-cs1@example.test", "Owner CS1", "ACTIVE", "USER");
            UUID user = createUser("user-cs1@example.test", "User CS1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(owner, "ws-cs1", "Workspace CS1");
            UUID projectId = createProject(workspaceId, "CS1", "Project CS1", owner);
            UUID channelId = createChatChannel(workspaceId, projectId, null, "PROJECT", "general", owner);

            // user is NOT a member of workspaceId
            assertThatThrownBy(() -> addChannelMember(workspaceId, projectId, channelId, user))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Channel member requires active Workspace membership");

            // Even if in workspace_members, but status is 'REMOVED'
            addWorkspaceMember(workspaceId, user, "MEMBER", "REMOVED");
            assertThatThrownBy(() -> addChannelMember(workspaceId, projectId, channelId, user))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Channel member requires active Workspace membership");
        }

        @Test
        @DisplayName("Channel member requires active Project membership when channel is project-scoped")
        void testChannelMemberRequiresProjectMembership() {
            UUID owner = createUser("owner-cs2@example.test", "Owner CS2", "ACTIVE", "USER");
            UUID user = createUser("user-cs2@example.test", "User CS2", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(owner, "ws-cs2", "Workspace CS2");
            addWorkspaceMember(workspaceId, user, "MEMBER", "ACTIVE");

            UUID projectId = createProject(workspaceId, "CS2", "Project CS2", owner);
            UUID channelId = createChatChannel(workspaceId, projectId, null, "PROJECT", "dev", owner);

            // user is active workspace member, but NOT project member
            assertThatThrownBy(() -> addChannelMember(workspaceId, projectId, channelId, user))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Channel member requires Project membership");
        }

        @Test
        @DisplayName("Channel scope mismatch: member workspace_id or project_id differs from channel")
        void testChannelScopeMismatch() {
            UUID owner = createUser("owner-cs3@example.test", "Owner CS3", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(owner, "ws-cs3", "Workspace CS3");
            UUID projectId = createProject(workspaceId, "CS3", "Project CS3", owner);

            // Workspace-level channel (project_id = null)
            UUID channelId = createChatChannel(workspaceId, null, null, "TEAM", "ws-general", owner);

            // Passing non-null projectId when channel is workspace-level triggers Channel scope mismatch
            assertThatThrownBy(() -> addChannelMember(workspaceId, projectId, channelId, owner))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Channel scope mismatch");
        }

        @Test
        @DisplayName("CLIENT role is blocked from entering internal channels (TEAM, TASK, PROJECT)")
        void testClientBlockedFromInternalChannels() {
            UUID pmUser = createUser("pm-cs4@example.test", "PM CS4", "ACTIVE", "USER");
            UUID clientUser = createUser("client-cs4@example.test", "Client CS4", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-cs4", "Workspace CS4");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER", "ACTIVE");

            UUID projectId = createProject(workspaceId, "CS4", "Project CS4", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);

            UUID teamChannel = createChatChannel(workspaceId, projectId, null, "TEAM", "team-internal", pmUser);
            UUID projectChannel = createChatChannel(workspaceId, projectId, null, "PROJECT", "project-internal", pmUser);

            UUID task = createWorkItem(projectId, "CS4-1", "Task 1");
            UUID taskChannel = createChatChannel(workspaceId, projectId, task, "TASK", "task-internal", pmUser);

            // Attempting to add CLIENT to TEAM channel fails
            assertThatThrownBy(() -> addChannelMember(workspaceId, projectId, teamChannel, clientUser))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("CLIENT-only member cannot enter internal channel");

            // Attempting to add CLIENT to PROJECT channel fails
            assertThatThrownBy(() -> addChannelMember(workspaceId, projectId, projectChannel, clientUser))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("CLIENT-only member cannot enter internal channel");

            // Attempting to add CLIENT to TASK channel fails
            assertThatThrownBy(() -> addChannelMember(workspaceId, projectId, taskChannel, clientUser))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("CLIENT-only member cannot enter internal channel");
        }

        @Test
        @DisplayName("DEVELOPER or PM role can enter internal channels")
        void testDeveloperOrPmCanEnterInternalChannels() {
            UUID pmUser = createUser("pm-cs5@example.test", "PM CS5", "ACTIVE", "USER");
            UUID devUser = createUser("dev-cs5@example.test", "Dev CS5", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-cs5", "Workspace CS5");
            addWorkspaceMember(workspaceId, devUser, "MEMBER", "ACTIVE");

            UUID projectId = createProject(workspaceId, "CS5", "Project CS5", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, devUser, "DEVELOPER", pmUser);

            UUID teamChannel = createChatChannel(workspaceId, projectId, null, "TEAM", "dev-team", pmUser);

            // Dev can join TEAM channel
            UUID devMemberId = addChannelMember(workspaceId, projectId, teamChannel, devUser);
            assertThat(devMemberId).isNotNull();

            // PM can join TEAM channel
            UUID pmMemberId = addChannelMember(workspaceId, projectId, teamChannel, pmUser);
            assertThat(pmMemberId).isNotNull();
        }

        @Test
        @DisplayName("CLIENT_PM channel only allows CLIENT or PM roles (DEVELOPER-only rejected)")
        void testClientPmChannelRoleRestrictions() {
            UUID pmUser = createUser("pm-cs6@example.test", "PM CS6", "ACTIVE", "USER");
            UUID clientUser = createUser("client-cs6@example.test", "Client CS6", "ACTIVE", "USER");
            UUID devUser = createUser("dev-cs6@example.test", "Dev CS6", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(pmUser, "ws-cs6", "Workspace CS6");
            addWorkspaceMember(workspaceId, clientUser, "MEMBER", "ACTIVE");
            addWorkspaceMember(workspaceId, devUser, "MEMBER", "ACTIVE");

            UUID projectId = createProject(workspaceId, "CS6", "Project CS6", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, clientUser, "CLIENT", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, devUser, "DEVELOPER", pmUser);

            UUID clientPmChannel = createChatChannel(workspaceId, projectId, null, "CLIENT_PM", "client-pm-collab", pmUser);

            // PM can join CLIENT_PM
            UUID pmMemberId = addChannelMember(workspaceId, projectId, clientPmChannel, pmUser);
            assertThat(pmMemberId).isNotNull();

            // CLIENT can join CLIENT_PM
            UUID clientMemberId = addChannelMember(workspaceId, projectId, clientPmChannel, clientUser);
            assertThat(clientMemberId).isNotNull();

            // DEVELOPER-only is rejected from CLIENT_PM
            assertThatThrownBy(() -> addChannelMember(workspaceId, projectId, clientPmChannel, devUser))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("CLIENT_PM channel requires CLIENT or PM");
        }
    }

    // =========================================================================
    // 2. Trigger ds_chat_scope() - Message Sender & Mention Guards
    // =========================================================================
    @Nested
    @DisplayName("2. Trigger ds_chat_scope() Message Sender and Mention Invariants")
    class ChatMessageScopeTests {

        @Test
        @DisplayName("Sender must be an active channel member to send a message")
        void testSenderMustBeActiveChannelMember() {
            UUID pmUser = createUser("pm-msg1@example.test", "PM MSG1", "ACTIVE", "USER");
            UUID devUser = createUser("dev-msg1@example.test", "Dev MSG1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-msg1", "Workspace MSG1");
            addWorkspaceMember(workspaceId, devUser, "MEMBER", "ACTIVE");

            UUID projectId = createProject(workspaceId, "MSG1", "Project MSG1", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, devUser, "DEVELOPER", pmUser);

            UUID channelId = createChatChannel(workspaceId, projectId, null, "PROJECT", "general", pmUser);
            addChannelMember(workspaceId, projectId, channelId, pmUser);

            // devUser is NOT a member of channelId yet
            assertThatThrownBy(() -> sendMessage(workspaceId, projectId, channelId, devUser, "Hello world", null, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Sender is not an active channel member");

            // Add devUser to channel -> now sending message succeeds
            addChannelMember(workspaceId, projectId, channelId, devUser);
            UUID messageId = sendMessage(workspaceId, projectId, channelId, devUser, "Hello world", null, null);
            assertThat(messageId).isNotNull();
        }

        @Test
        @DisplayName("Mentions must reference members of the channel; outside user mention is rejected")
        void testMentionsMustReferenceChannelMembers() {
            UUID pmUser = createUser("pm-msg2@example.test", "PM MSG2", "ACTIVE", "USER");
            UUID dev1 = createUser("dev1-msg2@example.test", "Dev1 MSG2", "ACTIVE", "USER");
            UUID dev2 = createUser("dev2-msg2@example.test", "Dev2 MSG2", "ACTIVE", "USER");

            UUID workspaceId = createWorkspace(pmUser, "ws-msg2", "Workspace MSG2");
            addWorkspaceMember(workspaceId, dev1, "MEMBER", "ACTIVE");
            addWorkspaceMember(workspaceId, dev2, "MEMBER", "ACTIVE");

            UUID projectId = createProject(workspaceId, "MSG2", "Project MSG2", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, dev1, "DEVELOPER", pmUser);
            addProjectMemberWithRole(projectId, workspaceId, dev2, "DEVELOPER", pmUser);

            UUID channelId = createChatChannel(workspaceId, projectId, null, "PROJECT", "dev", pmUser);
            addChannelMember(workspaceId, projectId, channelId, pmUser);
            addChannelMember(workspaceId, projectId, channelId, dev1);
            // dev2 is intentionally NOT added to channelId

            // dev1 mentions dev2 who is not in the channel -> rejected
            assertThatThrownBy(() -> sendMessage(workspaceId, projectId, channelId, dev1, "Hey @dev2", new UUID[]{dev2}, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Mention must reference channel membership");

            // dev1 mentions pmUser who IS in the channel -> succeeds
            UUID msgId = sendMessage(workspaceId, projectId, channelId, dev1, "Hey @pm", new UUID[]{pmUser}, null);
            assertThat(msgId).isNotNull();
        }

        @Test
        @DisplayName("fk_reply_same_channel ensures reply_to_id belongs to the same channel")
        void testReplyToMustBelongToSameChannel() {
            UUID pmUser = createUser("pm-msg3@example.test", "PM MSG3", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-msg3", "Workspace MSG3");
            UUID projectId = createProject(workspaceId, "MSG3", "Project MSG3", pmUser);

            UUID channelA = createChatChannel(workspaceId, projectId, null, "PROJECT", "channel-a", pmUser);
            UUID channelB = createChatChannel(workspaceId, projectId, null, "PROJECT", "channel-b", pmUser);
            addChannelMember(workspaceId, projectId, channelA, pmUser);
            addChannelMember(workspaceId, projectId, channelB, pmUser);

            UUID msgA = sendMessage(workspaceId, projectId, channelA, pmUser, "Message in channel A", null, null);

            // Reply in same channel succeeds
            UUID replyA = sendMessage(workspaceId, projectId, channelA, pmUser, "Reply in channel A", null, msgA);
            assertThat(replyA).isNotNull();

            // Attempting to reply to msgA from channelB fails with foreign key violation fk_reply_same_channel
            assertThatThrownBy(() -> sendMessage(workspaceId, projectId, channelB, pmUser, "Cross-channel reply", null, msgA))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Check constraint: reply_to_id cannot equal message id (no self reply)")
        void testReplyToSelfCheckConstraint() {
            UUID pmUser = createUser("pm-msg4@example.test", "PM MSG4", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-msg4", "Workspace MSG4");
            UUID projectId = createProject(workspaceId, "MSG4", "Project MSG4", pmUser);

            UUID channel = createChatChannel(workspaceId, projectId, null, "PROJECT", "general", pmUser);
            addChannelMember(workspaceId, projectId, channel, pmUser);

            UUID selfId = UUID.randomUUID();
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO chat_messages (id, workspace_id, project_id, channel_id, sender_id, sender_kind, body, reply_to_id, provenance, sent_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, ?, 'USER', 'Self-replying', ?, '{\"schemaVersion\":1}'::jsonb, now(), now(), now(), 0)",
                    selfId, workspaceId, projectId, channel, pmUser, selfId
            )).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 3. Notification Deduplication
    // =========================================================================
    @Nested
    @DisplayName("3. Notification Deduplication & Constraints")
    class NotificationDeduplicationTests {

        @Test
        @DisplayName("uq_notifications_user_id_event_key: duplicate (user_id, event_key) is rejected")
        void testNotificationDeduplication() {
            UUID userA = createUser("usera-notif@example.test", "User A", "ACTIVE", "USER");
            UUID userB = createUser("userb-notif@example.test", "User B", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(userA, "ws-notif", "Workspace Notif");

            String eventKey = "sprint:started:sprint-42";

            // 1. Insert first notification for userA
            UUID notif1 = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO notifications (id, workspace_id, user_id, event_type, event_key, title, body, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 'SPRINT_STARTED', ?, 'Sprint Started', 'Sprint 42 has started.', now(), now(), 0)",
                    notif1, workspaceId, userA, eventKey
            );

            // 2. Duplicate notification for userA with identical event_key must be rejected
            UUID notif2 = UUID.randomUUID();
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO notifications (id, workspace_id, user_id, event_type, event_key, title, body, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 'SPRINT_STARTED', ?, 'Sprint Started Again', 'Duplicate event.', now(), now(), 0)",
                    notif2, workspaceId, userA, eventKey
            )).isInstanceOf(DuplicateKeyException.class);

            // 3. Same event_key for distinct userB succeeds
            UUID notif3 = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO notifications (id, workspace_id, user_id, event_type, event_key, title, body, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 'SPRINT_STARTED', ?, 'Sprint Started', 'Sprint 42 has started.', now(), now(), 0)",
                    notif3, workspaceId, userB, eventKey
            );

            // 4. Distinct event_key for userA succeeds
            UUID notif4 = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO notifications (id, workspace_id, user_id, event_type, event_key, title, body, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 'SPRINT_COMPLETED', 'sprint:completed:sprint-42', 'Sprint Done', 'Sprint 42 completed.', now(), now(), 0)",
                    notif4, workspaceId, userA
            );

            Long countUserA = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Long.class, userA);
            assertThat(countUserA).isEqualTo(2L);
        }
    }
}
