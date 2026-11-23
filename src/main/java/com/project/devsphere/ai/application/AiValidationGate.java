package com.project.devsphere.ai.application;

import com.project.devsphere.ai.domain.AiJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class AiValidationGate {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private final JdbcTemplate jdbcTemplate;

    public AiValidationGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    public record ValidationResult(
            boolean valid,
            AiJob.ValidationStatus status,
            Map<String, Object> report
    ) {
    }

    public ValidationResult validate(AiJob job, Map<String, Object> outputJson, UUID projectId) {
        if (outputJson == null || outputJson.isEmpty()) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", 1);
            report.put("valid", false);
            report.put("error", "Output JSON is empty or null");
            report.put("validatedAt", Instant.now().toString());
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        // 1. Collect all references from inputRefs to whitelist them
        Set<UUID> inputUuids = new HashSet<>();
        if (job.getInputRefs() != null) {
            collectUuids(job.getInputRefs(), inputUuids);
        }

        // 2. Collect all UUID references from outputJson
        Set<UUID> outputUuids = new HashSet<>();
        collectUuids(outputJson, outputUuids);

        // 3. Hallucination check: each output UUID not in inputRefs must exist in project database
        List<String> hallucinated = new ArrayList<>();
        int checkedCount = 0;

        for (UUID ref : outputUuids) {
            checkedCount++;
            if (inputUuids.contains(ref)) {
                continue;
            }

            if (!referenceExistsInDatabase(ref, projectId)) {
                hallucinated.add(ref.toString());
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("checkedReferencesCount", checkedCount);
        report.put("validatedAt", Instant.now().toString());

        if (!hallucinated.isEmpty()) {
            report.put("valid", false);
            report.put("error", "Hallucinated references detected: IDs not found in input_refs or database");
            report.put("hallucinatedReferences", hallucinated);
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        // 4. Job-type specific output validation
        AiJob.JobType jobType = job.getJobType();
        if (jobType == AiJob.JobType.REQUIREMENT_PLAN) {
            if (!outputJson.containsKey("items") && !outputJson.containsKey("proposal_payload")) {
                report.put("valid", false);
                report.put("error", "REQUIREMENT_PLAN output requires 'items' or 'proposal_payload'");
                return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
            }
        } else if (jobType == AiJob.JobType.PR_REVIEW || jobType == AiJob.JobType.PUSH_REVIEW) {
            if (!outputJson.containsKey("findings") && !outputJson.containsKey("status") && !outputJson.containsKey("summary")) {
                report.put("valid", false);
                report.put("error", "Code review output requires 'findings', 'status', or 'summary'");
                return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
            }
        } else if (jobType == AiJob.JobType.REPORT_SUMMARY) {
            if (!outputJson.containsKey("summary") && !outputJson.containsKey("facts")) {
                report.put("valid", false);
                report.put("error", "REPORT_SUMMARY output requires 'summary' or 'facts'");
                return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
            }
        }

        report.put("valid", true);
        report.put("findings", List.of());
        return new ValidationResult(true, AiJob.ValidationStatus.VALID, report);
    }

    private boolean referenceExistsInDatabase(UUID id, UUID projectId) {
        if (projectId != null) {
            // Check work items
            Integer wiCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM work_items WHERE id = ? AND project_id = ? AND deleted_at IS NULL",
                    Integer.class, id, projectId
            );
            if (wiCount != null && wiCount > 0) return true;

            // Check milestones
            Integer msCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM milestones WHERE id = ? AND project_id = ?",
                    Integer.class, id, projectId
            );
            if (msCount != null && msCount > 0) return true;

            // Check project members / users
            Integer pmCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM project_members WHERE project_id = ? AND (user_id = ? OR id = ?)",
                    Integer.class, projectId, id, id
            );
            if (pmCount != null && pmCount > 0) return true;

            // Check project itself
            if (projectId.equals(id)) return true;
        }

        // Check users
        Integer uCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = ?",
                Integer.class, id
        );
        return uCount != null && uCount > 0;
    }

    private void collectUuids(Object obj, Set<UUID> collected) {
        if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectUuids(entry.getValue(), collected);
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                collectUuids(item, collected);
            }
        } else if (obj instanceof String str) {
            if (str.length() == 36 && UUID_PATTERN.matcher(str).matches()) {
                try {
                    collected.add(UUID.fromString(str));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } else if (obj instanceof UUID uuid) {
            collected.add(uuid);
        }
    }
}
