package com.project.devsphere.storage.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.storage.api.dto.DownloadAttachmentResponse;
import com.project.devsphere.storage.api.dto.RegisteredReportExport;
import com.project.devsphere.storage.api.dto.UploadRequestCommand;
import com.project.devsphere.storage.api.dto.UploadRequestResponse;
import com.project.devsphere.storage.domain.Attachment;
import com.project.devsphere.storage.infrastructure.S3StorageProperties;
import com.project.devsphere.storage.infrastructure.StorageKeyValidator;
import com.project.devsphere.storage.infrastructure.persistence.AttachmentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class AttachmentService {

    private static final long MAX_FILE_SIZE = 20971520L; // 20MB
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final long PRESIGNED_EXPIRY_SECONDS = 300L; // 5 minutes, the storage adapters' maximum

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "application/pdf",
            "text/plain",
            "application/json",
            "application/zip",
            "text/markdown",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final AttachmentRepository attachmentRepository;
    private final AccessPolicy accessPolicy;
    private final StorageProvider storageProvider;
    private final JdbcTemplate jdbcTemplate;
    private final StorageObjectClient storageObjectClient;
    private final S3StorageProperties s3StorageProperties;
    private final TransactionTemplate readTxTemplate;
    private final TransactionTemplate writeTxTemplate;

    public AttachmentService(
            AttachmentRepository attachmentRepository,
            AccessPolicy accessPolicy,
            StorageProvider storageProvider,
            JdbcTemplate jdbcTemplate) {
        this(attachmentRepository, accessPolicy, storageProvider, jdbcTemplate,
                (StorageObjectClient) null, null, null);
    }

    public AttachmentService(
            AttachmentRepository attachmentRepository,
            AccessPolicy accessPolicy,
            StorageProvider storageProvider,
            JdbcTemplate jdbcTemplate,
            StorageObjectClient storageObjectClient,
            S3StorageProperties s3StorageProperties,
            TransactionTemplate transactionTemplate) {
        this.attachmentRepository = Objects.requireNonNull(attachmentRepository, "attachmentRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.storageObjectClient = storageObjectClient;
        this.s3StorageProperties = s3StorageProperties;
        if (transactionTemplate != null && transactionTemplate.getTransactionManager() != null) {
            TransactionTemplate readOnly = new TransactionTemplate(transactionTemplate.getTransactionManager());
            readOnly.setReadOnly(true);
            this.readTxTemplate = readOnly;
            this.writeTxTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
        } else {
            this.readTxTemplate = transactionTemplate;
            this.writeTxTemplate = transactionTemplate;
        }
    }

    @Autowired
    public AttachmentService(
            AttachmentRepository attachmentRepository,
            AccessPolicy accessPolicy,
            StorageProvider storageProvider,
            JdbcTemplate jdbcTemplate,
            ObjectProvider<StorageObjectClient> storageObjectClientProvider,
            ObjectProvider<S3StorageProperties> s3StoragePropertiesProvider,
            ObjectProvider<TransactionTemplate> transactionTemplateProvider) {
        this(
                attachmentRepository,
                accessPolicy,
                storageProvider,
                jdbcTemplate,
                storageObjectClientProvider != null ? storageObjectClientProvider.getIfAvailable() : null,
                s3StoragePropertiesProvider != null ? s3StoragePropertiesProvider.getIfAvailable() : null,
                transactionTemplateProvider != null ? transactionTemplateProvider.getIfAvailable() : null
        );
    }

    @Transactional
    public UploadRequestResponse requestUpload(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UploadRequestCommand command) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        if (command == null) {
            throw StorageException.validationFailed("Upload request command must not be null");
        }

        // Validate file size: 0 <= file_size <= 20971520
        if (command.fileSize() == null || command.fileSize() < 0 || command.fileSize() > MAX_FILE_SIZE) {
            throw StorageException.invalidFileSize("File size must be between 0 and 20971520 bytes (20MB)");
        }

        // Validate MIME type against whitelist
        if (command.contentType() == null || !ALLOWED_MIME_TYPES.contains(command.contentType().toLowerCase())) {
            throw StorageException.unsupportedMediaType("Unsupported content type: " + command.contentType());
        }

        // Validate SHA-256 regex: ^[0-9a-f]{64}$
        if (command.sha256() == null || !SHA256_PATTERN.matcher(command.sha256()).matches()) {
            throw StorageException.invalidChecksum("SHA-256 checksum must match ^[0-9a-f]{64}$");
        }

        // Validate kind and parentId
        if (command.kind() == null) {
            throw StorageException.validationFailed("Attachment kind must not be null");
        }
        if (command.parentId() == null) {
            throw StorageException.invalidParentTarget("Parent ID is required for attachment");
        }

        UUID workItemId = null;
        UUID chatMessageId = null;
        UUID reportId = null;
        UUID ciRunId = null;

        switch (command.kind()) {
            case TASK_FILE -> {
                workItemId = command.parentId();
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM work_items WHERE id = ? AND project_id = ?",
                        Integer.class, workItemId, projectId
                );
                if (count == null || count == 0) {
                    throw StorageException.invalidParentTarget("Work item not found in this project: " + workItemId);
                }
            }
            case CHAT_FILE -> {
                chatMessageId = command.parentId();
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM chat_messages WHERE id = ? AND workspace_id = ? AND project_id = ?",
                        Integer.class, chatMessageId, workspaceId, projectId
                );
                if (count == null || count == 0) {
                    throw StorageException.invalidParentTarget("Chat message not found in this project scope: " + chatMessageId);
                }
            }
            case REPORT_EXPORT -> {
                reportId = command.parentId();
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM reports WHERE id = ? AND project_id = ?",
                        Integer.class, reportId, projectId
                );
                if (count == null || count == 0) {
                    throw StorageException.invalidParentTarget("Report not found in this project: " + reportId);
                }
            }
            case CI_ARTIFACT -> {
                ciRunId = command.parentId();
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM ci_runs WHERE id = ? AND project_id = ?",
                        Integer.class, ciRunId, projectId
                );
                if (count == null || count == 0) {
                    throw StorageException.invalidParentTarget("CI run not found in this project: " + ciRunId);
                }
            }
        }

        // Visibility constraint: CHECK (visibility <> 'CLIENT_SHARED' OR scan_status = 'CLEAN')
        // While scan_status is PENDING, visibility cannot be CLIENT_SHARED.
        if (command.visibility() == Attachment.Visibility.CLIENT_SHARED) {
            throw StorageException.validationFailed("Attachment cannot have CLIENT_SHARED visibility while scan status is PENDING");
        }

        String sanitizedFilename = command.filename() != null
                ? command.filename().replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("\\.{2,}", ".")
                : "attachment";
        String storageKey = String.format("workspaces/%s/projects/%s/attachments/%s/%s",
                workspaceId, projectId, UUID.randomUUID(), sanitizedFilename);

        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspaceId);
        attachment.setProjectId(projectId);
        attachment.setWorkItemId(workItemId);
        attachment.setChatMessageId(chatMessageId);
        attachment.setReportId(reportId);
        attachment.setCiRunId(ciRunId);
        attachment.setUploadedBy(principal.userId());
        attachment.setKind(command.kind());
        attachment.setStorageKey(storageKey);
        attachment.setFilename(command.filename());
        attachment.setContentType(command.contentType().toLowerCase());
        attachment.setFileSize(command.fileSize());
        attachment.setSha256(command.sha256());
        attachment.setVisibility(Attachment.Visibility.INTERNAL);
        attachment.setScanStatus(Attachment.ScanStatus.PENDING);
        attachment.setVersion(0L);

        Attachment saved = attachmentRepository.save(attachment);

        String uploadUrl = storageProvider.generatePresignedUploadUrl(storageKey, command.contentType(), PRESIGNED_EXPIRY_SECONDS);

        return new UploadRequestResponse(
                saved.getId(),
                storageKey,
                uploadUrl,
                PRESIGNED_EXPIRY_SECONDS,
                saved.getScanStatus(),
                saved.getVisibility()
        );
    }

    /**
     * Confirms an uploaded attachment. When {@code mode=aws} and {@code verifyOnConfirm=true},
     * object existence is checked via HTTP {@code HEAD} strictly outside any DB transaction.
     */
    public Attachment confirmUpload(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID attachmentId,
            Attachment.Visibility targetVisibility) {

        String verificationUrl = inReadTx(() -> {
            accessPolicy.requireProjectMember(principal, workspaceId, projectId);

            Attachment attachment = attachmentRepository.findByIdAndWorkspaceIdAndProjectId(attachmentId, workspaceId, projectId)
                    .orElseThrow(() -> StorageException.attachmentNotFound("Attachment not found: " + attachmentId));

            if (attachment.getDeletedAt() != null) {
                throw StorageException.attachmentNotFound("Attachment has been deleted: " + attachmentId);
            }

            if (shouldVerifyRemoteObject()) {
                return storageProvider.generatePresignedDownloadUrl(attachment.getStorageKey(), PRESIGNED_EXPIRY_SECONDS);
            }
            return null;
        });

        if (verificationUrl != null) {
            boolean exists = storageObjectClient.verifyObjectExists(verificationUrl);
            if (!exists) {
                throw StorageException.storageObjectMissing(
                        "Uploaded object not found in storage for attachment: " + attachmentId);
            }
        }

        return inWriteTx(() -> {
            accessPolicy.requireProjectMember(principal, workspaceId, projectId);

            Attachment attachment = attachmentRepository.findByIdAndWorkspaceIdAndProjectId(attachmentId, workspaceId, projectId)
                    .orElseThrow(() -> StorageException.attachmentNotFound("Attachment not found: " + attachmentId));

            if (attachment.getDeletedAt() != null) {
                throw StorageException.attachmentNotFound("Attachment has been deleted: " + attachmentId);
            }

            // Transition scan_status to CLEAN
            attachment.setScanStatus(Attachment.ScanStatus.CLEAN);

            // Update visibility if requested
            if (targetVisibility != null) {
                attachment.setVisibility(targetVisibility);
            }

            return attachmentRepository.save(attachment);
        });
    }

    public Attachment confirmUpload(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID attachmentId) {
        return confirmUpload(principal, workspaceId, projectId, attachmentId, null);
    }

    /**
     * Registers a server-generated report export as a {@code CLEAN} {@code REPORT_EXPORT} attachment
     * and returns the saved entity along with a presigned download URL.
     */
    @Transactional
    public RegisteredReportExport registerUploadedReportExport(
            UUID workspaceId,
            UUID projectId,
            UUID reportId,
            UUID uploadedBy,
            String storageKey,
            String filename,
            String contentType,
            long fileSize,
            String sha256,
            Attachment.Visibility visibility) {

        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(reportId, "reportId must not be null");
        StorageKeyValidator.requireValidKey(storageKey);

        if (filename == null || filename.isBlank()) {
            throw StorageException.validationFailed("Filename must not be blank");
        }
        String normalizedContentType = contentType != null ? contentType.strip().toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_MIME_TYPES.contains(normalizedContentType)) {
            throw StorageException.unsupportedMediaType("Unsupported content type: " + contentType);
        }
        if (fileSize < 0 || fileSize > MAX_FILE_SIZE) {
            throw StorageException.invalidFileSize("File size must be between 0 and 20971520 bytes (20MB)");
        }
        if (sha256 == null || !SHA256_PATTERN.matcher(sha256).matches()) {
            throw StorageException.invalidChecksum("SHA-256 checksum must match ^[0-9a-f]{64}$");
        }

        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspaceId);
        attachment.setProjectId(projectId);
        attachment.setReportId(reportId);
        attachment.setUploadedBy(uploadedBy);
        attachment.setKind(Attachment.Kind.REPORT_EXPORT);
        attachment.setStorageKey(storageKey);
        attachment.setFilename(filename);
        attachment.setContentType(normalizedContentType);
        attachment.setFileSize(fileSize);
        attachment.setSha256(sha256);
        attachment.setVisibility(visibility != null ? visibility : Attachment.Visibility.INTERNAL);
        attachment.setScanStatus(Attachment.ScanStatus.CLEAN);
        attachment.setVersion(0L);

        Attachment saved = attachmentRepository.save(attachment);
        String downloadUrl = storageProvider.generatePresignedDownloadUrl(storageKey, PRESIGNED_EXPIRY_SECONDS);
        return new RegisteredReportExport(saved, downloadUrl, PRESIGNED_EXPIRY_SECONDS);
    }

    @Transactional(readOnly = true)
    public DownloadAttachmentResponse requestDownload(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID attachmentId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        Attachment attachment = attachmentRepository.findByIdAndWorkspaceIdAndProjectId(attachmentId, workspaceId, projectId)
                .orElseThrow(() -> StorageException.attachmentNotFound("Attachment not found: " + attachmentId));

        if (attachment.getDeletedAt() != null) {
            throw StorageException.attachmentNotFound("Attachment has been deleted: " + attachmentId);
        }

        // Role & Client barrier checks
        Boolean isClient = jdbcTemplate.queryForObject("SELECT ds_has_project_role(?, ?, 'CLIENT')", Boolean.class, projectId, principal.userId());
        Boolean isPm = jdbcTemplate.queryForObject("SELECT ds_has_project_role(?, ?, 'PM')", Boolean.class, projectId, principal.userId());
        Boolean isDev = jdbcTemplate.queryForObject("SELECT ds_has_project_role(?, ?, 'DEVELOPER')", Boolean.class, projectId, principal.userId());
        Boolean isViewer = jdbcTemplate.queryForObject("SELECT ds_has_project_role(?, ?, 'VIEWER')", Boolean.class, projectId, principal.userId());

        boolean isClientOnly = Boolean.TRUE.equals(isClient) &&
                !Boolean.TRUE.equals(isPm) &&
                !Boolean.TRUE.equals(isDev) &&
                !Boolean.TRUE.equals(isViewer);

        if (isClientOnly) {
            if (attachment.getVisibility() == Attachment.Visibility.INTERNAL) {
                throw StorageException.accessDenied("CLIENT role cannot access INTERNAL attachments");
            }
            if (attachment.getVisibility() == Attachment.Visibility.CLIENT_SHARED &&
                    attachment.getScanStatus() != Attachment.ScanStatus.CLEAN) {
                throw StorageException.attachmentNotClean("Attachment security scan is not CLEAN");
            }
        }

        if (attachment.getChatMessageId() != null) {
            Boolean isMember = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM chat_messages m " +
                            "JOIN chat_channel_members cm ON cm.channel_id = m.channel_id " +
                            "WHERE m.id = ? AND cm.user_id = ?)",
                    Boolean.class,
                    attachment.getChatMessageId(),
                    principal.userId()
            );
            if (!Boolean.TRUE.equals(isMember)) {
                throw StorageException.accessDenied("User is not a member of the chat channel for this attachment");
            }
        }

        String downloadUrl = storageProvider.generatePresignedDownloadUrl(attachment.getStorageKey(), PRESIGNED_EXPIRY_SECONDS);

        return new DownloadAttachmentResponse(
                attachment.getId(),
                downloadUrl,
                PRESIGNED_EXPIRY_SECONDS
        );
    }

    private boolean shouldVerifyRemoteObject() {
        return "aws".equalsIgnoreCase(storageProvider.mode())
                && s3StorageProperties != null
                && s3StorageProperties.isVerifyOnConfirm()
                && storageObjectClient != null;
    }

    private <T> T inReadTx(Supplier<T> action) {
        if (readTxTemplate != null) {
            return readTxTemplate.execute(status -> action.get());
        }
        return action.get();
    }

    private <T> T inWriteTx(Supplier<T> action) {
        if (writeTxTemplate != null) {
            return writeTxTemplate.execute(status -> action.get());
        }
        return action.get();
    }
}
