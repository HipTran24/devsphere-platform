package com.project.devsphere.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.planning.api.dto.ApplyPlanRequest;
import com.project.devsphere.planning.api.dto.ApplyPlanResponse;
import com.project.devsphere.planning.api.dto.ApprovePlanProposalRequest;
import com.project.devsphere.planning.api.dto.CreatePlanProposalRequest;
import com.project.devsphere.planning.api.dto.DeadlineConfirmationRequest;
import com.project.devsphere.planning.api.dto.DeadlineConsentResponse;
import com.project.devsphere.planning.api.dto.MilestoneImpactDTO;
import com.project.devsphere.planning.api.dto.PlanProposalPreviewResponse;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * Retrieves preview and summary of a proposal including tasks and milestone impacts.
     * Guarded by PM role.
     */
    @Transactional(readOnly = true)
    public PlanProposalPreviewResponse getPreview(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID proposalId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        PlanProposal proposal = proposalRepository.findByIdAndProjectId(proposalId, projectId)
                .orElseThrow(() -> PlanProposalException.notFound("Plan proposal not found: " + proposalId));

        Map<String, Object> payload = proposal.getProposalPayload();
        List<?> items = payload != null && payload.get("items") instanceof List<?> list ? list : List.of();

        int totalTasks = items.size();
        int createdTasksCount = 0;
        int totalEstimateMinutes = 0;
        String timezone = getProjectTimezone(projectId);
        LocalDate calculatedFinish = null;
        Instant maxFinishInstant = null;
        List<PlanProposalPreviewResponse.TaskPreviewItem> previewTasks = new ArrayList<>();

        for (Object obj : items) {
            if (obj instanceof Map<?, ?> rawItem) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) rawItem;
                String tempKey = item.get("tempKey") != null ? item.get("tempKey").toString() : null;
                UUID targetId = null;
                if (item.get("targetId") != null) {
                    try { targetId = UUID.fromString(item.get("targetId").toString()); } catch (Exception ignored) {}
                }
                String title = item.get("title") != null ? item.get("title").toString() : (tempKey != null ? tempKey : "Task");
                int estimate = item.get("estimateMinutes") instanceof Number n ? n.intValue() : 0;
                totalEstimateMinutes += estimate;

                UUID devId = null;
                if (item.get("developerId") != null) {
                    try { devId = UUID.fromString(item.get("developerId").toString()); } catch (Exception ignored) {}
                }
                Instant start = null;
                if (item.get("startAt") != null) {
                    try { start = Instant.parse(item.get("startAt").toString()); } catch (Exception ignored) {}
                }
                Instant finish = null;
                if (item.get("finishAt") != null) {
                    try {
                        finish = Instant.parse(item.get("finishAt").toString());
                        LocalDate date = finish.atZone(ZoneId.of(timezone)).toLocalDate();
                        if (calculatedFinish == null || date.isAfter(calculatedFinish)) {
                            calculatedFinish = date;
                        }
                        if (maxFinishInstant == null || finish.isAfter(maxFinishInstant)) {
                            maxFinishInstant = finish;
                        }
                    } catch (Exception ignored) {}
                }

                previewTasks.add(new PlanProposalPreviewResponse.TaskPreviewItem(
                        tempKey, targetId, title, estimate, devId, start, finish
                ));
            }
        }

        LocalDate plannedFinish = calculatedFinish != null ? calculatedFinish : proposal.getProposedCommittedDeadline();

        List<LocalDate> deadList = jdbcTemplate.query(
                "SELECT committed_deadline FROM projects WHERE id = ?",
                (rs, rowNum) -> {
                    java.sql.Date d = rs.getDate("committed_deadline");
                    return d != null ? d.toLocalDate() : null;
                },
                projectId
        );
        LocalDate currentCommittedDeadline = deadList.isEmpty() ? null : deadList.get(0);

        List<MilestoneImpactDTO> milestoneImpacts = getMilestoneImpacts(projectId, plannedFinish);

        return new PlanProposalPreviewResponse(
                proposal.getId(),
                proposal.getProjectId(),
                proposal.getVersion(),
                proposal.getBaseProjectVersion(),
                proposal.getSolverStatus().name(),
                items.size(),
                totalEstimateMinutes,
                maxFinishInstant,
                proposal.getChangesCommittedDeadline(),
                currentCommittedDeadline,
                proposal.getProposedCommittedDeadline(),
                previewTasks,
                milestoneImpacts
        );
    }

    /**
     * Approves a plan proposal.
     * Guarded by PM role.
     */
    public PlanProposalResponse approveProposal(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID proposalId,
            ApprovePlanProposalRequest request) {

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

        if (proposal.getStatus() == PlanProposal.Status.APPLIED) {
            throw PlanProposalException.proposalAlreadyApplied("Proposal is already applied");
        }

        if (!Objects.equals(proposal.getVersion(), request.expectedProposalVersion())) {
            throw PlanProposalException.versionConflict("Proposal version conflict. Expected: " + request.expectedProposalVersion() + ", actual: " + proposal.getVersion());
        }

        if (proposal.getSolverStatus() != PlanProposal.SolverStatus.OPTIMAL && proposal.getSolverStatus() != PlanProposal.SolverStatus.FEASIBLE) {
            throw PlanProposalException.proposalNotSolved("Cannot approve proposal with solver status " + proposal.getSolverStatus());
        }

        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE plan_proposals
                SET status = 'APPROVED',
                    approved_by = ?,
                    approved_at = ?
                WHERE id = ? AND project_id = ? AND version = ?
                """,
                principal.userId(),
                Timestamp.from(now),
                proposalId,
                projectId,
                proposal.getVersion()
        );

        entityManager.clear();
        PlanProposal updated = proposalRepository.findByIdAndProjectId(proposalId, projectId)
                .orElseThrow(() -> PlanProposalException.notFound("Plan proposal not found: " + proposalId));

        return PlanProposalResponse.from(updated);
    }

    /**
     * Retrieves safe client projection of deadline consent without disclosing internal tasks or capacities.
     * Accessible by designated CLIENT or PM.
     */
    @Transactional(readOnly = true)
    public DeadlineConsentResponse getDeadlineConsent(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID proposalId) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.CLIENT, ProjectRole.PM));

        PlanProposal proposal = proposalRepository.findByIdAndProjectId(proposalId, projectId)
                .orElseThrow(() -> PlanProposalException.notFound("Plan proposal not found: " + proposalId));

        LocalDate currentCommittedDeadline = jdbcTemplate.queryForObject(
                "SELECT committed_deadline FROM projects WHERE id = ?",
                LocalDate.class,
                projectId
        );
        UUID deadlineApproverId = getProjectDeadlineApproverId(projectId);
        boolean isDesignatedApprover = Objects.equals(principal.userId(), deadlineApproverId);

        List<MilestoneImpactDTO> milestoneImpacts = getMilestoneImpacts(projectId, proposal.getProposedCommittedDeadline());

        return new DeadlineConsentResponse(
                proposal.getId(),
                proposal.getProjectId(),
                proposal.getChangesCommittedDeadline(),
                currentCommittedDeadline,
                proposal.getProposedCommittedDeadline(),
                deadlineApproverId,
                isDesignatedApprover,
                proposal.getVersion(),
                proposal.getBaseProjectVersion(),
                proposal.getDeadlineConfirmedBy() != null,
                proposal.getDeadlineConfirmedBy(),
                proposal.getDeadlineConfirmedAt(),
                milestoneImpacts
        );
    }

    /**
     * Confirms proposed deadline by designated independent CLIENT.
     * Enforces author-approver independence and binds deadline confirmation version.
     */
    public DeadlineConsentResponse confirmDeadline(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID proposalId,
            DeadlineConfirmationRequest request) {

        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.CLIENT);

        if (request == null || !Boolean.TRUE.equals(request.confirmation())) {
            throw PlanProposalException.validationFailed("Confirmation must be explicitly granted");
        }

        Long actualProjectVersion = getProjectVersion(projectId);
        if (!Objects.equals(actualProjectVersion, request.expectedProjectVersion())) {
            throw PlanProposalException.planStale("Project version is stale. Expected: " + request.expectedProjectVersion() + ", actual: " + actualProjectVersion);
        }

        PlanProposal proposal = proposalRepository.findByIdAndProjectId(proposalId, projectId)
                .orElseThrow(() -> PlanProposalException.notFound("Plan proposal not found: " + proposalId));

        if (!Objects.equals(proposal.getVersion(), request.expectedProposalVersion())) {
            throw PlanProposalException.versionConflict("Proposal version conflict. Expected: " + request.expectedProposalVersion() + ", actual: " + proposal.getVersion());
        }

        if (proposal.getStatus() != PlanProposal.Status.APPROVED) {
            throw PlanProposalException.proposalNotApproved("Proposal must be APPROVED before deadline confirmation");
        }

        if (Objects.equals(principal.userId(), proposal.getApprovedBy())) {
            throw PlanProposalException.independentClientRequired("Deadline confirmation requires independent CLIENT different from PM approver");
        }

        UUID deadlineApproverId = getProjectDeadlineApproverId(projectId);
        if (!Objects.equals(principal.userId(), deadlineApproverId)) {
            throw PlanProposalException.invalidDeadlineApprover("User is not the designated deadline approver for this project");
        }

        Instant now = Instant.now();
        Long nextConfirmationVersion = proposal.getVersion() + 1;

        jdbcTemplate.update("""
                UPDATE plan_proposals
                SET deadline_confirmed_by = ?,
                    deadline_confirmed_at = ?,
                    deadline_confirmed_project_version = ?,
                    deadline_confirmation_version = ?
                WHERE id = ? AND project_id = ? AND version = ?
                """,
                principal.userId(),
                Timestamp.from(now),
                proposal.getBaseProjectVersion(),
                nextConfirmationVersion,
                proposalId,
                projectId,
                proposal.getVersion()
        );

        entityManager.clear();
        PlanProposal updated = proposalRepository.findByIdAndProjectId(proposalId, projectId)
                .orElseThrow(() -> PlanProposalException.notFound("Plan proposal not found: " + proposalId));

        LocalDate currentCommittedDeadline = jdbcTemplate.queryForObject(
                "SELECT committed_deadline FROM projects WHERE id = ?",
                LocalDate.class,
                projectId
        );
        List<MilestoneImpactDTO> milestoneImpacts = getMilestoneImpacts(projectId, updated.getProposedCommittedDeadline());

        return new DeadlineConsentResponse(
                updated.getId(),
                updated.getProjectId(),
                updated.getChangesCommittedDeadline(),
                currentCommittedDeadline,
                updated.getProposedCommittedDeadline(),
                deadlineApproverId,
                true,
                updated.getVersion(),
                updated.getBaseProjectVersion(),
                true,
                updated.getDeadlineConfirmedBy(),
                updated.getDeadlineConfirmedAt(),
                milestoneImpacts
        );
    }

    /**
     * Atomically applies an approved plan proposal:
     * - Materializes work items and dependencies
     * - Copies frozen/DONE schedule entries unchanged to satisfy ds_schedule_freeze
     * - Inserts new ScheduleVersion and ScheduleEntries
     * - Updates plan_proposals to APPLIED
     * - Updates projects active_schedule_version_id and committed_deadline
     */
    public ApplyPlanResponse applyProposal(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID proposalId,
            ApplyPlanRequest request) {

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

        if (proposal.getStatus() == PlanProposal.Status.APPLIED) {
            throw PlanProposalException.proposalAlreadyApplied("Proposal is already applied");
        }

        if (proposal.getStatus() != PlanProposal.Status.APPROVED) {
            throw PlanProposalException.proposalNotApproved("Proposal must be APPROVED before applying");
        }

        if (!Objects.equals(proposal.getVersion(), request.expectedProposalVersion())) {
            throw PlanProposalException.versionConflict("Proposal version conflict. Expected: " + request.expectedProposalVersion() + ", actual: " + proposal.getVersion());
        }

        if (Boolean.TRUE.equals(proposal.getChangesCommittedDeadline())) {
            if (proposal.getDeadlineConfirmedBy() == null || proposal.getDeadlineConfirmedAt() == null) {
                throw PlanProposalException.clientDeadlineConfirmationRequired("Proposal changes committed deadline but lacks client deadline confirmation");
            }
        }

        Map<String, Object> payload = proposal.getProposalPayload();
        List<?> items = payload != null && payload.get("items") instanceof List<?> list ? list : List.of();
        List<?> dependencies = payload != null && payload.get("dependencies") instanceof List<?> list ? list : List.of();

        UUID activeScheduleVersionId = jdbcTemplate.queryForObject(
                "SELECT active_schedule_version_id FROM projects WHERE id = ?",
                UUID.class,
                projectId
        );

        Map<UUID, OldScheduleEntry> activeOldEntries = new HashMap<>();
        if (activeScheduleVersionId != null) {
            List<OldScheduleEntry> oldList = jdbcTemplate.query("""
                    SELECT e.work_item_id, e.sprint_id, e.assignee_id, e.start_at, e.finish_at,
                           e.remaining_minutes_snapshot, e.frozen, w.status as task_status
                    FROM schedule_entries e
                    JOIN work_items w ON w.id = e.work_item_id
                    WHERE e.schedule_version_id = ?
                    """,
                    (rs, rowNum) -> new OldScheduleEntry(
                            UUID.fromString(rs.getString("work_item_id")),
                            rs.getString("sprint_id") != null ? UUID.fromString(rs.getString("sprint_id")) : null,
                            rs.getString("assignee_id") != null ? UUID.fromString(rs.getString("assignee_id")) : null,
                            rs.getTimestamp("start_at").toInstant(),
                            rs.getTimestamp("finish_at").toInstant(),
                            rs.getInt("remaining_minutes_snapshot"),
                            rs.getBoolean("frozen"),
                            rs.getString("task_status")
                    ),
                    activeScheduleVersionId
            );
            for (OldScheduleEntry ose : oldList) {
                activeOldEntries.put(ose.workItemId(), ose);
            }
        }

        Map<String, UUID> tempKeyToWorkItemId = new HashMap<>();
        int createdCount = 0;
        int updatedCount = 0;
        Instant now = Instant.now();

        UUID requirementId = null;
        if (proposal.getRequirementVersionId() != null) {
            List<UUID> reqIds = jdbcTemplate.query(
                    "SELECT requirement_id FROM requirement_versions WHERE id = ?",
                    (rs, rowNum) -> UUID.fromString(rs.getString("requirement_id")),
                    proposal.getRequirementVersionId()
            );
            if (!reqIds.isEmpty()) {
                requirementId = reqIds.get(0);
            }
        } else if (proposal.getChangeRequestId() != null) {
            requirementId = proposal.getChangeRequestId();
        }

        for (Object itemObj : items) {
            if (!(itemObj instanceof Map<?, ?> itemMap)) continue;
            String tempKey = itemMap.get("tempKey") != null ? itemMap.get("tempKey").toString() : null;
            String targetIdStr = itemMap.get("targetId") != null ? itemMap.get("targetId").toString() : null;

            UUID workItemId;
            if (targetIdStr != null && !targetIdStr.isBlank()) {
                workItemId = UUID.fromString(targetIdStr);
                if (tempKey != null) tempKeyToWorkItemId.put(tempKey, workItemId);

                String currentStatus = jdbcTemplate.queryForObject("SELECT status FROM work_items WHERE id = ?", String.class, workItemId);
                if (!"DONE".equals(currentStatus)) {
                    UUID assigneeId = itemMap.get("developerId") != null ? UUID.fromString(itemMap.get("developerId").toString()) : null;
                    Instant finishAt = itemMap.get("finishAt") != null ? Instant.parse(itemMap.get("finishAt").toString()) : null;
                    Integer remainingMinutes = itemMap.get("remainingMinutes") instanceof Number n ? n.intValue() : null;

                    if (assigneeId != null) {
                        jdbcTemplate.update("UPDATE work_items SET assignee_id = ?, updated_at = now() WHERE id = ?", assigneeId, workItemId);
                    }
                    if (finishAt != null) {
                        jdbcTemplate.update("UPDATE work_items SET due_at = ?, updated_at = now() WHERE id = ?", Timestamp.from(finishAt), workItemId);
                    }
                    if (remainingMinutes != null) {
                        jdbcTemplate.update("UPDATE work_items SET remaining_minutes = ?, updated_at = now() WHERE id = ?", remainingMinutes, workItemId);
                    }
                    updatedCount++;
                }
            } else {
                workItemId = UUID.randomUUID();
                if (tempKey != null) tempKeyToWorkItemId.put(tempKey, workItemId);

                String title = itemMap.get("title") != null ? itemMap.get("title").toString() : (tempKey != null ? tempKey : "New Task");
                String description = itemMap.get("description") != null ? itemMap.get("description").toString() : "";
                String type = itemMap.get("type") != null ? itemMap.get("type").toString() : "TASK";
                String workKind = itemMap.get("workKind") != null ? itemMap.get("workKind").toString() : "CODE";
                int estimateMinutes = itemMap.get("estimateMinutes") instanceof Number n ? n.intValue() : 0;
                int remainingMinutes = itemMap.get("remainingMinutes") instanceof Number n ? n.intValue() : estimateMinutes;
                UUID assigneeId = itemMap.get("developerId") != null ? UUID.fromString(itemMap.get("developerId").toString()) : null;
                Instant finishAt = itemMap.get("finishAt") != null ? Instant.parse(itemMap.get("finishAt").toString()) : null;

                jdbcTemplate.update("""
                        INSERT INTO work_items (
                            id, project_id, requirement_id, type, work_kind, title, description,
                            status, estimate_minutes, remaining_minutes, assignee_id, due_at,
                            created_at, updated_at, version
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, ?,
                            'TO_DO', ?, ?, ?, ?,
                            now(), now(), 0
                        )
                        """,
                        workItemId, projectId, requirementId, type, workKind, title, description,
                        estimateMinutes, remainingMinutes, assigneeId, finishAt != null ? Timestamp.from(finishAt) : null
                );
                createdCount++;
            }
        }

        for (Object depObj : dependencies) {
            if (!(depObj instanceof Map<?, ?> depMap)) continue;
            String predKey = depMap.get("predecessorKey") != null ? depMap.get("predecessorKey").toString() : null;
            String succKey = depMap.get("successorKey") != null ? depMap.get("successorKey").toString() : null;
            int lagMinutes = depMap.get("lagMinutes") instanceof Number n ? n.intValue() : 0;

            UUID predId = tempKeyToWorkItemId.get(predKey);
            UUID succId = tempKeyToWorkItemId.get(succKey);
            if (predId != null && succId != null) {
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM work_item_dependencies WHERE predecessor_id = ? AND successor_id = ?",
                        Integer.class, predId, succId
                );
                if (exists == null || exists == 0) {
                    jdbcTemplate.update("""
                            INSERT INTO work_item_dependencies (id, project_id, predecessor_id, successor_id, link_type, lag_minutes, created_at)
                            VALUES (gen_random_uuid(), ?, ?, ?, 'FINISH_TO_START', ?, now())
                            """,
                            projectId, predId, succId, lagMinutes
                    );
                }
            }
        }

        // Step 1: Update plan_proposals to APPLIED FIRST
        jdbcTemplate.update("""
                UPDATE plan_proposals
                SET status = 'APPLIED'
                WHERE id = ? AND project_id = ? AND version = ?
                """,
                proposalId, projectId, proposal.getVersion()
        );

        // Step 2: Create schedule_versions
        UUID newScheduleVersionId = UUID.randomUUID();
        Integer nextVersionNo = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) + 1 FROM schedule_versions WHERE project_id = ?",
                Integer.class, projectId
        );
        String source = proposal.getKind() == PlanProposal.Kind.AI_REQUIREMENT ? "AI_PLAN"
                : (proposal.getKind() == PlanProposal.Kind.AUTO_REPLAN ? "RESCHEDULE" : "MANUAL");
        Instant approvedAt = proposal.getApprovedAt() != null ? proposal.getApprovedAt() : now;
        LocalDate plannedFinish = proposal.getProposedCommittedDeadline();
        LocalDate forecastFinish = proposal.getProposedCommittedDeadline();

        String baselineSnapshotJson;
        String milestoneDelaysJson;
        try {
            baselineSnapshotJson = objectMapper.writeValueAsString(payload != null ? payload : Map.of("schemaVersion", 1));
            milestoneDelaysJson = objectMapper.writeValueAsString(proposal.getSolverDetails() != null ? proposal.getSolverDetails() : Map.of("schemaVersion", 1));
        } catch (JsonProcessingException e) {
            baselineSnapshotJson = "{\"schemaVersion\":1}";
            milestoneDelaysJson = "{\"schemaVersion\":1}";
        }

        jdbcTemplate.update("""
                INSERT INTO schedule_versions (
                    id, project_id, version_no, source, proposal_id, approved_by, approved_at,
                    planned_finish, forecast_finish, milestone_delays, baseline_snapshot, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?::jsonb, ?::jsonb, now()
                )
                """,
                newScheduleVersionId, projectId, nextVersionNo, source, proposalId, proposal.getApprovedBy(),
                Timestamp.from(approvedAt), plannedFinish, forecastFinish, milestoneDelaysJson, baselineSnapshotJson
        );

        // Step 3: Insert schedule_entries
        Set<UUID> handledWorkItemIds = new HashSet<>();
        for (Object itemObj : items) {
            if (!(itemObj instanceof Map<?, ?> itemMap)) continue;
            String tempKey = itemMap.get("tempKey") != null ? itemMap.get("tempKey").toString() : null;
            UUID workItemId = tempKeyToWorkItemId.get(tempKey);
            if (workItemId == null) continue;
            handledWorkItemIds.add(workItemId);

            OldScheduleEntry oldEntry = activeOldEntries.get(workItemId);
            if (oldEntry != null && (oldEntry.frozen() || "DONE".equals(oldEntry.taskStatus()))) {
                jdbcTemplate.update("""
                        INSERT INTO schedule_entries (
                            id, project_id, schedule_version_id, work_item_id, sprint_id,
                            assignee_id, start_at, finish_at, remaining_minutes_snapshot, frozen, created_at
                        ) VALUES (
                            gen_random_uuid(), ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, now()
                        )
                        """,
                        projectId, newScheduleVersionId, workItemId, oldEntry.sprintId(),
                        oldEntry.assigneeId(), Timestamp.from(oldEntry.startAt()), Timestamp.from(oldEntry.finishAt()),
                        oldEntry.remainingMinutesSnapshot(), oldEntry.frozen()
                );
            } else {
                UUID assigneeId = itemMap.get("developerId") != null ? UUID.fromString(itemMap.get("developerId").toString()) : null;
                UUID sprintId = itemMap.get("sprintId") != null ? UUID.fromString(itemMap.get("sprintId").toString()) : null;
                Instant startAt = itemMap.get("startAt") != null ? Instant.parse(itemMap.get("startAt").toString()) : now;
                Instant finishAt = itemMap.get("finishAt") != null ? Instant.parse(itemMap.get("finishAt").toString()) : now.plusSeconds(7200);
                int remMinutes = itemMap.get("remainingMinutes") instanceof Number n ? n.intValue()
                        : (itemMap.get("estimateMinutes") instanceof Number em ? em.intValue() : 0);
                boolean frozen = Boolean.TRUE.equals(itemMap.get("frozen"));

                jdbcTemplate.update("""
                        INSERT INTO schedule_entries (
                            id, project_id, schedule_version_id, work_item_id, sprint_id,
                            assignee_id, start_at, finish_at, remaining_minutes_snapshot, frozen, created_at
                        ) VALUES (
                            gen_random_uuid(), ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, now()
                        )
                        """,
                        projectId, newScheduleVersionId, workItemId, sprintId,
                        assigneeId, Timestamp.from(startAt), Timestamp.from(finishAt),
                        remMinutes, frozen
                );
            }
        }

        for (OldScheduleEntry oldEntry : activeOldEntries.values()) {
            if (!handledWorkItemIds.contains(oldEntry.workItemId()) && (oldEntry.frozen() || "DONE".equals(oldEntry.taskStatus()))) {
                jdbcTemplate.update("""
                        INSERT INTO schedule_entries (
                            id, project_id, schedule_version_id, work_item_id, sprint_id,
                            assignee_id, start_at, finish_at, remaining_minutes_snapshot, frozen, created_at
                        ) VALUES (
                            gen_random_uuid(), ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, now()
                        )
                        """,
                        projectId, newScheduleVersionId, oldEntry.workItemId(), oldEntry.sprintId(),
                        oldEntry.assigneeId(), Timestamp.from(oldEntry.startAt()), Timestamp.from(oldEntry.finishAt()),
                        oldEntry.remainingMinutesSnapshot(), oldEntry.frozen()
                );
            }
        }

        // Step 4: Update projects active_schedule_version_id and committed_deadline
        LocalDate finalCommittedDeadline;
        if (Boolean.TRUE.equals(proposal.getChangesCommittedDeadline())) {
            finalCommittedDeadline = proposal.getProposedCommittedDeadline();
            jdbcTemplate.update("""
                    UPDATE projects
                    SET active_schedule_version_id = ?,
                        committed_deadline = ?
                    WHERE id = ?
                    """,
                    newScheduleVersionId, finalCommittedDeadline, projectId
            );
        } else {
            finalCommittedDeadline = jdbcTemplate.queryForObject(
                    "SELECT committed_deadline FROM projects WHERE id = ?",
                    LocalDate.class,
                    projectId
            );
            jdbcTemplate.update("""
                    UPDATE projects
                    SET active_schedule_version_id = ?
                    WHERE id = ?
                    """,
                    newScheduleVersionId, projectId
            );
        }

        entityManager.clear();

        return new ApplyPlanResponse(
                proposalId,
                newScheduleVersionId,
                nextVersionNo,
                createdCount,
                updatedCount,
                finalCommittedDeadline,
                now
        );
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

    private UUID getProjectDeadlineApproverId(UUID projectId) {
        List<UUID> list = jdbcTemplate.query("SELECT deadline_approver_id FROM projects WHERE id = ?", (rs, rowNum) -> {
            String s = rs.getString("deadline_approver_id");
            return s != null ? UUID.fromString(s) : null;
        }, projectId);
        return list.isEmpty() ? null : list.get(0);
    }

    private List<MilestoneImpactDTO> getMilestoneImpacts(UUID projectId, LocalDate referenceDate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, due_date FROM milestones WHERE project_id = ? ORDER BY due_date ASC",
                projectId
        );
        List<MilestoneImpactDTO> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID mId = (UUID) r.get("id");
            String name = (String) r.get("name");
            LocalDate targetDate = r.get("due_date") != null ? ((java.sql.Date) r.get("due_date")).toLocalDate() : null;
            Integer delayDays = 0;
            if (targetDate != null && referenceDate != null && referenceDate.isAfter(targetDate)) {
                delayDays = (int) ChronoUnit.DAYS.between(targetDate, referenceDate);
            }
            list.add(new MilestoneImpactDTO(mId, name, targetDate, delayDays));
        }
        return list;
    }

    private static final class OldScheduleEntry {
        private final UUID workItemId;
        private final UUID sprintId;
        private final UUID assigneeId;
        private final Instant startAt;
        private final Instant finishAt;
        private final int remainingMinutesSnapshot;
        private final boolean frozen;
        private final String taskStatus;

        public OldScheduleEntry(
                UUID workItemId,
                UUID sprintId,
                UUID assigneeId,
                Instant startAt,
                Instant finishAt,
                int remainingMinutesSnapshot,
                boolean frozen,
                String taskStatus) {
            this.workItemId = workItemId;
            this.sprintId = sprintId;
            this.assigneeId = assigneeId;
            this.startAt = startAt;
            this.finishAt = finishAt;
            this.remainingMinutesSnapshot = remainingMinutesSnapshot;
            this.frozen = frozen;
            this.taskStatus = taskStatus;
        }

        public UUID workItemId() { return workItemId; }
        public UUID sprintId() { return sprintId; }
        public UUID assigneeId() { return assigneeId; }
        public Instant startAt() { return startAt; }
        public Instant finishAt() { return finishAt; }
        public int remainingMinutesSnapshot() { return remainingMinutesSnapshot; }
        public boolean frozen() { return frozen; }
        public String taskStatus() { return taskStatus; }
    }
}
