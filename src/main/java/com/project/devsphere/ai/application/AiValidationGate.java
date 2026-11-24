package com.project.devsphere.ai.application;

import com.project.devsphere.ai.domain.AiJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class AiValidationGate {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );
    private static final List<String> ESTIMATE_KEYS = List.of(
            "estimateMinutes", "estimate_minutes", "storyPoints", "story_points"
    );
    private static final Map<String, String> REPORT_METRIC_QUERIES = Map.ofEntries(
            Map.entry("totalWorkItems", "SELECT count(*) FROM work_items WHERE project_id = ? AND deleted_at IS NULL"),
            Map.entry("completedWorkItems", "SELECT count(*) FROM work_items WHERE project_id = ? AND status = 'DONE' AND deleted_at IS NULL"),
            Map.entry("doneWorkItems", "SELECT count(*) FROM work_items WHERE project_id = ? AND status = 'DONE' AND deleted_at IS NULL"),
            Map.entry("inProgressWorkItems", "SELECT count(*) FROM work_items WHERE project_id = ? AND status = 'IN_PROGRESS' AND deleted_at IS NULL"),
            Map.entry("blockedWorkItems", "SELECT count(*) FROM work_items WHERE project_id = ? AND is_blocked = true AND deleted_at IS NULL"),
            Map.entry("openAlerts", "SELECT count(*) FROM alerts WHERE project_id = ? AND status <> 'RESOLVED'"),
            Map.entry("resolvedAlerts", "SELECT count(*) FROM alerts WHERE project_id = ? AND status = 'RESOLVED'"),
            Map.entry("activeSprints", "SELECT count(*) FROM sprints WHERE project_id = ? AND status = 'ACTIVE'"),
            Map.entry("totalSprints", "SELECT count(*) FROM sprints WHERE project_id = ?"),
            Map.entry("totalMilestones", "SELECT count(*) FROM milestones WHERE project_id = ?"),
            Map.entry("acceptedMilestones", "SELECT count(*) FROM milestones WHERE project_id = ? AND acceptance_status = 'ACCEPTED'")
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
            ValidationResult planFailure = validateRequirementPlan(outputJson, report);
            if (planFailure != null) {
                return planFailure;
            }
        } else if (jobType == AiJob.JobType.PR_REVIEW || jobType == AiJob.JobType.PUSH_REVIEW) {
            ValidationResult reviewFailure = validateCodeReview(job, outputJson, report);
            if (reviewFailure != null) {
                return reviewFailure;
            }
        } else if (jobType == AiJob.JobType.REPORT_SUMMARY) {
            ValidationResult reportFailure = validateReportSummary(outputJson, projectId, report);
            if (reportFailure != null) {
                return reportFailure;
            }
        }

        report.put("valid", true);
        report.put("findings", List.of());
        return new ValidationResult(true, AiJob.ValidationStatus.VALID, report);
    }

    private ValidationResult validateRequirementPlan(Map<String, Object> outputJson, Map<String, Object> report) {
        if (!outputJson.containsKey("items") && !outputJson.containsKey("proposal_payload")) {
            report.put("valid", false);
            report.put("error", "REQUIREMENT_PLAN output requires 'items' or 'proposal_payload'");
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        Map<?, ?> payloadMap = outputJson.get("proposal_payload") instanceof Map<?, ?> m ? m : null;
        Object rawItems = outputJson.containsKey("items")
                ? outputJson.get("items")
                : (payloadMap != null ? payloadMap.get("items") : null);

        if (rawItems != null && !(rawItems instanceof List<?>)) {
            report.put("valid", false);
            report.put("error", "REQUIREMENT_PLAN 'items' must be a list");
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        List<?> items = rawItems instanceof List<?> l ? l : List.of();
        List<Map<String, Object>> invalidEstimates = new ArrayList<>();
        Map<String, Set<String>> graph = new LinkedHashMap<>();

        for (int i = 0; i < items.size(); i++) {
            Object rawItem = items.get(i);
            if (!(rawItem instanceof Map<?, ?> item)) {
                continue;
            }
            String itemKey = extractItemKey(item, "#" + i);
            graph.putIfAbsent(itemKey, new LinkedHashSet<>());

            for (String estimateKey : ESTIMATE_KEYS) {
                if (!item.containsKey(estimateKey) || item.get(estimateKey) == null) {
                    continue;
                }
                Double estimate = parseFiniteDouble(item.get(estimateKey));
                if (estimate == null || estimate <= 0.0d) {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("item", itemKey);
                    issue.put("field", estimateKey);
                    issue.put("value", item.get(estimateKey));
                    invalidEstimates.add(issue);
                }
            }

            Object dependsOn = item.containsKey("dependsOn") ? item.get("dependsOn") : item.get("depends_on");
            if (dependsOn instanceof List<?> depList) {
                for (Object dep : depList) {
                    if (dep != null && !dep.toString().isBlank()) {
                        addEdge(graph, dep.toString().trim(), itemKey);
                    }
                }
            } else if (dependsOn instanceof String depStr && !depStr.isBlank()) {
                addEdge(graph, depStr.trim(), itemKey);
            }
        }

        Object rawDeps = outputJson.containsKey("dependencies")
                ? outputJson.get("dependencies")
                : (payloadMap != null ? payloadMap.get("dependencies") : null);

        if (rawDeps instanceof List<?> deps) {
            for (Object rawDep : deps) {
                if (!(rawDep instanceof Map<?, ?> depMap)) {
                    continue;
                }
                String from = firstNonBlank(depMap, "predecessorKey", "predecessor_key", "predecessor", "from", "source");
                String to = firstNonBlank(depMap, "successorKey", "successor_key", "successor", "to", "target");
                if (from != null && to != null) {
                    addEdge(graph, from, to);
                }
            }
        }

        if (!invalidEstimates.isEmpty()) {
            report.put("valid", false);
            report.put("error", "REQUIREMENT_PLAN contains non-positive estimates");
            report.put("invalidEstimates", invalidEstimates);
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        if (hasCycle(graph)) {
            report.put("valid", false);
            report.put("dependencyCycleDetected", true);
            report.put("error", "REQUIREMENT_PLAN contains a dependency cycle");
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        return null;
    }

    private ValidationResult validateCodeReview(AiJob job, Map<String, Object> outputJson, Map<String, Object> report) {
        if (!outputJson.containsKey("findings") && !outputJson.containsKey("status") && !outputJson.containsKey("summary")) {
            report.put("valid", false);
            report.put("error", "Code review output requires 'findings', 'status', or 'summary'");
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        if (job.getInputRefs() == null || job.getInputRefs().get("diffFiles") == null) {
            return null;
        }

        Map<String, LineBounds> diffBounds = parseDiffFiles(job.getInputRefs().get("diffFiles"));
        Object rawFindings = outputJson.get("findings");
        if (rawFindings == null) {
            return null;
        }
        if (!(rawFindings instanceof List<?> findings)) {
            report.put("valid", false);
            report.put("error", "Code review 'findings' must be a list");
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        List<Map<String, Object>> invalidFindings = new ArrayList<>();
        for (Object rawFinding : findings) {
            if (!(rawFinding instanceof Map<?, ?> finding)) {
                invalidFindings.add(Map.of("reason", "Finding must be an object"));
                continue;
            }
            String filePath = firstNonBlank(finding, "filePath", "file_path", "path", "file");
            String normalizedPath = normalizePath(filePath);
            if (normalizedPath == null || !diffBounds.containsKey(normalizedPath)) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("filePath", filePath);
                issue.put("reason", "File path is not in diffFiles");
                invalidFindings.add(issue);
                continue;
            }

            Object rawLine = finding.containsKey("lineNumber") ? finding.get("lineNumber")
                    : (finding.containsKey("line_number") ? finding.get("line_number") : finding.get("line"));
            if (rawLine != null) {
                Integer lineNumber = parseWholeInt(rawLine);
                LineBounds bounds = diffBounds.get(normalizedPath);
                if (lineNumber == null
                        || lineNumber < bounds.minLine()
                        || (bounds.maxLine() != null && lineNumber > bounds.maxLine())) {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("filePath", filePath);
                    issue.put("lineNumber", rawLine);
                    issue.put("allowedRange", bounds.minLine() + ".." + (bounds.maxLine() != null ? bounds.maxLine() : "*"));
                    issue.put("reason", "Line number is outside diff bounds");
                    invalidFindings.add(issue);
                }
            }
        }

        if (!invalidFindings.isEmpty()) {
            report.put("valid", false);
            report.put("error", "Review findings fall outside diffFiles or line bounds");
            report.put("invalidFindings", invalidFindings);
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        return null;
    }

    private ValidationResult validateReportSummary(
            Map<String, Object> outputJson,
            UUID projectId,
            Map<String, Object> report) {
        if (!outputJson.containsKey("summary") && !outputJson.containsKey("facts")) {
            report.put("valid", false);
            report.put("error", "REPORT_SUMMARY output requires 'summary' or 'facts'");
            return new ValidationResult(false, AiJob.ValidationStatus.INVALID, report);
        }

        Object rawMetrics = outputJson.get("metrics");
        if (projectId == null || !(rawMetrics instanceof Map<?, ?> metrics) || metrics.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> factMismatches = new ArrayList<>();
        for (Map.Entry<?, ?> entry : metrics.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().toString().trim();
            String sql = REPORT_METRIC_QUERIES.get(key);
            if (sql == null) {
                continue;
            }

            Long actual = jdbcTemplate.queryForObject(sql, Long.class, projectId);
            long actualCount = actual != null ? actual : 0L;
            Long claimed = parseWholeLong(entry.getValue());
            if (claimed == null || claimed != actualCount) {
                Map<String, Object> mismatch = new LinkedHashMap<>();
                mismatch.put("metric", key);
                mismatch.put("claimed", entry.getValue());
                mismatch.put("actual", actualCount);
                factMismatches.add(mismatch);
            }
        }

        if (!factMismatches.isEmpty()) {
            report.put("valid", false);
            report.put("error", "REPORT_SUMMARY metrics mismatch project ground truth");
            report.put("factMismatches", factMismatches);
            return new ValidationResult(false, AiJob.ValidationStatus.NEEDS_REVIEW, report);
        }

        return null;
    }

    private static Map<String, LineBounds> parseDiffFiles(Object rawDiffFiles) {
        Map<String, LineBounds> result = new LinkedHashMap<>();
        if (rawDiffFiles instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String path = normalizePath(entry.getKey().toString());
                if (path == null) {
                    continue;
                }
                result.put(path, parseLineBounds(entry.getValue()));
            }
        } else if (rawDiffFiles instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String pathStr) {
                    String path = normalizePath(pathStr);
                    if (path != null) {
                        result.put(path, new LineBounds(1, null));
                    }
                } else if (item instanceof Map<?, ?> m) {
                    String path = normalizePath(firstNonBlank(m, "filePath", "file_path", "path", "file"));
                    if (path != null) {
                        result.put(path, parseLineBounds(m));
                    }
                }
            }
        }
        return result;
    }

    private static LineBounds parseLineBounds(Object spec) {
        if (spec instanceof Number || spec instanceof String) {
            Integer maxLine = parseWholeInt(spec);
            return new LineBounds(1, maxLine);
        }
        if (spec instanceof Map<?, ?> m) {
            Integer minLine = parseWholeInt(m.containsKey("minLine") ? m.get("minLine") : m.get("startLine"));
            Object maxCandidate = m.containsKey("maxLine") ? m.get("maxLine")
                    : (m.containsKey("endLine") ? m.get("endLine")
                    : (m.containsKey("lines") ? m.get("lines") : m.get("lineCount")));
            Integer maxLine = parseWholeInt(maxCandidate);
            return new LineBounds(minLine != null && minLine >= 1 ? minLine : 1, maxLine);
        }
        return new LineBounds(1, null);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        while (trimmed.startsWith("./")) {
            trimmed = trimmed.substring(2);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void addEdge(Map<String, Set<String>> graph, String from, String to) {
        graph.putIfAbsent(from, new LinkedHashSet<>());
        graph.putIfAbsent(to, new LinkedHashSet<>());
        graph.get(from).add(to);
    }

    private static boolean hasCycle(Map<String, Set<String>> graph) {
        Map<String, Integer> state = new HashMap<>(); // 0=unvisited, 1=visiting, 2=visited
        for (String node : graph.keySet()) {
            if (state.getOrDefault(node, 0) == 0 && dfsCycle(node, graph, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dfsCycle(String node, Map<String, Set<String>> graph, Map<String, Integer> state) {
        state.put(node, 1);
        for (String next : graph.getOrDefault(node, Set.of())) {
            int nextState = state.getOrDefault(next, 0);
            if (nextState == 1) {
                return true;
            }
            if (nextState == 0 && dfsCycle(next, graph, state)) {
                return true;
            }
        }
        state.put(node, 2);
        return false;
    }

    private static String extractItemKey(Map<?, ?> item, String fallback) {
        String key = firstNonBlank(item, "tempKey", "temp_key", "key", "itemKey", "item_key", "id", "title");
        return key != null ? key : fallback;
    }

    private static String firstNonBlank(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null) {
                String str = val.toString().trim();
                if (!str.isEmpty()) {
                    return str;
                }
            }
        }
        return null;
    }

    private static Double parseFiniteDouble(Object value) {
        if (value instanceof Boolean) {
            return null;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static Integer parseWholeInt(Object value) {
        Long longVal = parseWholeLong(value);
        if (longVal == null || longVal < Integer.MIN_VALUE || longVal > Integer.MAX_VALUE) {
            return null;
        }
        return longVal.intValue();
    }

    private static Long parseWholeLong(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        try {
            if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
                return ((Number) value).longValue();
            }
            if (value instanceof BigDecimal bd) {
                return bd.stripTrailingZeros().scale() <= 0 ? bd.longValueExact() : null;
            }
            if (value instanceof Number n) {
                double d = n.doubleValue();
                if (!Double.isFinite(d) || d != Math.rint(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
                    return null;
                }
                return (long) d;
            }
            if (value instanceof String s && !s.isBlank()) {
                BigDecimal bd = new BigDecimal(s.trim());
                return bd.stripTrailingZeros().scale() <= 0 ? bd.longValueExact() : null;
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
        return null;
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

            // Restrict user references strictly to project members
            Integer mCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM project_members WHERE project_id = ? AND user_id = ?",
                    Integer.class, projectId, id
            );
            return mCount != null && mCount > 0;
        }

        // Global fallback if no project context
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

    private record LineBounds(int minLine, Integer maxLine) {
    }
}
