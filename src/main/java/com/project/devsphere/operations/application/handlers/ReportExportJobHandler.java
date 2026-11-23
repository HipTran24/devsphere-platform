package com.project.devsphere.operations.application.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.operations.application.ClaimedJob;
import com.project.devsphere.operations.application.JobHandler;
import com.project.devsphere.operations.application.JobResult;
import com.project.devsphere.operations.application.NonRetryableJobException;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Generates a scheduled project report draft (currently {@code DAILY}) from live work-item data.
 *
 * <p>The draft is stored in {@code reports} with status DRAFT so a PM still approves and publishes
 * it through the reporting workflow. Generation is idempotent per project, type and period.</p>
 */
@Component
public class ReportExportJobHandler implements JobHandler {

    private static final Set<String> SUPPORTED_TYPES = Set.of("DAILY");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ReportExportJobHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BackgroundJob.JobKind kind() {
        return BackgroundJob.JobKind.REPORT_EXPORT;
    }

    @Override
    public JobResult handle(ClaimedJob job) {
        if (job.projectId() == null) {
            throw new NonRetryableJobException("Report generation must be project scoped");
        }
        String type = job.requireString("reportType");
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new NonRetryableJobException("Unsupported scheduled report type");
        }
        LocalDate period;
        try {
            period = LocalDate.parse(job.requireString("periodDate"));
        } catch (DateTimeParseException ex) {
            throw new NonRetryableJobException("periodDate must be an ISO date", ex);
        }

        List<UUID> existing = jdbc.queryForList(
                "select id from reports where project_id=? and type=? and period_start=? and period_end=? limit 1",
                UUID.class, job.projectId(), type, period, period);
        if (!existing.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reportId", existing.getFirst().toString());
            details.put("outcome", "ALREADY_GENERATED");
            return JobResult.of(details);
        }

        Map<String, Object> statusCounts = new LinkedHashMap<>();
        jdbc.query("""
                select status, count(*) as items from work_items
                where project_id=? and deleted_at is null group by status order by status
                """, rs -> {
            statusCounts.put(rs.getString("status"), rs.getLong("items"));
        }, job.projectId());
        Map<String, Object> flags = jdbc.queryForMap("""
                select count(*) filter (where is_blocked) as blocked,
                       count(*) filter (where status<>'DONE' and due_at is not null and due_at<now()) as overdue,
                       count(*) filter (where status='DONE' and completed_at>=?::date and completed_at<(?::date + 1)) as completed
                from work_items where project_id=? and deleted_at is null
                """, period, period, job.projectId());

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", 1);
        content.put("generatedBy", "scheduler");
        content.put("periodDate", period.toString());
        content.put("workItemsByStatus", statusCounts);
        content.put("blockedItems", ((Number) flags.get("blocked")).longValue());
        content.put("overdueItems", ((Number) flags.get("overdue")).longValue());
        content.put("completedInPeriod", ((Number) flags.get("completed")).longValue());

        UUID reportId = jdbc.queryForObject("""
                insert into reports(project_id,type,period_start,period_end,status,content,visible_to_client)
                values (?,?,?,?,'DRAFT',?::jsonb,false) returning id
                """, UUID.class, job.projectId(), type, period, period, toJson(content));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reportId", reportId == null ? null : reportId.toString());
        details.put("reportType", type);
        details.put("periodDate", period.toString());
        details.put("outcome", "GENERATED");
        return JobResult.of(details);
    }

    private static String toJson(Map<String, Object> content) {
        try {
            return JSON.writeValueAsString(content);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Report content cannot be serialised", ex);
        }
    }
}
