package com.project.devsphere.storage.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.storage.api.dto.DownloadAttachmentResponse;
import com.project.devsphere.storage.api.dto.UploadRequestCommand;
import com.project.devsphere.storage.api.dto.UploadRequestResponse;
import com.project.devsphere.storage.domain.Attachment;
import com.project.devsphere.storage.infrastructure.persistence.AttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock private AttachmentRepository attachmentRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private StorageProvider storageProvider;
    @Mock private JdbcTemplate jdbcTemplate;

    private AttachmentService attachmentService;

    private UUID workspaceId;
    private UUID projectId;
    private UUID callerUserId;
    private SessionService.Principal principal;
    private static final String VALID_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @BeforeEach
    void setUp() {
        attachmentService = new AttachmentService(attachmentRepository, accessPolicy, storageProvider, jdbcTemplate);

        workspaceId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();
        principal = new SessionService.Principal(callerUserId, UUID.randomUUID(), "Alice", "USER");
    }

    @Test
    @DisplayName("requestUpload: rejects file size > 20MB")
    void requestUploadFileSizeOverLimit() {
        UploadRequestCommand command = new UploadRequestCommand(
                "big.zip", "application/zip", 20971521L, VALID_SHA,
                Attachment.Kind.TASK_FILE, UUID.randomUUID(), Attachment.Visibility.INTERNAL
        );

        assertThatThrownBy(() -> attachmentService.requestUpload(principal, workspaceId, projectId, command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("File size must be between 0 and 20971520 bytes");
    }

    @Test
    @DisplayName("requestUpload: rejects unsupported MIME type")
    void requestUploadUnsupportedMime() {
        UploadRequestCommand command = new UploadRequestCommand(
                "virus.exe", "application/x-msdownload", 1024L, VALID_SHA,
                Attachment.Kind.TASK_FILE, UUID.randomUUID(), Attachment.Visibility.INTERNAL
        );

        assertThatThrownBy(() -> attachmentService.requestUpload(principal, workspaceId, projectId, command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Unsupported content type");
    }

    @Test
    @DisplayName("requestUpload: rejects invalid SHA-256")
    void requestUploadInvalidSha() {
        UploadRequestCommand command = new UploadRequestCommand(
                "doc.pdf", "application/pdf", 1024L, "invalid-sha-hex",
                Attachment.Kind.TASK_FILE, UUID.randomUUID(), Attachment.Visibility.INTERNAL
        );

        assertThatThrownBy(() -> attachmentService.requestUpload(principal, workspaceId, projectId, command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("SHA-256 checksum must match");
    }

    @Test
    @DisplayName("requestUpload: rejects CLIENT_SHARED visibility while scan status is PENDING")
    void requestUploadClientSharedPendingRejected() {
        UploadRequestCommand command = new UploadRequestCommand(
                "doc.pdf", "application/pdf", 1024L, VALID_SHA,
                Attachment.Kind.TASK_FILE, UUID.randomUUID(), Attachment.Visibility.CLIENT_SHARED
        );

        when(jdbcTemplate.queryForObject(contains("work_items"), eq(Integer.class), any(), eq(projectId)))
                .thenReturn(1);

        assertThatThrownBy(() -> attachmentService.requestUpload(principal, workspaceId, projectId, command))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Attachment cannot have CLIENT_SHARED visibility while scan status is PENDING");
    }

    @Test
    @DisplayName("requestUpload: success generates storageKey and presigned upload URL")
    void requestUploadSuccess() {
        UUID workItemId = UUID.randomUUID();
        UploadRequestCommand command = new UploadRequestCommand(
                "spec.pdf", "application/pdf", 2048L, VALID_SHA,
                Attachment.Kind.TASK_FILE, workItemId, null
        );

        when(jdbcTemplate.queryForObject(contains("work_items"), eq(Integer.class), eq(workItemId), eq(projectId)))
                .thenReturn(1);

        Attachment saved = new Attachment();
        saved.setId(UUID.randomUUID());
        saved.setScanStatus(Attachment.ScanStatus.PENDING);
        saved.setVisibility(Attachment.Visibility.INTERNAL);
        when(attachmentRepository.save(any(Attachment.class))).thenReturn(saved);

        when(storageProvider.generatePresignedUploadUrl(anyString(), eq("application/pdf"), eq(900L)))
                .thenReturn("https://storage.devsphere.local/upload/spec.pdf");

        UploadRequestResponse response = attachmentService.requestUpload(principal, workspaceId, projectId, command);

        assertThat(response).isNotNull();
        assertThat(response.scanStatus()).isEqualTo(Attachment.ScanStatus.PENDING);
        assertThat(response.uploadUrl()).contains("https://storage.devsphere.local/upload");
    }

    @Test
    @DisplayName("confirmUpload: transitions scanStatus to CLEAN and can update visibility")
    void confirmUploadSuccess() {
        UUID attachmentId = UUID.randomUUID();
        Attachment attachment = new Attachment();
        attachment.setId(attachmentId);
        attachment.setScanStatus(Attachment.ScanStatus.PENDING);
        attachment.setVisibility(Attachment.Visibility.INTERNAL);

        when(attachmentRepository.findByIdAndWorkspaceIdAndProjectId(attachmentId, workspaceId, projectId))
                .thenReturn(Optional.of(attachment));
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(i -> i.getArgument(0));

        Attachment confirmed = attachmentService.confirmUpload(
                principal, workspaceId, projectId, attachmentId, Attachment.Visibility.CLIENT_SHARED
        );

        assertThat(confirmed.getScanStatus()).isEqualTo(Attachment.ScanStatus.CLEAN);
        assertThat(confirmed.getVisibility()).isEqualTo(Attachment.Visibility.CLIENT_SHARED);
    }

    @Test
    @DisplayName("requestDownload: CLIENT role rejected for INTERNAL visibility")
    void requestDownloadClientBlockedFromInternal() {
        UUID attachmentId = UUID.randomUUID();
        Attachment attachment = new Attachment();
        attachment.setId(attachmentId);
        attachment.setVisibility(Attachment.Visibility.INTERNAL);

        when(attachmentRepository.findByIdAndWorkspaceIdAndProjectId(attachmentId, workspaceId, projectId))
                .thenReturn(Optional.of(attachment));

        // Caller is CLIENT-only
        when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role(?, ?, 'CLIENT')"), eq(Boolean.class), eq(projectId), eq(callerUserId)))
                .thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role(?, ?, 'PM')"), eq(Boolean.class), eq(projectId), eq(callerUserId)))
                .thenReturn(false);
        when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role(?, ?, 'DEVELOPER')"), eq(Boolean.class), eq(projectId), eq(callerUserId)))
                .thenReturn(false);
        when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role(?, ?, 'VIEWER')"), eq(Boolean.class), eq(projectId), eq(callerUserId)))
                .thenReturn(false);

        assertThatThrownBy(() -> attachmentService.requestDownload(principal, workspaceId, projectId, attachmentId))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("CLIENT role cannot access INTERNAL attachments");
    }

    @Test
    @DisplayName("requestDownload: CLIENT allowed for CLEAN CLIENT_SHARED attachment")
    void requestDownloadClientAllowedForCleanClientShared() {
        UUID attachmentId = UUID.randomUUID();
        Attachment attachment = new Attachment();
        attachment.setId(attachmentId);
        attachment.setStorageKey("keys/clean.pdf");
        attachment.setVisibility(Attachment.Visibility.CLIENT_SHARED);
        attachment.setScanStatus(Attachment.ScanStatus.CLEAN);

        when(attachmentRepository.findByIdAndWorkspaceIdAndProjectId(attachmentId, workspaceId, projectId))
                .thenReturn(Optional.of(attachment));

        when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role(?, ?, 'CLIENT')"), eq(Boolean.class), eq(projectId), eq(callerUserId)))
                .thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role(?, ?, 'PM')"), eq(Boolean.class), eq(projectId), eq(callerUserId)))
                .thenReturn(false);
        when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role(?, ?, 'DEVELOPER')"), eq(Boolean.class), eq(projectId), eq(callerUserId)))
                .thenReturn(false);
        when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role(?, ?, 'VIEWER')"), eq(Boolean.class), eq(projectId), eq(callerUserId)))
                .thenReturn(false);

        when(storageProvider.generatePresignedDownloadUrl("keys/clean.pdf", 900L))
                .thenReturn("https://storage.devsphere.local/download/keys/clean.pdf");

        DownloadAttachmentResponse response = attachmentService.requestDownload(principal, workspaceId, projectId, attachmentId);
        assertThat(response).isNotNull();
        assertThat(response.downloadUrl()).isEqualTo("https://storage.devsphere.local/download/keys/clean.pdf");
    }
}
