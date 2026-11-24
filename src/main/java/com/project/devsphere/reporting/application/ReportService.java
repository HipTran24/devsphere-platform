package com.project.devsphere.reporting.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.reporting.api.dto.CreateReportRequest;
import com.project.devsphere.reporting.api.dto.ReportExportAttachmentResponse;
import com.project.devsphere.reporting.api.dto.ReportResponse;
import com.project.devsphere.reporting.domain.Report;
import com.project.devsphere.reporting.infrastructure.persistence.ReportRepository;
import com.project.devsphere.storage.api.dto.RegisteredReportExport;
import com.project.devsphere.storage.application.AttachmentService;
import com.project.devsphere.storage.application.StorageException;
import com.project.devsphere.storage.application.StorageObjectClient;
import com.project.devsphere.storage.application.StorageProvider;
import com.project.devsphere.storage.domain.Attachment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

@Service
public class ReportService {

    private static final long MAX_FILE_SIZE = 20971520L;
    private static final long PRESIGNED_EXPIRY_SECONDS = 300L;
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final ReportRepository reportRepository;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AttachmentService attachmentService;
    private final StorageProvider storageProvider;
    private final StorageObjectClient storageObjectClient;
    private final TransactionTemplate readTxTemplate;
    private final TransactionTemplate writeTxTemplate;

    public ReportService(
            ReportRepository reportRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        this(reportRepository, accessPolicy, jdbcTemplate, null, (AttachmentService) null, null, null, null);
    }

    public ReportService(
            ReportRepository reportRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AttachmentService attachmentService,
            StorageProvider storageProvider,
            StorageObjectClient storageObjectClient,
            TransactionTemplate transactionTemplate) {
        this.reportRepository = Objects.requireNonNull(reportRepository, "reportRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.attachmentService = attachmentService;
        this.storageProvider = storageProvider;
        this.storageObjectClient = storageObjectClient;
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
    public ReportService(
            ReportRepository reportRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate,
            @Autowired(required = false) ObjectMapper objectMapper,
            ObjectProvider<AttachmentService> attachmentServiceProvider,
            ObjectProvider<StorageProvider> storageProviderProvider,
            ObjectProvider<StorageObjectClient> storageObjectClientProvider,
            ObjectProvider<TransactionTemplate> transactionTemplateProvider) {
        this(
                reportRepository,
                accessPolicy,
                jdbcTemplate,
                objectMapper,
                attachmentServiceProvider != null ? attachmentServiceProvider.getIfAvailable() : null,
                storageProviderProvider != null ? storageProviderProvider.getIfAvailable() : null,
                storageObjectClientProvider != null ? storageObjectClientProvider.getIfAvailable() : null,
                transactionTemplateProvider != null ? transactionTemplateProvider.getIfAvailable() : null
        );
    }

    @Transactional
    public ReportResponse createReport(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            CreateReportRequest request) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER));

        if (request == null || request.type() == null || request.type().isBlank()) {
            throw ReportException.invalidInput("type must not be blank");
        }

        Report.Type type;
        try {
            type = Report.Type.valueOf(request.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ReportException.invalidInput("Invalid report type: " + request.type() + ". Allowed: " + Arrays.toString(Report.Type.values()));
        }

        if (request.periodStart() == null || request.periodEnd() == null) {
            throw ReportException.invalidPeriod("periodStart and periodEnd must not be null");
        }

        if (request.periodEnd().isBefore(request.periodStart())) {
            throw ReportException.invalidPeriod("periodEnd cannot be before periodStart");
        }

        if (request.aiJobId() != null) {
            Integer validAiJobs = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ai_jobs WHERE id = ? AND project_id = ? AND status = 'SUCCESS' AND validation_status = 'VALID'",
                    Integer.class, request.aiJobId(), projectId
            );
            if (validAiJobs == null || validAiJobs == 0) {
                throw ReportException.aiJobNotValid("AI report requires validated output from ai_jobs with status SUCCESS and validation_status VALID");
            }
        }

        Report report = new Report();
        report.setProjectId(projectId);
        report.setType(type);
        report.setPeriodStart(request.periodStart());
        report.setPeriodEnd(request.periodEnd());
        report.setStatus(Report.Status.DRAFT);
        report.setContent(request.content() != null ? new LinkedHashMap<>(request.content()) : new LinkedHashMap<>(Map.of("schemaVersion", 1)));
        report.setVisibleToClient(false);
        report.setMilestoneId(request.milestoneId());
        report.setGeneratedBy(principal.userId());
        report.setAiJobId(request.aiJobId());

        Report saved = reportRepository.save(report);
        return ReportResponse.from(saved);
    }

    @Transactional
    public ReportResponse approveReport(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID reportId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        Report report = reportRepository.findByIdAndProjectId(reportId, projectId)
                .orElseThrow(() -> ReportException.notFound("Report not found: " + reportId));

        if (report.getAiJobId() != null) {
            Integer validAiJobs = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ai_jobs WHERE id = ? AND project_id = ? AND status = 'SUCCESS' AND validation_status = 'VALID'",
                    Integer.class, report.getAiJobId(), projectId
            );
            if (validAiJobs == null || validAiJobs == 0) {
                throw ReportException.aiJobNotValid("AI report requires validated output from ai_jobs with status SUCCESS and validation_status VALID");
            }
        }

        report.setStatus(Report.Status.APPROVED);
        report.setApprovedBy(principal.userId());

        Report saved = reportRepository.save(report);
        return ReportResponse.from(saved);
    }

    @Transactional
    public ReportResponse publishReport(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID reportId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        Report report = reportRepository.findByIdAndProjectId(reportId, projectId)
                .orElseThrow(() -> ReportException.notFound("Report not found: " + reportId));

        if (report.getAiJobId() != null) {
            Integer validAiJobs = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ai_jobs WHERE id = ? AND project_id = ? AND status = 'SUCCESS' AND validation_status = 'VALID'",
                    Integer.class, report.getAiJobId(), projectId
            );
            if (validAiJobs == null || validAiJobs == 0) {
                throw ReportException.aiJobNotValid("AI report requires validated output from ai_jobs with status SUCCESS and validation_status VALID");
            }
        }

        report.setStatus(Report.Status.PUBLISHED);
        report.setApprovedBy(principal.userId());
        report.setPublishedAt(Instant.now());
        report.setVisibleToClient(true);

        Report saved = reportRepository.save(report);
        return ReportResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ReportResponse> getReports(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            String typeFilter) {

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        boolean isClientOnly = scope.hasRole(ProjectRole.CLIENT)
                && !scope.hasRole(ProjectRole.PM)
                && !scope.hasRole(ProjectRole.DEVELOPER);

        if (isClientOnly) {
            List<ReportResponse> published = jdbcTemplate.query("""
                    SELECT id, project_id, type, period_start, period_end, status, content, visible_to_client,
                           milestone_id, generated_by, ai_job_id, approved_by, published_at, created_at, updated_at, version
                    FROM reports
                    WHERE project_id = ? AND visible_to_client = true AND status = 'PUBLISHED'
                    ORDER BY period_end DESC
                    """,
                    (rs, rowNum) -> new ReportResponse(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("project_id")),
                            rs.getString("type"),
                            rs.getDate("period_start").toLocalDate(),
                            rs.getDate("period_end").toLocalDate(),
                            rs.getString("status"),
                            parseContentJson(rs.getString("content")),
                            rs.getBoolean("visible_to_client"),
                            rs.getString("milestone_id") != null ? UUID.fromString(rs.getString("milestone_id")) : null,
                            rs.getString("generated_by") != null ? UUID.fromString(rs.getString("generated_by")) : null,
                            rs.getString("ai_job_id") != null ? UUID.fromString(rs.getString("ai_job_id")) : null,
                            rs.getString("approved_by") != null ? UUID.fromString(rs.getString("approved_by")) : null,
                            rs.getTimestamp("published_at") != null ? rs.getTimestamp("published_at").toInstant() : null,
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getLong("version")
                    ),
                    projectId
            );
            return published.stream()
                    .filter(r -> typeFilter == null || typeFilter.isBlank()
                            || (r.type() != null && r.type().equalsIgnoreCase(typeFilter.trim())))
                    .toList();
        }

        return reportRepository.findAllByProjectId(projectId, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .filter(r -> typeFilter == null || typeFilter.isBlank() || r.getType().name().equalsIgnoreCase(typeFilter.trim()))
                .map(ReportResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReportResponse getReport(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID reportId) {

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        Report report = reportRepository.findByIdAndProjectId(reportId, projectId)
                .orElseThrow(() -> ReportException.notFound("Report not found: " + reportId));

        boolean isClientOnly = scope.hasRole(ProjectRole.CLIENT)
                && !scope.hasRole(ProjectRole.PM)
                && !scope.hasRole(ProjectRole.DEVELOPER);

        if (isClientOnly && (!Boolean.TRUE.equals(report.getVisibleToClient()) || report.getStatus() != Report.Status.PUBLISHED)) {
            throw ReportException.accessDenied("Report is not published or visible to client: " + reportId);
        }

        return ReportResponse.from(report);
    }

    @Transactional(readOnly = true)
    public byte[] exportReport(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID reportId,
            String format) {

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        Report report = reportRepository.findByIdAndProjectId(reportId, projectId)
                .orElseThrow(() -> ReportException.notFound("Report not found: " + reportId));

        boolean isClientOnly = scope.hasRole(ProjectRole.CLIENT)
                && !scope.hasRole(ProjectRole.PM)
                && !scope.hasRole(ProjectRole.DEVELOPER);

        if (isClientOnly && (!Boolean.TRUE.equals(report.getVisibleToClient()) || report.getStatus() != Report.Status.PUBLISHED)) {
            throw ReportException.accessDenied("Report is not published or visible to client: " + reportId);
        }

        String projectName = jdbcTemplate.queryForObject(
                "SELECT name FROM projects WHERE id = ?",
                String.class, projectId
        );

        String normFormat = format != null ? format.trim().toUpperCase(Locale.ROOT) : "PDF";
        if ("PDF".equals(normFormat)) {
            return ReportExportGenerator.generatePdf(report, projectName);
        } else if ("XLSX".equals(normFormat)) {
            return ReportExportGenerator.generateXlsx(report, projectName);
        } else {
            throw ReportException.invalidInput("Unsupported export format: " + format + ". Allowed: PDF, XLSX");
        }
    }

    /**
     * Generates a report export, uploads it to S3 outside any DB transaction when {@code mode=aws},
     * and registers a {@code REPORT_EXPORT} attachment returning a presigned download URL.
     */
    public ReportExportAttachmentResponse exportReportAsAttachment(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID reportId,
            String format) {

        PreparedExport prepared = inReadTx(() -> {
            ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);
            Report report = reportRepository.findByIdAndProjectId(reportId, projectId)
                    .orElseThrow(() -> ReportException.notFound("Report not found: " + reportId));

            boolean isClientOnly = scope.hasRole(ProjectRole.CLIENT)
                    && !scope.hasRole(ProjectRole.PM)
                    && !scope.hasRole(ProjectRole.DEVELOPER);

            if (isClientOnly && (!Boolean.TRUE.equals(report.getVisibleToClient()) || report.getStatus() != Report.Status.PUBLISHED)) {
                throw ReportException.accessDenied("Report is not published or visible to client: " + reportId);
            }

            String projectName = jdbcTemplate.queryForObject(
                    "SELECT name FROM projects WHERE id = ?",
                    String.class, projectId
            );

            String normFormat = format != null ? format.trim().toUpperCase(Locale.ROOT) : "PDF";
            byte[] fileBytes;
            String contentType;
            String extension;
            if ("PDF".equals(normFormat)) {
                fileBytes = ReportExportGenerator.generatePdf(report, projectName);
                contentType = PDF_CONTENT_TYPE;
                extension = "pdf";
            } else if ("XLSX".equals(normFormat)) {
                fileBytes = ReportExportGenerator.generateXlsx(report, projectName);
                contentType = XLSX_CONTENT_TYPE;
                extension = "xlsx";
            } else {
                throw ReportException.invalidInput("Unsupported export format: " + format + ". Allowed: PDF, XLSX");
            }

            if (fileBytes.length > MAX_FILE_SIZE) {
                throw StorageException.invalidFileSize("Generated export exceeds 20MB attachment limit");
            }

            String sha256 = sha256Hex(fileBytes);
            String filename = "report-" + reportId + "." + extension;
            Attachment.Visibility visibility = (report.getStatus() == Report.Status.PUBLISHED && Boolean.TRUE.equals(report.getVisibleToClient()))
                    ? Attachment.Visibility.CLIENT_SHARED
                    : Attachment.Visibility.INTERNAL;
            String storageKey = String.format("workspaces/%s/projects/%s/attachments/%s/%s",
                    workspaceId, projectId, UUID.randomUUID(), filename);

            String presignedPutUrl = null;
            if (storageProvider != null && "aws".equalsIgnoreCase(storageProvider.mode()) && storageObjectClient != null) {
                presignedPutUrl = storageProvider.generatePresignedUploadUrl(storageKey, contentType, PRESIGNED_EXPIRY_SECONDS);
            }

            return new PreparedExport(normFormat, filename, contentType, fileBytes, sha256, visibility, storageKey, presignedPutUrl);
        });

        if (prepared.presignedPutUrl() != null) {
            storageObjectClient.uploadObject(prepared.presignedPutUrl(), prepared.fileBytes(), prepared.contentType());
        }

        if (attachmentService == null) {
            throw new IllegalStateException("AttachmentService is required to register exported report attachments");
        }

        return inWriteTx(() -> {
            RegisteredReportExport registered = attachmentService.registerUploadedReportExport(
                    workspaceId,
                    projectId,
                    reportId,
                    principal.userId(),
                    prepared.storageKey(),
                    prepared.filename(),
                    prepared.contentType(),
                    prepared.fileBytes().length,
                    prepared.sha256(),
                    prepared.visibility()
            );
            return new ReportExportAttachmentResponse(
                    registered.attachment().getId(),
                    reportId,
                    prepared.format(),
                    prepared.filename(),
                    prepared.contentType(),
                    prepared.fileBytes().length,
                    prepared.sha256(),
                    registered.downloadUrl(),
                    registered.expiresInSeconds()
            );
        });
    }

    private Map<String, Object> parseContentJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(rawJson, MAP_TYPE_REF);
            return parsed != null ? parsed : Map.of();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse report content JSONB", ex);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
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

    private record PreparedExport(
            String format,
            String filename,
            String contentType,
            byte[] fileBytes,
            String sha256,
            Attachment.Visibility visibility,
            String storageKey,
            String presignedPutUrl
    ) {}
}
