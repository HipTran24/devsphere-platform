package com.project.devsphere.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.planning.api.dto.CreatePlanProposalRequest;
import com.project.devsphere.planning.api.dto.PlanProposalResponse;
import com.project.devsphere.planning.api.dto.SolveProposalRequest;
import com.project.devsphere.planning.api.dto.SolverDiagnosticsResponse;
import com.project.devsphere.planning.domain.PlanProposal;
import com.project.devsphere.planning.infrastructure.persistence.PlanProposalRepository;
import com.project.devsphere.resource.api.ResourceCapacitySnapshot;
import com.project.devsphere.resource.api.ResourceCapacitySnapshotReader;
import com.project.devsphere.solver.application.SchedulingSolver;
import com.project.devsphere.solver.domain.SolverInputSnapshot;
import com.project.devsphere.solver.domain.SolverResult;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class PlanProposalService {

    private final PlanProposalRepository proposalRepository;
    private final AccessPolicy accessPolicy;
    private final ResourceCapacitySnapshotReader capacitySnapshotReader;
    private final SchedulingSolver schedulingSolver;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public PlanProposalService(
            PlanProposalRepository proposalRepository,
            AccessPolicy accessPolicy,
            ResourceCapacitySnapshotReader capacitySnapshotReader,
            SchedulingSolver schedulingSolver,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager) {
        this.proposalRepository = Objects.requireNonNull(proposalRepository, "proposalRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.capacitySnapshotReader = Objects.requireNonNull(capacitySnapshotReader, "capacitySnapshotReader must not be null");
        this.schedulingSolver = Objects.requireNonNull(schedulingSolver, "schedulingSolver must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Creates a new plan proposal in DRAFT status with optimistic concurrency check against projects.version.
     * Guarded by PM role.
     */
    public PlanProposalResponse createDraftProposal(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            CreatePlanProposalRequest request) {

        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null) {
            throw PlanProposalException.validationFailed("Request must not be null");
        }

        Long actualProjectVersion = getProjectVersion(projectId);
        if (!Objects.equals(actualProjectVersion, request.expectedProjectVersion())) {
            throw PlanProposalException.planStale("Project version is stale. Expected: " + request.expectedProjectVersion() + ", actual: " + actualProjectVersion);
        }

        Map<String, Object> payload = request.proposalPayload();
        if (payload == null || payload.isEmpty()) {
            payload = new LinkedHashMap<>();
            payload.put("schemaVersion", 1);
            payload.put("items", List.of());
            payload.put("dependencies", List.of());
        } else {
            payload = new LinkedHashMap<>(payload);
            if (!payload.containsKey("schemaVersion")) {
                payload.put("schemaVersion", 1);
            }
            if (!payload.containsKey("items")) {
                payload.put("items", List.of());
            }
        }

        PlanProposal proposal = new PlanProposal();
        proposal.setProjectId(projectId);
        proposal.setKind(request.kind() != null ? request.kind() : PlanProposal.Kind.MANUAL_REPLAN);
        proposal.setBaseProjectVersion(actualProjectVersion);
        proposal.setBaseScheduleVersionId(request.baseScheduleVersionId());
        proposal.setRequirementVersionId(request.requirementVersionId());
        proposal.setChangeRequestId(request.changeRequestId());
        proposal.setStatus(PlanProposal.Status.DRAFT);
        proposal.setSolverStatus(PlanProposal.SolverStatus.NOT_RUN);
        proposal.setProposalPayload(payload);
        proposal.setSolverDetails(new LinkedHashMap<>(Map.of("schemaVersion", 1)));
        proposal.setAssumptions(request.assumptions() != null ? new ArrayList<>(request.assumptions()) : new ArrayList<>());
        proposal.setCreatedBy(principal.userId());
        proposal.setChangesCommittedDeadline(request.changesCommittedDeadline() != null && request.changesCommittedDeadline());
        proposal.setProposedCommittedDeadline(request.proposedCommittedDeadline());

        PlanProposal saved = proposalRepository.save(proposal);
        proposalRepository.flush();

        return PlanProposalResponse.from(saved);
    }

    /**
     * Solves a proposal using SchedulingSolver and ResourceCapacitySnapshotReader.
     * Executes the mandatory TWO-PHASE UPDATE to comply with ds_proposal_guard().
     * Guarded by PM role.
     */
    public PlanProposalResponse solveProposal(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID proposalId,
            SolveProposalRequest request) {

        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null) {
            throw PlanProposalException.validationFailed("Request must not be null");
        }

        Long actualProjectVersion = getProjectVersion(projectId);
        if (!Objects.equals(actualProjectVersion, request.expectedProjectVersion())) {
            throw PlanProposalException.planStale("Project version is stale. Expected: " + request.expectedProjectVersion() + ", actual: " + actualProjectVersion);
        }

        PlanProposal proposal = proposalRepository.findByIdAndProjectId(proposalId, projectId)
                .orElseThrow(() -> PlanProposalException.notFound("Plan proposal not found: " + proposalId));

        if (!Objects.equals(proposal.getVersion(), request.expectedProposalVersion())) {
            throw PlanProposalException.versionConflict("Proposal version is stale. Expected: " + request.expectedProposalVersion() + ", actual: " + proposal.getVersion());
        }

        String timezoneStr = getProjectTimezone(projectId);
        ZoneId zone = ZoneId.of(timezoneStr);
        LocalDate horizonStart = request.horizonStart() != null ? request.horizonStart() : LocalDate.now(zone);
        LocalDate horizonEnd = request.horizonEnd() != null ? request.horizonEnd() : horizonStart.plusDays(90);

        int maxTimeSeconds = (request.solverLimitSeconds() != null && request.solverLimitSeconds() > 0)
                ? Math.min(30, request.solverLimitSeconds())
                : 30;

        // Read resource capacity snapshot
        ResourceCapacitySnapshot capacitySnapshot = capacitySnapshotReader.read(projectId, horizonStart, horizonEnd);

        // Build solver input snapshot from proposal payload and capacity
        SolverInputSnapshot inputSnapshot = buildSolverInputSnapshot(
                projectId,
                workspaceId,
                actualProjectVersion,
                timezoneStr,
                horizonStart,
                horizonEnd,
                maxTimeSeconds,
                proposal.getProposalPayload(),
                capacitySnapshot
        );

        // Execute solver
        SolverResult solverResult = schedulingSolver.solve(inputSnapshot);

        // Apply solution to proposal payload
        Map<String, Object> updatedPayload = applySolutionToPayload(proposal.getProposalPayload(), solverResult);

        // Construct solver_details map
        Map<String, Object> solverDetails = new LinkedHashMap<>();
        solverDetails.put("schemaVersion", 1);
        solverDetails.put("rawNativeStatus", solverResult.diagnostics().rawNativeStatus());
        solverDetails.put("wallTimeSeconds", solverResult.diagnostics().wallTimeSeconds());
        solverDetails.put("userTimeSeconds", solverResult.diagnostics().userTimeSeconds());
        solverDetails.put("numBooleans", solverResult.diagnostics().numBooleans());
        solverDetails.put("numBranches", solverResult.diagnostics().numBranches());
        solverDetails.put("numConflicts", solverResult.diagnostics().numConflicts());
        solverDetails.put("bestObjectiveBound", solverResult.diagnostics().bestObjectiveBound());
        solverDetails.put("objectiveValue", solverResult.diagnostics().objectiveValue());
        solverDetails.put("message", solverResult.diagnostics().message());
        solverDetails.put("makespanMinutes", solverResult.makespanMinutes());
        solverDetails.put("totalLatenessMinutes", solverResult.totalLatenessMinutes());
        solverDetails.put("details", solverResult.diagnostics().details());

        PlanProposal.SolverStatus targetSolverStatus = PlanProposal.SolverStatus.valueOf(solverResult.status().name());

        // MANDATORY TWO-PHASE UPDATE:
        // Phase 1: Update proposal_payload, solver_details, assumptions (trigger resets solver_status to NOT_RUN)
        String payloadJson;
        String detailsJson;
        String assumptionsJson;
        try {
            payloadJson = objectMapper.writeValueAsString(updatedPayload);
            detailsJson = objectMapper.writeValueAsString(solverDetails);
            assumptionsJson = objectMapper.writeValueAsString(proposal.getAssumptions() != null ? proposal.getAssumptions() : List.of());
        } catch (JsonProcessingException ex) {
            throw PlanProposalException.invalidPayload("Failed to serialize proposal payload: " + ex.getMessage());
        }

        jdbcTemplate.update("""
                UPDATE plan_proposals
                SET proposal_payload = ?::jsonb,
                    solver_details = ?::jsonb,
                    assumptions = ?::jsonb,
                    updated_at = clock_timestamp()
                WHERE id = ? AND project_id = ?
                """,
                payloadJson,
                detailsJson,
                assumptionsJson,
                proposalId,
                projectId
        );

        // Phase 2: Update solver_status = targetSolverStatus (trigger does NOT reset on solver_status alone)
        jdbcTemplate.update("""
                UPDATE plan_proposals
                SET solver_status = ?,
                    updated_at = clock_timestamp()
                WHERE id = ? AND project_id = ?
                """,
                targetSolverStatus.name(),
                proposalId,
                projectId
        );

        // Clear persistence context to load fresh state
        entityManager.clear();

        PlanProposal fresh = proposalRepository.findByIdAndProjectId(proposalId, projectId)
                .orElseThrow(() -> PlanProposalException.notFound("Plan proposal not found after solve: " + proposalId));

        return PlanProposalResponse.from(fresh);
    }

    /**
     * Retrieves a proposal by ID and project ID.
     * Guarded by PM role.
     */
    @Transactional(readOnly = true)
    public PlanProposalResponse getProposal(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID proposalId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        PlanProposal proposal = proposalRepository.findByIdAndProjectId(proposalId, projectId)
                .orElseThrow(() -> PlanProposalException.notFound("Plan proposal not found: " + proposalId));

        return PlanProposalResponse.from(proposal);
    }

    /**
     * Retrieves solver diagnostics for a proposal.
     * Guarded by PM role.
     */
    @Transactional(readOnly = true)
    public SolverDiagnosticsResponse getDiagnostics(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID proposalId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        PlanProposal proposal = proposalRepository.findByIdAndProjectId(proposalId, projectId)
                .orElseThrow(() -> PlanProposalException.notFound("Plan proposal not found: " + proposalId));

        return SolverDiagnosticsResponse.from(proposal);
    }

    /**
     * Lists proposals for a project.
     * Guarded by PM role.
     */
    @Transactional(readOnly = true)
    public List<PlanProposalResponse> listProposals(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        List<PlanProposal> proposals = proposalRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        return proposals.stream().map(PlanProposalResponse::from).toList();
    }

    private SolverInputSnapshot buildSolverInputSnapshot(
            UUID projectId,
            UUID workspaceId,
            Long projectVersion,
            String timezone,
            LocalDate horizonStart,
            LocalDate horizonEnd,
            int maxTimeSeconds,
            Map<String, Object> payload,
            ResourceCapacitySnapshot capacitySnapshot) {

        List<SolverInputSnapshot.TaskItem> tasks = new ArrayList<>();
        Map<String, UUID> tempKeyToId = new HashMap<>();

        if (payload != null && payload.get("items") instanceof List<?> rawItems) {
            for (Object obj : rawItems) {
                if (obj instanceof Map<?, ?> itemMap) {
                    String tempKey = itemMap.get("tempKey") != null ? itemMap.get("tempKey").toString() : null;
                    UUID taskId = null;
                    if (itemMap.get("targetId") != null) {
                        try {
                            taskId = UUID.fromString(itemMap.get("targetId").toString());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    if (taskId == null) {
                        taskId = UUID.randomUUID();
                    }
                    if (tempKey != null) {
                        tempKeyToId.put(tempKey, taskId);
                    }

                    String title = itemMap.get("title") != null ? itemMap.get("title").toString() : (tempKey != null ? tempKey : "Task");
                    int estimateMinutes = 0;
                    if (itemMap.get("estimateMinutes") instanceof Number n) {
                        estimateMinutes = n.intValue();
                    }

                    List<SolverInputSnapshot.SkillRequirement> requiredSkills = new ArrayList<>();
                    if (itemMap.get("requiredSkills") instanceof List<?> rawSkills) {
                        for (Object sObj : rawSkills) {
                            if (sObj instanceof Map<?, ?> skillMap) {
                                String skillName = skillMap.get("skill") != null ? skillMap.get("skill").toString() : null;
                                int level = skillMap.get("level") instanceof Number n ? n.intValue() : 1;
                                if (skillName != null) {
                                    requiredSkills.add(new SolverInputSnapshot.SkillRequirement(skillName, level));
                                }
                            }
                        }
                    }

                    UUID fixedAssigneeId = null;
                    if (itemMap.get("fixedAssigneeId") != null) {
                        try {
                            fixedAssigneeId = UUID.fromString(itemMap.get("fixedAssigneeId").toString());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }

                    LocalDate milestoneDeadline = null;
                    if (itemMap.get("milestoneDeadline") != null) {
                        try {
                            milestoneDeadline = LocalDate.parse(itemMap.get("milestoneDeadline").toString());
                        } catch (Exception ignored) {
                        }
                    }

                    boolean strictMilestone = Boolean.TRUE.equals(itemMap.get("strictMilestone"));

                    tasks.add(new SolverInputSnapshot.TaskItem(
                            taskId,
                            tempKey != null ? tempKey : taskId.toString(),
                            title,
                            estimateMinutes,
                            requiredSkills,
                            fixedAssigneeId,
                            milestoneDeadline,
                            strictMilestone
                    ));
                }
            }
        }

        List<SolverInputSnapshot.TaskDependency> dependencies = new ArrayList<>();
        if (payload != null && payload.get("dependencies") instanceof List<?> rawDeps) {
            for (Object obj : rawDeps) {
                if (obj instanceof Map<?, ?> depMap) {
                    String predKey = depMap.get("predecessorKey") != null ? depMap.get("predecessorKey").toString() : null;
                    String succKey = depMap.get("successorKey") != null ? depMap.get("successorKey").toString() : null;
                    int lag = depMap.get("lagMinutes") instanceof Number n ? n.intValue() : 0;

                    UUID predId = tempKeyToId.get(predKey);
                    UUID succId = tempKeyToId.get(succKey);
                    if (predId != null && succId != null) {
                        dependencies.add(new SolverInputSnapshot.TaskDependency(predId, succId, lag));
                    }
                }
            }
        }

        return new SolverInputSnapshot(
                projectId,
                workspaceId,
                projectVersion,
                timezone,
                horizonStart,
                horizonEnd,
                maxTimeSeconds,
                4,
                42,
                tasks,
                dependencies,
                capacitySnapshot != null ? capacitySnapshot.developers() : List.of(),
                List.of()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applySolutionToPayload(Map<String, Object> originalPayload, SolverResult result) {
        Map<String, Object> updated = new LinkedHashMap<>(originalPayload != null ? originalPayload : Map.of());
        if (!updated.containsKey("schemaVersion")) {
            updated.put("schemaVersion", 1);
        }

        Map<String, SolverResult.TaskAssignment> assignmentMap = new HashMap<>();
        if (result != null && result.assignments() != null) {
            for (SolverResult.TaskAssignment a : result.assignments()) {
                if (a.taskKey() != null) {
                    assignmentMap.put(a.taskKey(), a);
                }
            }
        }

        List<Map<String, Object>> updatedItems = new ArrayList<>();
        if (updated.get("items") instanceof List<?> rawItems) {
            for (Object obj : rawItems) {
                if (obj instanceof Map<?, ?> itemMap) {
                    Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) itemMap);
                    String tempKey = item.get("tempKey") != null ? item.get("tempKey").toString() : null;
                    SolverResult.TaskAssignment assignment = assignmentMap.get(tempKey);
                    if (assignment != null) {
                        item.put("developerId", assignment.developerId());
                        item.put("startAt", assignment.startAt().toString());
                        item.put("finishAt", assignment.finishAt().toString());
                        item.put("scheduledMinutes", assignment.scheduledMinutes());
                        item.put("segments", assignment.segments());
                    }
                    updatedItems.add(item);
                }
            }
        }
        updated.put("items", updatedItems);
        return updated;
    }

    private Long getProjectVersion(UUID projectId) {
        Long v = jdbcTemplate.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
        return v != null ? v : 0L;
    }

    private String getProjectTimezone(UUID projectId) {
        List<String> list = jdbcTemplate.query("SELECT timezone FROM projects WHERE id = ?", (rs, rowNum) -> rs.getString("timezone"), projectId);
        return (list.isEmpty() || list.get(0) == null) ? "UTC" : list.get(0);
    }

    private void setActorId(SessionService.Principal principal) {
        if (principal != null && principal.userId() != null) {
            try {
                jdbcTemplate.execute("SELECT set_config('devsphere.actor_id', '" + principal.userId() + "', true)");
            } catch (Exception ignored) {
            }
        }
    }
}
