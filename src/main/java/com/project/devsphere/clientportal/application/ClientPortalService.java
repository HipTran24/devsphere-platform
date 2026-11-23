package com.project.devsphere.clientportal.application;

import com.project.devsphere.clientportal.api.dto.ClientProjectOverviewResponse;
import com.project.devsphere.clientportal.api.dto.MilestoneReviewReceiptResponse;
import com.project.devsphere.clientportal.api.dto.PublishedMilestoneResponse;
import com.project.devsphere.clientportal.api.dto.PublishedReportResponse;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.project.domain.Milestone;
import com.project.devsphere.project.infrastructure.persistence.MilestoneRepository;
import com.project.devsphere.reporting.domain.Report;
import com.project.devsphere.reporting.infrastructure.persistence.ReportRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class ClientPortalService {

    private final MilestoneRepository milestoneRepository;
    private final ReportRepository reportRepository;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;

    public ClientPortalService(
            MilestoneRepository milestoneRepository,
            ReportRepository reportRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        this.milestoneRepository = Objects.requireNonNull(milestoneRepository, "milestoneRepository must not be null");
        this.reportRepository = Objects.requireNonNull(reportRepository, "reportRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Transactional(readOnly = true)
    public ClientProjectOverviewResponse getClientProjectOverview(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        // Fetch high-level project information only (Zero Data Leak: no git, no PRs, no CI, no capacity)
        Map<String, Object> projectRow = jdbcTemplate.queryForMap("""
                SELECT name, project_key, description, objective, status, planned_start, committed_deadline
                FROM projects
                WHERE id = ? AND workspace_id = ?
                """, projectId, workspaceId);

        String projectName = (String) projectRow.get("name");
        String projectKey = (String) projectRow.get("project_key");
        String description = (String) projectRow.get("description");
        String objective = (String) projectRow.get("objective");
        String status = (String) projectRow.get("status");
        LocalDate plannedStart = projectRow.get("planned_start") instanceof Date d ? d.toLocalDate() : null;
        LocalDate committedDeadline = projectRow.get("committed_deadline") instanceof Date d ? d.toLocalDate() : null;

        // Fetch published milestones
        List<PublishedMilestoneResponse> publishedMilestones = jdbcTemplate.query("""
                SELECT id, project_id, code, name, description, due_date, forecast_date, status, acceptance_status,
                       published_to_client, accepted_by, accepted_at, version
                FROM milestones
                WHERE project_id = ? AND published_to_client = true
                ORDER BY due_date ASC
                """,
                (rs, rowNum) -> new PublishedMilestoneResponse(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("project_id")),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getDate("due_date") != null ? rs.getDate("due_date").toLocalDate() : null,
                        rs.getDate("forecast_date") != null ? rs.getDate("forecast_date").toLocalDate() : null,
                        rs.getString("status"),
                        rs.getString("acceptance_status"),
                        rs.getBoolean("published_to_client"),
                        rs.getString("accepted_by") != null ? UUID.fromString(rs.getString("accepted_by")) : null,
                        rs.getTimestamp("accepted_at") != null ? rs.getTimestamp("accepted_at").toInstant() : null,
                        rs.getLong("version")
                ),
                projectId
        );

        int acceptedMilestonesCount = (int) publishedMilestones.stream()
                .filter(m -> "ACCEPTED".equalsIgnoreCase(m.acceptanceStatus()))
                .count();

        // Fetch published reports visible to client
        List<PublishedReportResponse> publishedReports = jdbcTemplate.query("""
                SELECT id, project_id, type, period_start, period_end, status, published_at, content
                FROM reports
                WHERE project_id = ? AND visible_to_client = true AND status = 'PUBLISHED'
                ORDER BY period_end DESC
                """,
                (rs, rowNum) -> new PublishedReportResponse(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("project_id")),
                        rs.getString("type"),
                        rs.getDate("period_start") != null ? rs.getDate("period_start").toLocalDate() : null,
                        rs.getDate("period_end") != null ? rs.getDate("period_end").toLocalDate() : null,
                        rs.getString("status"),
                        rs.getTimestamp("published_at") != null ? rs.getTimestamp("published_at").toInstant() : null,
                        Map.of("summary", "Published Report")
                ),
                projectId
        );

        return new ClientProjectOverviewResponse(
                projectId,
                projectName,
                projectKey,
                description,
                objective,
                status,
                plannedStart,
                committedDeadline,
                publishedMilestones.size(),
                acceptedMilestonesCount,
                publishedReports.size(),
                publishedMilestones,
                publishedReports
        );
    }

    @Transactional(readOnly = true)
    public List<PublishedMilestoneResponse> listPublishedMilestones(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        return jdbcTemplate.query("""
                SELECT id, project_id, code, name, description, due_date, forecast_date, status, acceptance_status,
                       published_to_client, accepted_by, accepted_at, version
                FROM milestones
                WHERE project_id = ? AND published_to_client = true
                ORDER BY due_date ASC
                """,
                (rs, rowNum) -> new PublishedMilestoneResponse(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("project_id")),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getDate("due_date") != null ? rs.getDate("due_date").toLocalDate() : null,
                        rs.getDate("forecast_date") != null ? rs.getDate("forecast_date").toLocalDate() : null,
                        rs.getString("status"),
                        rs.getString("acceptance_status"),
                        rs.getBoolean("published_to_client"),
                        rs.getString("accepted_by") != null ? UUID.fromString(rs.getString("accepted_by")) : null,
                        rs.getTimestamp("accepted_at") != null ? rs.getTimestamp("accepted_at").toInstant() : null,
                        rs.getLong("version")
                ),
                projectId
        );
    }

    public MilestoneReviewReceiptResponse reviewMilestone(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID milestoneId,
            String decision,
            String comments) {

        // Caller MUST hold the CLIENT role
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.CLIENT);

        Milestone milestone = milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .orElseThrow(() -> ClientPortalException.notFound("Milestone not found: " + milestoneId));

        if (!Boolean.TRUE.equals(milestone.getPublishedToClient())) {
            throw ClientPortalException.notPublished("Milestone is not published to client: " + milestoneId);
        }

        String normDecision = decision != null ? decision.trim().toUpperCase(Locale.ROOT) : "";
        if (!"ACCEPTED".equals(normDecision) && !"CHANGES_REQUESTED".equals(normDecision)) {
            throw ClientPortalException.invalidDecision("Decision must be ACCEPTED or CHANGES_REQUESTED, got: " + decision);
        }

        Instant now = Instant.now();
        List<Object> history = milestone.getAcceptanceHistory() != null
                ? new ArrayList<>(milestone.getAcceptanceHistory())
                : new ArrayList<>();

        Map<String, Object> historyEntry = new LinkedHashMap<>();
        historyEntry.put("decision", normDecision);
        historyEntry.put("actorId", principal.userId().toString());
        historyEntry.put("comments", comments != null ? comments : "");
        historyEntry.put("timestamp", now.toString());
        history.add(historyEntry);
        milestone.setAcceptanceHistory(history);

        if ("ACCEPTED".equals(normDecision)) {
            milestone.setStatus(Milestone.Status.ACCEPTED);
            milestone.setAcceptanceStatus(Milestone.AcceptanceStatus.ACCEPTED);
            milestone.setAcceptedBy(principal.userId());
            milestone.setAcceptedAt(now);
        } else {
            milestone.setAcceptanceStatus(Milestone.AcceptanceStatus.CHANGES_REQUESTED);
        }

        milestoneRepository.save(milestone);

        return new MilestoneReviewReceiptResponse(
                milestone.getId(),
                normDecision,
                milestone.getStatus().name(),
                milestone.getAcceptanceStatus().name(),
                principal.userId(),
                now,
                comments
        );
    }

    public PublishedMilestoneResponse publishMilestone(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID milestoneId,
            boolean publish) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        Milestone milestone = milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .orElseThrow(() -> ClientPortalException.notFound("Milestone not found: " + milestoneId));

        milestone.setPublishedToClient(publish);
        Milestone saved = milestoneRepository.save(milestone);
        return PublishedMilestoneResponse.from(saved);
    }
}
