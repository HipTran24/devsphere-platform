package com.project.devsphere.monitoring.application;

import com.project.devsphere.monitoring.api.dto.AffectedTaskDto;
import com.project.devsphere.monitoring.api.dto.ControlTowerGraphResponse;
import com.project.devsphere.monitoring.api.dto.CpmEdgeDto;
import com.project.devsphere.monitoring.api.dto.CpmNodeDto;
import com.project.devsphere.monitoring.api.dto.ImpactAnalysisResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

@Component
public class CpmAnalyzer {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public CpmAnalyzer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record CpmTask(
            UUID id,
            String itemKey,
            String title,
            String status,
            boolean isBlocked,
            int durationMinutes
    ) {}

    public record CpmDependency(
            UUID predecessorId,
            UUID successorId,
            String linkType,
            int lagMinutes
    ) {}

    public record CpmAnalysisResult(
            UUID projectId,
            long projectMakespanMinutes,
            int totalTasks,
            int completedTasks,
            int criticalTasksCount,
            List<UUID> criticalPathItemIds,
            List<CpmNodeDto> nodes,
            List<CpmEdgeDto> edges,
            Map<UUID, CpmNodeDto> nodeMap,
            List<CpmTask> topologicalOrder,
            Map<UUID, List<CpmDependency>> outgoingEdges,
            Map<UUID, List<CpmDependency>> incomingEdges
    ) {
        public ControlTowerGraphResponse toGraphResponse() {
            return new ControlTowerGraphResponse(
                    projectId,
                    projectMakespanMinutes,
                    totalTasks,
                    completedTasks,
                    criticalTasksCount,
                    criticalPathItemIds,
                    nodes,
                    edges
            );
        }

        public ImpactAnalysisResponse calculateImpact(UUID targetWorkItemId, int delayMinutes) {
            if (targetWorkItemId == null) {
                throw MonitoringException.validationFailed("Target work item ID must not be null");
            }
            if (delayMinutes <= 0) {
                throw MonitoringException.validationFailed("delayMinutes must be positive, was: " + delayMinutes);
            }

            CpmNodeDto targetNode = nodeMap.get(targetWorkItemId);
            if (targetNode == null) {
                throw MonitoringException.resourceNotFound("Work item not found in project CPM graph: " + targetWorkItemId);
            }

            Map<UUID, Long> simEarlyStart = new HashMap<>();
            Map<UUID, Long> simEarlyFinish = new HashMap<>();

            for (CpmNodeDto n : nodes) {
                simEarlyStart.put(n.workItemId(), n.earlyStartMinutes());
                simEarlyFinish.put(n.workItemId(), n.earlyFinishMinutes());
            }

            // Simulate delayed finish for target task
            simEarlyFinish.put(targetWorkItemId, targetNode.earlyFinishMinutes() + delayMinutes);

            List<AffectedTaskDto> affectedList = new ArrayList<>();

            // Forward propagate along topological order
            for (CpmTask task : topologicalOrder) {
                UUID u = task.id();
                if (u.equals(targetWorkItemId)) {
                    continue;
                }

                long originalEs = simEarlyStart.get(u);
                long newEs = originalEs;

                List<CpmDependency> inEdges = incomingEdges.getOrDefault(u, List.of());
                for (CpmDependency dep : inEdges) {
                    long predEf = simEarlyFinish.get(dep.predecessorId());
                    long requiredStart = predEf + Math.max(0, dep.lagMinutes());
                    if (requiredStart > newEs) {
                        newEs = requiredStart;
                    }
                }

                if (newEs > originalEs) {
                    simEarlyStart.put(u, newEs);
                    simEarlyFinish.put(u, newEs + task.durationMinutes());
                    long shift = newEs - originalEs;

                    CpmNodeDto origNode = nodeMap.get(u);
                    affectedList.add(new AffectedTaskDto(
                            u,
                            task.itemKey(),
                            task.title(),
                            originalEs,
                            newEs,
                            shift,
                            origNode != null && origNode.critical()
                    ));
                }
            }

            long newMakespan = 0;
            for (Map.Entry<UUID, Long> entry : simEarlyFinish.entrySet()) {
                if (entry.getValue() > newMakespan) {
                    newMakespan = entry.getValue();
                }
            }

            long projectDelay = Math.max(0L, newMakespan - projectMakespanMinutes);
            boolean shiftsDeadline = projectDelay > 0;

            return new ImpactAnalysisResponse(
                    projectId,
                    targetWorkItemId,
                    targetNode.itemKey(),
                    delayMinutes,
                    targetNode.totalSlackMinutes(),
                    projectDelay,
                    shiftsDeadline,
                    affectedList
            );
        }
    }

    /**
     * Executes CPM analysis for a given project ID by reading current tasks and finish-to-start dependencies.
     */
    public CpmAnalysisResult analyzeProject(UUID projectId) {
        if (projectId == null) {
            throw MonitoringException.validationFailed("Project ID must not be null");
        }

        List<CpmTask> tasks = loadProjectTasks(projectId);
        List<CpmDependency> dependencies = loadProjectDependencies(projectId);

        return analyze(projectId, tasks, dependencies);
    }

    /**
     * Pure domain CPM computation for given tasks and dependencies.
     */
    public CpmAnalysisResult analyze(UUID projectId, List<CpmTask> tasks, List<CpmDependency> dependencies) {
        if (tasks == null || tasks.isEmpty()) {
            return new CpmAnalysisResult(
                    projectId,
                    0L,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of()
            );
        }

        Map<UUID, CpmTask> taskMap = new LinkedHashMap<>();
        int completedCount = 0;
        for (CpmTask t : tasks) {
            taskMap.put(t.id(), t);
            if ("DONE".equalsIgnoreCase(t.status())) {
                completedCount++;
            }
        }

        // Filter dependencies so both predecessor and successor exist in task set
        Map<UUID, List<CpmDependency>> outgoing = new HashMap<>();
        Map<UUID, List<CpmDependency>> incoming = new HashMap<>();
        Map<UUID, Integer> inDegree = new HashMap<>();

        for (UUID id : taskMap.keySet()) {
            outgoing.put(id, new ArrayList<>());
            incoming.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }

        List<CpmDependency> validDependencies = new ArrayList<>();
        if (dependencies != null) {
            for (CpmDependency dep : dependencies) {
                if (taskMap.containsKey(dep.predecessorId()) && taskMap.containsKey(dep.successorId())) {
                    if ("FINISH_TO_START".equalsIgnoreCase(dep.linkType())) {
                        outgoing.get(dep.predecessorId()).add(dep);
                        incoming.get(dep.successorId()).add(dep);
                        inDegree.put(dep.successorId(), inDegree.get(dep.successorId()) + 1);
                        validDependencies.add(dep);
                    }
                }
            }
        }

        // 1. Topological Sort via Kahn's algorithm (Cycle Detection)
        Queue<UUID> queue = new ArrayDeque<>();
        for (Map.Entry<UUID, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<CpmTask> topologicalOrder = new ArrayList<>();
        Map<UUID, Integer> remainingInDegree = new HashMap<>(inDegree);

        while (!queue.isEmpty()) {
            UUID u = queue.poll();
            topologicalOrder.add(taskMap.get(u));

            for (CpmDependency edge : outgoing.get(u)) {
                UUID v = edge.successorId();
                int deg = remainingInDegree.get(v) - 1;
                remainingInDegree.put(v, deg);
                if (deg == 0) {
                    queue.add(v);
                }
            }
        }

        if (topologicalOrder.size() < taskMap.size()) {
            throw MonitoringException.dependencyCycleDetected(
                    "Dependency cycle detected in project work items: processed "
                            + topologicalOrder.size() + " of " + taskMap.size() + " items"
            );
        }

        // 2. Forward Pass: Calculate Early Start (ES) and Early Finish (EF)
        Map<UUID, Long> earlyStart = new HashMap<>();
        Map<UUID, Long> earlyFinish = new HashMap<>();

        for (CpmTask t : tasks) {
            earlyStart.put(t.id(), 0L);
            earlyFinish.put(t.id(), 0L);
        }

        for (CpmTask t : topologicalOrder) {
            UUID u = t.id();
            long es = earlyStart.get(u);
            long ef = es + Math.max(0, t.durationMinutes());
            earlyFinish.put(u, ef);

            for (CpmDependency edge : outgoing.get(u)) {
                UUID v = edge.successorId();
                long lag = Math.max(0, edge.lagMinutes());
                long candidateEs = ef + lag;
                if (candidateEs > earlyStart.get(v)) {
                    earlyStart.put(v, candidateEs);
                }
            }
        }

        long projectMakespan = 0L;
        for (Long ef : earlyFinish.values()) {
            if (ef > projectMakespan) {
                projectMakespan = ef;
            }
        }

        // 3. Backward Pass: Calculate Late Finish (LF) and Late Start (LS)
        Map<UUID, Long> lateStart = new HashMap<>();
        Map<UUID, Long> lateFinish = new HashMap<>();

        for (int i = topologicalOrder.size() - 1; i >= 0; i--) {
            CpmTask t = topologicalOrder.get(i);
            UUID u = t.id();
            List<CpmDependency> outEdges = outgoing.get(u);

            long lf;
            if (outEdges.isEmpty()) {
                // Sink node terminates at project makespan
                lf = projectMakespan;
            } else {
                long minLs = Long.MAX_VALUE;
                for (CpmDependency edge : outEdges) {
                    UUID v = edge.successorId();
                    long lag = Math.max(0, edge.lagMinutes());
                    long lsV = lateStart.get(v);
                    long candidateLf = lsV - lag;
                    if (candidateLf < minLs) {
                        minLs = candidateLf;
                    }
                }
                lf = (minLs == Long.MAX_VALUE) ? projectMakespan : minLs;
            }

            long ls = lf - Math.max(0, t.durationMinutes());
            lateFinish.put(u, lf);
            lateStart.put(u, ls);
        }

        // 4. Slack & Critical Path Identification
        Map<UUID, Long> totalSlack = new HashMap<>();
        Map<UUID, Long> freeSlack = new HashMap<>();
        Map<UUID, Boolean> isCritical = new HashMap<>();

        int criticalCount = 0;
        for (CpmTask t : tasks) {
            UUID u = t.id();
            long es = earlyStart.get(u);
            long ef = earlyFinish.get(u);
            long ls = lateStart.get(u);
            long lf = lateFinish.get(u);

            long ts = ls - es; // Equivalent to lf - ef
            totalSlack.put(u, ts);

            List<CpmDependency> outEdges = outgoing.get(u);
            long fs;
            if (outEdges.isEmpty()) {
                fs = projectMakespan - ef;
            } else {
                long minNextEs = Long.MAX_VALUE;
                for (CpmDependency edge : outEdges) {
                    UUID v = edge.successorId();
                    long lag = Math.max(0, edge.lagMinutes());
                    long esV = earlyStart.get(v);
                    long candidate = esV - lag;
                    if (candidate < minNextEs) {
                        minNextEs = candidate;
                    }
                }
                fs = (minNextEs == Long.MAX_VALUE) ? (projectMakespan - ef) : Math.max(0L, minNextEs - ef);
            }
            freeSlack.put(u, fs);

            boolean crit = ts <= 0;
            isCritical.put(u, crit);
            if (crit) {
                criticalCount++;
            }
        }

        // Build CpmNodeDto list and map
        List<CpmNodeDto> nodeDtos = new ArrayList<>();
        Map<UUID, CpmNodeDto> nodeMap = new HashMap<>();

        for (CpmTask t : tasks) {
            UUID u = t.id();
            CpmNodeDto dto = new CpmNodeDto(
                    u,
                    t.itemKey(),
                    t.title(),
                    t.status(),
                    t.isBlocked(),
                    t.durationMinutes(),
                    earlyStart.get(u),
                    earlyFinish.get(u),
                    lateStart.get(u),
                    lateFinish.get(u),
                    totalSlack.get(u),
                    freeSlack.get(u),
                    isCritical.get(u)
            );
            nodeDtos.add(dto);
            nodeMap.put(u, dto);
        }

        // Build CpmEdgeDto list
        List<CpmEdgeDto> edgeDtos = new ArrayList<>();
        for (CpmDependency dep : validDependencies) {
            UUID p = dep.predecessorId();
            UUID s = dep.successorId();
            boolean edgeCritical = Boolean.TRUE.equals(isCritical.get(p))
                    && Boolean.TRUE.equals(isCritical.get(s))
                    && (earlyFinish.get(p) + Math.max(0, dep.lagMinutes()) == earlyStart.get(s));

            edgeDtos.add(new CpmEdgeDto(
                    p,
                    s,
                    dep.linkType(),
                    dep.lagMinutes(),
                    edgeCritical
            ));
        }

        // Extract primary critical path sequence
        List<UUID> criticalPathItemIds = extractCriticalPath(topologicalOrder, isCritical, outgoing, earlyFinish, earlyStart);

        return new CpmAnalysisResult(
                projectId,
                projectMakespan,
                tasks.size(),
                completedCount,
                criticalCount,
                criticalPathItemIds,
                Collections.unmodifiableList(nodeDtos),
                Collections.unmodifiableList(edgeDtos),
                Collections.unmodifiableMap(nodeMap),
                Collections.unmodifiableList(topologicalOrder),
                Collections.unmodifiableMap(outgoing),
                Collections.unmodifiableMap(incoming)
        );
    }

    private List<UUID> extractCriticalPath(
            List<CpmTask> topologicalOrder,
            Map<UUID, Boolean> isCritical,
            Map<UUID, List<CpmDependency>> outgoing,
            Map<UUID, Long> earlyFinish,
            Map<UUID, Long> earlyStart) {

        List<UUID> criticalRoots = new ArrayList<>();
        for (CpmTask t : topologicalOrder) {
            if (Boolean.TRUE.equals(isCritical.get(t.id())) && earlyStart.get(t.id()) == 0L) {
                criticalRoots.add(t.id());
            }
        }

        if (criticalRoots.isEmpty()) {
            return List.of();
        }

        // Trace longest critical path from first critical root
        UUID current = criticalRoots.get(0);
        List<UUID> path = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();

        while (current != null && visited.add(current)) {
            path.add(current);
            UUID nextCritical = null;
            long currentEf = earlyFinish.get(current);

            for (CpmDependency dep : outgoing.getOrDefault(current, List.of())) {
                UUID succ = dep.successorId();
                if (Boolean.TRUE.equals(isCritical.get(succ))) {
                    long lag = Math.max(0, dep.lagMinutes());
                    if (currentEf + lag == earlyStart.get(succ)) {
                        nextCritical = succ;
                        break;
                    }
                }
            }
            current = nextCritical;
        }

        return Collections.unmodifiableList(path);
    }

    private List<CpmTask> loadProjectTasks(UUID projectId) {
        if (jdbcTemplate == null) {
            return List.of();
        }
        String sql = """
                SELECT id, item_key, title, status, is_blocked, estimate_minutes, remaining_minutes
                FROM work_items
                WHERE project_id = ? AND deleted_at IS NULL
                ORDER BY created_at ASC
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            String itemKey = rs.getString("item_key");
            String title = rs.getString("title");
            String status = rs.getString("status");
            boolean isBlocked = rs.getBoolean("is_blocked");
            Integer est = (Integer) rs.getObject("estimate_minutes");
            Integer rem = (Integer) rs.getObject("remaining_minutes");

            int duration;
            if ("DONE".equalsIgnoreCase(status)) {
                duration = 0;
            } else if (rem != null) {
                duration = Math.max(0, rem);
            } else if (est != null) {
                duration = Math.max(0, est);
            } else {
                duration = 0;
            }

            return new CpmTask(id, itemKey, title, status, isBlocked, duration);
        }, projectId);
    }

    private List<CpmDependency> loadProjectDependencies(UUID projectId) {
        if (jdbcTemplate == null) {
            return List.of();
        }
        String sql = """
                SELECT predecessor_id, successor_id, link_type, lag_minutes
                FROM work_item_dependencies
                WHERE project_id = ? AND link_type = 'FINISH_TO_START'
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new CpmDependency(
                rs.getObject("predecessor_id", UUID.class),
                rs.getObject("successor_id", UUID.class),
                rs.getString("link_type"),
                rs.getInt("lag_minutes")
        ), projectId);
    }
}
