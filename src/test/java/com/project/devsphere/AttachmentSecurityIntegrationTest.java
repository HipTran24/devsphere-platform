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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
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
public class AttachmentSecurityIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @MockitoBean private JavaMailSender mailSender;

    private static final String VALID_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20,971,520 bytes

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] sessionKey = new byte[32];
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        sessionKey[0] = 88;
        hmac[0] = 34;
        encryption[0] = 62;
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

    private UUID createWorkItem(UUID projectId, String itemKey, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO work_items (id, project_id, type, item_key, title, description, status, work_kind, priority, estimate_minutes, remaining_minutes, created_at, updated_at, version) " +
                        "VALUES (?, ?, 'TASK', ?, ?, 'desc', 'TO_DO', 'NON_CODE', 'MEDIUM', 60, 60, now(), now(), 0)",
                id, projectId, itemKey, title
        );
        return id;
    }

    private UUID createChatMessage(UUID workspaceId, UUID projectId, UUID channelId, UUID senderId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO chat_messages (id, workspace_id, project_id, channel_id, sender_id, sender_kind, body, sent_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, 'USER', 'Message text', ?, ?, ?, 0)",
                id, workspaceId, projectId, channelId, senderId, now, now, now
        );
        return id;
    }

    private UUID createChatChannel(UUID workspaceId, UUID projectId, String type, String name, UUID createdBy) {
        UUID channelId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO chat_channels (id, workspace_id, project_id, type, name, created_by, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                channelId, workspaceId, projectId, type, name, createdBy, now, now
        );
        // Add creator as member
        jdbc.update(
                "INSERT INTO chat_channel_members (id, workspace_id, project_id, channel_id, user_id, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), workspaceId, projectId, channelId, createdBy, now, now, now
        );
        return channelId;
    }

    private UUID insertAttachment(
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            UUID chatMessageId,
            UUID reportId,
            UUID ciRunId,
            UUID uploadedBy,
            String kind,
            String storageKey,
            String filename,
            String contentType,
            long fileSize,
            String sha256,
            String visibility,
            String scanStatus) {

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO attachments (id, workspace_id, project_id, work_item_id, chat_message_id, report_id, ci_run_id, uploaded_by, kind, storage_key, filename, content_type, file_size, sha256, visibility, scan_status, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), 0)",
                id, workspaceId, projectId, workItemId, chatMessageId, reportId, ciRunId, uploadedBy, kind, storageKey, filename, contentType, fileSize, sha256, visibility, scanStatus
        );
        return id;
    }

    // =========================================================================
    // 1. File Size Constraints (<= 20MB, > 0)
    // =========================================================================
    @Nested
    @DisplayName("1. File Size Boundaries (0 to 20MB)")
    class FileSizeBoundaryTests {

        @Test
        @DisplayName("File size up to exactly 20MB (20,971,520 bytes) is accepted")
        void testFileSizeUpTo20MbAccepted() {
            UUID pmUser = createUser("pm-fs1@example.test", "PM FS1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-fs1", "Workspace FS1");
            UUID projectId = createProject(workspaceId, "FS1", "Project FS1", pmUser);
            UUID task = createWorkItem(projectId, "FS1-1", "Task 1");

            // 0 bytes (empty file)
            UUID att0 = insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/fs1-0", "empty.txt", "text/plain", 0L, VALID_SHA256, "INTERNAL", "CLEAN");
            assertThat(att0).isNotNull();

            // 1KB (1024 bytes)
            UUID att1 = insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/fs1-1k", "small.png", "image/png", 1024L, VALID_SHA256, "INTERNAL", "CLEAN");
            assertThat(att1).isNotNull();

            // Exactly 20MB (20971520 bytes)
            UUID attMax = insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/fs1-20m", "large.pdf", "application/pdf", MAX_FILE_SIZE, VALID_SHA256, "INTERNAL", "CLEAN");
            assertThat(attMax).isNotNull();
        }

        @Test
        @DisplayName("File size exceeding 20MB (20,971,521 bytes) is rejected by check constraint")
        void testFileSizeExceeding20MbRejected() {
            UUID pmUser = createUser("pm-fs2@example.test", "PM FS2", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-fs2", "Workspace FS2");
            UUID projectId = createProject(workspaceId, "FS2", "Project FS2", pmUser);
            UUID task = createWorkItem(projectId, "FS2-1", "Task 2");

            long overLimit = MAX_FILE_SIZE + 1L; // 20971521
            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/fs2-over", "huge.zip", "application/zip", overLimit, VALID_SHA256, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Negative file size is rejected by check constraint")
        void testNegativeFileSizeRejected() {
            UUID pmUser = createUser("pm-fs3@example.test", "PM FS3", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-fs3", "Workspace FS3");
            UUID projectId = createProject(workspaceId, "FS3", "Project FS3", pmUser);
            UUID task = createWorkItem(projectId, "FS3-1", "Task 3");

            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/fs3-neg", "neg.txt", "text/plain", -1L, VALID_SHA256, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 2. SHA-256 Checksum Regex Validation
    // =========================================================================
    @Nested
    @DisplayName("2. SHA-256 Checksum Regex Validation")
    class ChecksumValidationTests {

        @Test
        @DisplayName("Valid 64-char lowercase hexadecimal SHA-256 is accepted")
        void testValidSha256Accepted() {
            UUID pmUser = createUser("pm-sha1@example.test", "PM SHA1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-sha1", "Workspace SHA1");
            UUID projectId = createProject(workspaceId, "SHA1", "Project SHA1", pmUser);
            UUID task = createWorkItem(projectId, "SHA1-1", "Task 1");

            String validSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
            UUID att = insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/sha1-valid", "test.pdf", "application/pdf", 100L, validSha, "INTERNAL", "CLEAN");
            assertThat(att).isNotNull();
        }

        @Test
        @DisplayName("Uppercase hexadecimal characters in SHA-256 are rejected")
        void testUppercaseSha256Rejected() {
            UUID pmUser = createUser("pm-sha2@example.test", "PM SHA2", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-sha2", "Workspace SHA2");
            UUID projectId = createProject(workspaceId, "SHA2", "Project SHA2", pmUser);
            UUID task = createWorkItem(projectId, "SHA2-1", "Task 2");

            String upperSha = VALID_SHA256.toUpperCase();
            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/sha2-upper", "test.pdf", "application/pdf", 100L, upperSha, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Invalid length or non-hex characters in SHA-256 are rejected")
        void testInvalidLengthOrCharsRejected() {
            UUID pmUser = createUser("pm-sha3@example.test", "PM SHA3", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-sha3", "Workspace SHA3");
            UUID projectId = createProject(workspaceId, "SHA3", "Project SHA3", pmUser);
            UUID task = createWorkItem(projectId, "SHA3-1", "Task 3");

            // Too short (63 chars)
            String shortSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85";
            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/sha3-short", "test.pdf", "application/pdf", 100L, shortSha, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // Non-hex characters ('g')
            String nonHexSha = "g3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/sha3-nonhex", "test.pdf", "application/pdf", 100L, nonHexSha, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 3. Client Visibility Guard (visibility <> 'CLIENT_SHARED' OR scan_status = 'CLEAN')
    // =========================================================================
    @Nested
    @DisplayName("3. Client Visibility Guard")
    class ClientVisibilityGuardTests {

        @Test
        @DisplayName("CLIENT_SHARED visibility is rejected when scan_status is PENDING")
        void testClientSharedRejectedWhenScanPending() {
            UUID pmUser = createUser("pm-vis1@example.test", "PM VIS1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-vis1", "Workspace VIS1");
            UUID projectId = createProject(workspaceId, "VIS1", "Project VIS1", pmUser);
            UUID task = createWorkItem(projectId, "VIS1-1", "Task 1");

            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/vis1-pending", "contract.pdf", "application/pdf", 2048L, VALID_SHA256, "CLIENT_SHARED", "PENDING"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("CLIENT_SHARED visibility is rejected when scan_status is REJECTED")
        void testClientSharedRejectedWhenScanRejected() {
            UUID pmUser = createUser("pm-vis2@example.test", "PM VIS2", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-vis2", "Workspace VIS2");
            UUID projectId = createProject(workspaceId, "VIS2", "Project VIS2", pmUser);
            UUID task = createWorkItem(projectId, "VIS2-1", "Task 2");

            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/vis2-malware", "virus.exe", "application/octet-stream", 4096L, VALID_SHA256, "CLIENT_SHARED", "REJECTED"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("CLIENT_SHARED visibility is allowed when scan_status is CLEAN")
        void testClientSharedAllowedWhenScanClean() {
            UUID pmUser = createUser("pm-vis3@example.test", "PM VIS3", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-vis3", "Workspace VIS3");
            UUID projectId = createProject(workspaceId, "VIS3", "Project VIS3", pmUser);
            UUID task = createWorkItem(projectId, "VIS3-1", "Task 3");

            UUID att = insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/vis3-clean", "report.pdf", "application/pdf", 1024L, VALID_SHA256, "CLIENT_SHARED", "CLEAN");
            assertThat(att).isNotNull();
        }

        @Test
        @DisplayName("INTERNAL visibility is allowed even when scan_status is PENDING or REJECTED")
        void testInternalVisibilityAllowedWithAnyScanStatus() {
            UUID pmUser = createUser("pm-vis4@example.test", "PM VIS4", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-vis4", "Workspace VIS4");
            UUID projectId = createProject(workspaceId, "VIS4", "Project VIS4", pmUser);
            UUID task = createWorkItem(projectId, "VIS4-1", "Task 4");

            // INTERNAL with PENDING
            UUID attPending = insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/vis4-pend", "doc1.pdf", "application/pdf", 512L, VALID_SHA256, "INTERNAL", "PENDING");
            assertThat(attPending).isNotNull();

            // INTERNAL with REJECTED
            UUID attRejected = insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/vis4-rej", "doc2.pdf", "application/pdf", 512L, VALID_SHA256, "INTERNAL", "REJECTED");
            assertThat(attRejected).isNotNull();
        }

        @Test
        @DisplayName("Updating attachment: mutating scan_status to REJECTED while CLIENT_SHARED violates constraint")
        void testUpdateScanStatusToRejectedWhileClientSharedFails() {
            UUID pmUser = createUser("pm-vis5@example.test", "PM VIS5", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-vis5", "Workspace VIS5");
            UUID projectId = createProject(workspaceId, "VIS5", "Project VIS5", pmUser);
            UUID task = createWorkItem(projectId, "VIS5-1", "Task 5");

            // Create CLEAN and CLIENT_SHARED
            UUID att = insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", "keys/vis5-update", "spec.pdf", "application/pdf", 1024L, VALID_SHA256, "CLIENT_SHARED", "CLEAN");

            // Antivirus post-scan rescans and marks as REJECTED while still CLIENT_SHARED -> check constraint violation
            assertThatThrownBy(() -> jdbc.update("UPDATE attachments SET scan_status = 'REJECTED' WHERE id = ?", att))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 4. Trigger ds_attachment_scope() & Scope Matching
    // =========================================================================
    @Nested
    @DisplayName("4. Trigger ds_attachment_scope() Scope Alignment")
    class AttachmentScopeTests {

        @Test
        @DisplayName("Attachment for chat_message must share exact workspace_id and project_id with message")
        void testChatMessageAttachmentScopeMismatch() {
            UUID pmUser = createUser("pm-att1@example.test", "PM ATT1", "ACTIVE", "USER");
            UUID workspaceId1 = createWorkspace(pmUser, "ws-att1a", "Workspace ATT1A");
            UUID workspaceId2 = createWorkspace(pmUser, "ws-att1b", "Workspace ATT1B");
            UUID projectId1 = createProject(workspaceId1, "ATT1A", "Project ATT1A", pmUser);
            UUID projectId2 = createProject(workspaceId2, "ATT1B", "Project ATT1B", pmUser);

            UUID channel = createChatChannel(workspaceId1, projectId1, "PROJECT", "dev", pmUser);
            UUID messageId = createChatMessage(workspaceId1, projectId1, channel, pmUser);

            // Valid attachment sharing same workspaceId1 and projectId1 succeeds
            UUID validAtt = insertAttachment(workspaceId1, projectId1, null, messageId, null, null, pmUser,
                    "CHAT_FILE", "keys/att1-valid", "code.zip", "application/zip", 1024L, VALID_SHA256, "INTERNAL", "CLEAN");
            assertThat(validAtt).isNotNull();

            // Mismatch: Attachment has workspaceId1 but projectId2 -> fails ds_attachment_scope() or ds_project_write_lock
            assertThatThrownBy(() -> insertAttachment(workspaceId1, projectId2, null, messageId, null, null, pmUser,
                    "CHAT_FILE", "keys/att1-mismatch", "other.zip", "application/zip", 1024L, VALID_SHA256, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 5. Parent Reference & Kind Consistency Constraints
    // =========================================================================
    @Nested
    @DisplayName("5. Parent Entity Check Constraints")
    class ParentReferenceTests {

        @Test
        @DisplayName("num_nonnulls: attachment must have exactly one parent entity (not zero, not two)")
        void testExactlyOneParentRequired() {
            UUID pmUser = createUser("pm-par1@example.test", "PM PAR1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-par1", "Workspace PAR1");
            UUID projectId = createProject(workspaceId, "PAR1", "Project PAR1", pmUser);
            UUID task1 = createWorkItem(projectId, "PAR1-1", "Task 1");
            UUID channel = createChatChannel(workspaceId, projectId, "PROJECT", "general", pmUser);
            UUID message1 = createChatMessage(workspaceId, projectId, channel, pmUser);

            // Zero parents -> fails
            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, null, null, null, null, pmUser,
                    "TASK_FILE", "keys/par1-zero", "file.pdf", "application/pdf", 1024L, VALID_SHA256, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // Two parents (task1 AND message1) -> fails
            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, task1, message1, null, null, pmUser,
                    "TASK_FILE", "keys/par1-two", "file.pdf", "application/pdf", 1024L, VALID_SHA256, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Kind mismatch: TASK_FILE with chat_message_id is rejected")
        void testKindMismatchRejected() {
            UUID pmUser = createUser("pm-par2@example.test", "PM PAR2", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-par2", "Workspace PAR2");
            UUID projectId = createProject(workspaceId, "PAR2", "Project PAR2", pmUser);
            UUID channel = createChatChannel(workspaceId, projectId, "PROJECT", "general", pmUser);
            UUID message = createChatMessage(workspaceId, projectId, channel, pmUser);

            // kind is 'TASK_FILE' but parent provided is chat_message_id -> violates check constraint
            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, null, message, null, null, pmUser,
                    "TASK_FILE", "keys/par2-mismatch", "file.pdf", "application/pdf", 1024L, VALID_SHA256, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("storage_key unique constraint: duplicate storage_key is rejected")
        void testUniqueStorageKey() {
            UUID pmUser = createUser("pm-par3@example.test", "PM PAR3", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-par3", "Workspace PAR3");
            UUID projectId = createProject(workspaceId, "PAR3", "Project PAR3", pmUser);
            UUID task = createWorkItem(projectId, "PAR3-1", "Task 1");

            String key = "workspaces/" + workspaceId + "/projects/" + projectId + "/unique-file.pdf";

            UUID att1 = insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", key, "file.pdf", "application/pdf", 1024L, VALID_SHA256, "INTERNAL", "CLEAN");
            assertThat(att1).isNotNull();

            // Duplicate storage_key fails
            assertThatThrownBy(() -> insertAttachment(workspaceId, projectId, task, null, null, null, pmUser,
                    "TASK_FILE", key, "another.pdf", "application/pdf", 2048L, VALID_SHA256, "INTERNAL", "CLEAN"))
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }
}
