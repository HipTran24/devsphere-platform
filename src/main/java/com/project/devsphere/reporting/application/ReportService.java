package com.project.devsphere.reporting.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.reporting.api.dto.CreateReportRequest;
import com.project.devsphere.reporting.api.dto.ReportResponse;
import com.project.devsphere.reporting.domain.Report;
import com.project.devsphere.reporting.infrastructure.persistence.ReportRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class ReportService {

    private final ReportRepository reportRepository;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;

    public ReportService(
            ReportRepository reportRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        this.reportRepository = Objects.requireNonNull(reportRepository, "reportRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

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
            return jdbcTemplate.query("""
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
                            Map.of("summary", "Client Published Report"),
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
}
