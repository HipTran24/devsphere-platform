"use client";

import { useState, useEffect, useCallback, useMemo } from "react";
import {
  fetchProjects,
  fetchWorkItems,
  fetchDependencies,
  fetchSprints,
  fetchPlanProposals,
  previewPlanProposal,
  approvePlanProposal,
  confirmDeadline,
  applyPlanProposal,
  isMockAuthEnabled,
} from "../../../lib/api-client";
import {
  DEMO_PLANNING_TASKS,
  DEMO_DEPENDENCIES,
  DEMO_MILESTONES,
  DEMO_PROPOSALS,
} from "../demo-data";
import { DEMO_PROJECT, DEMO_USERS } from "../../backlog/demo-data";
import { DEMO_SPRINTS } from "../../sprints/demo-data";
import {
  calculateCriticalPath,
  calculateTimelineBounds,
  generateTimelineIntervals,
  calculateScheduleDiff,
  evaluateDeadlineGating,
} from "../utils/planning-utils";
import { useAuth } from "../../auth/components/AuthProvider";

export function usePlanningData({ projectKey, workspaceId, initialProposalId } = {}) {
  const authContext = useAuth();
  const currentUser = authContext?.session?.user || null;

  const [project, setProject] = useState(null);
  const [tasks, setTasks] = useState([]);
  const [dependencies, setDependencies] = useState([]);
  const [milestones, setMilestones] = useState([]);
  const [sprints, setSprints] = useState([]);
  const [proposals, setProposals] = useState([]);
  const [activeProposalId, setActiveProposalId] = useState(initialProposalId || null);
  const [preview, setPreview] = useState(null);
  const [deadlineConsent, setDeadlineConsent] = useState(null);

  // View state controls
  const [viewMode, setViewMode] = useState("DAYS"); // 'DAYS' | 'WEEKS' | 'SPRINTS'
  const [criticalOnly, setCriticalOnly] = useState(false);
  const [zoom, setZoom] = useState("normal"); // 'compact' | 'normal' | 'detailed'

  // Async and modal state
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [actionSuccess, setActionSuccess] = useState(null);
  const [confirmDialogVisible, setConfirmDialogVisible] = useState(false);

  // Role override for PM testing
  const [simulatedRole, setSimulatedRole] = useState(null);

  const effectiveUser = useMemo(() => {
    if (simulatedRole) {
      return {
        ...(currentUser || DEMO_USERS[1]),
        role: simulatedRole,
        systemRole: simulatedRole,
        email: simulatedRole === "PM" ? "pm@devsphere.io" : `${simulatedRole.toLowerCase()}@devsphere.io`,
      };
    }
    return currentUser || DEMO_USERS[1];
  }, [currentUser, simulatedRole]);

  // Active Proposal
  const activeProposal = useMemo(() => {
    if (!proposals.length) return null;
    if (activeProposalId) {
      const found = proposals.find((p) => p.id === activeProposalId);
      if (found) return found;
    }
    return proposals[0];
  }, [proposals, activeProposalId]);

  // CPM calculation on tasks + dependencies
  const cpmResult = useMemo(() => {
    return calculateCriticalPath(tasks, dependencies);
  }, [tasks, dependencies]);

  // Display tasks (filtered by criticalOnly if enabled)
  const displayTasks = useMemo(() => {
    if (!criticalOnly) return cpmResult.tasks;
    return cpmResult.tasks.filter((t) => t.isCritical);
  }, [cpmResult.tasks, criticalOnly]);

  // Timeline bounds and intervals
  const timelineBounds = useMemo(() => {
    return calculateTimelineBounds(tasks, milestones, sprints);
  }, [tasks, milestones, sprints]);

  const timelineIntervals = useMemo(() => {
    return generateTimelineIntervals(timelineBounds.minDate, timelineBounds.maxDate, viewMode);
  }, [timelineBounds.minDate, timelineBounds.maxDate, viewMode]);

  // Schedule Diff between baseline tasks and proposal
  const scheduleDiff = useMemo(() => {
    const proposedTasks = preview?.tasks || activeProposal?.tasks || [];
    return calculateScheduleDiff(tasks, proposedTasks);
  }, [tasks, preview, activeProposal]);

  // Deadline gating evaluation
  const deadlineGating = useMemo(() => {
    const targetProposal = preview || activeProposal || {};
    return evaluateDeadlineGating(targetProposal, deadlineConsent);
  }, [preview, activeProposal, deadlineConsent]);

  // Main data loader
  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);

    const isMock = isMockAuthEnabled();

    if (isMock) {
      const mockProject = {
        ...DEMO_PROJECT,
        projectKey: projectKey || DEMO_PROJECT.projectKey,
        workspaceId: workspaceId || DEMO_PROJECT.workspaceId,
        committedDeadline: "2026-12-15",
      };
      setProject(mockProject);
      setTasks(DEMO_PLANNING_TASKS);
      setDependencies(DEMO_DEPENDENCIES);
      setMilestones(DEMO_MILESTONES);
      setSprints(DEMO_SPRINTS);
      setProposals(DEMO_PROPOSALS);

      const targetProp = initialProposalId
        ? DEMO_PROPOSALS.find((p) => p.id === initialProposalId) || DEMO_PROPOSALS[0]
        : DEMO_PROPOSALS[0];

      setActiveProposalId(targetProp?.id || null);
      setPreview(targetProp);
      setDeadlineConsent({
        proposalId: targetProp?.id,
        confirmed: false,
        changesCommittedDeadline: targetProp?.changesCommittedDeadline || false,
      });

      setLoading(false);
      return;
    }

    try {
      const targetWorkspaceId = workspaceId || "devsphere-core";
      const projects = await fetchProjects(targetWorkspaceId);
      const foundProject = Array.isArray(projects)
        ? projects.find((p) => p.projectKey === (projectKey || "DSP")) || projects[0]
        : null;

      const resolvedProjectId = foundProject?.id || "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
      setProject(
        foundProject || {
          ...DEMO_PROJECT,
          projectKey: projectKey || "DSP",
          workspaceId: targetWorkspaceId,
          committedDeadline: "2026-12-15",
        }
      );

      const [itemsRes, depsRes, sprintsRes, proposalsRes] = await Promise.all([
        fetchWorkItems(targetWorkspaceId, resolvedProjectId).catch(() => DEMO_PLANNING_TASKS),
        fetchDependencies(targetWorkspaceId, resolvedProjectId).catch(() => DEMO_DEPENDENCIES),
        fetchSprints(targetWorkspaceId, resolvedProjectId).catch(() => DEMO_SPRINTS),
        fetchPlanProposals(targetWorkspaceId, resolvedProjectId).catch(() => DEMO_PROPOSALS),
      ]);

      setTasks(Array.isArray(itemsRes) && itemsRes.length ? itemsRes : DEMO_PLANNING_TASKS);
      setDependencies(Array.isArray(depsRes) && depsRes.length ? depsRes : DEMO_DEPENDENCIES);
      setMilestones(DEMO_MILESTONES);
      setSprints(Array.isArray(sprintsRes) && sprintsRes.length ? sprintsRes : DEMO_SPRINTS);

      const resolvedProposals = Array.isArray(proposalsRes) && proposalsRes.length ? proposalsRes : DEMO_PROPOSALS;
      setProposals(resolvedProposals);

      const targetProp = initialProposalId
        ? resolvedProposals.find((p) => p.id === initialProposalId) || resolvedProposals[0]
        : resolvedProposals[0];

      setActiveProposalId(targetProp?.id || null);

      if (targetProp?.id) {
        try {
          const prevRes = await previewPlanProposal(targetWorkspaceId, resolvedProjectId, targetProp.id);
          setPreview(prevRes || targetProp);
        } catch {
          setPreview(targetProp);
        }
      }
    } catch (err) {
      setError(err);
      // Fallback to demo data on error so UI never crashes
      setProject({
        ...DEMO_PROJECT,
        projectKey: projectKey || DEMO_PROJECT.projectKey,
        workspaceId: workspaceId || DEMO_PROJECT.workspaceId,
        committedDeadline: "2026-12-15",
      });
      setTasks(DEMO_PLANNING_TASKS);
      setDependencies(DEMO_DEPENDENCIES);
      setMilestones(DEMO_MILESTONES);
      setSprints(DEMO_SPRINTS);
      setProposals(DEMO_PROPOSALS);
      setPreview(DEMO_PROPOSALS[0]);
    } finally {
      setLoading(false);
    }
  }, [projectKey, workspaceId, initialProposalId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Switch proposal selection
  const selectProposal = useCallback(
    async (propId) => {
      setActiveProposalId(propId);
      const found = proposals.find((p) => p.id === propId);
      if (!found) return;

      const isMock = isMockAuthEnabled();
      if (isMock) {
        setPreview(found);
        setDeadlineConsent({
          proposalId: found.id,
          confirmed: false,
          changesCommittedDeadline: found.changesCommittedDeadline,
        });
        return;
      }

      const targetWorkspaceId = project?.workspaceId || workspaceId || "devsphere-core";
      const targetProjectId = project?.id || "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

      try {
        const prevRes = await previewPlanProposal(targetWorkspaceId, targetProjectId, propId);
        setPreview(prevRes || found);
      } catch {
        setPreview(found);
      }
    },
    [proposals, project, workspaceId]
  );

  // Approve Proposal
  const handleApproveProposal = useCallback(
    async ({ notes } = {}) => {
      if (!activeProposal) return;
      setActionLoading(true);
      setActionSuccess(null);
      setError(null);

      const isMock = isMockAuthEnabled();
      if (isMock) {
        setProposals((prev) =>
          prev.map((p) =>
            p.id === activeProposal.id
              ? { ...p, status: "APPROVED", approvedAt: new Date().toISOString(), approvedBy: effectiveUser.id }
              : p
          )
        );
        setActionSuccess(`Proposal ${activeProposal.id.slice(0, 8)} successfully approved.`);
        setActionLoading(false);
        return;
      }

      const targetWorkspaceId = project?.workspaceId || workspaceId || "devsphere-core";
      const targetProjectId = project?.id || "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

      try {
        await approvePlanProposal(targetWorkspaceId, targetProjectId, activeProposal.id, {
          expectedProjectVersion: project?.version || 1,
          expectedProposalVersion: activeProposal.version || 1,
          notes,
        });
        setActionSuccess(`Proposal ${activeProposal.id.slice(0, 8)} successfully approved.`);
        await loadData();
      } catch (err) {
        setError(err);
      } finally {
        setActionLoading(false);
      }
    },
    [activeProposal, project, workspaceId, effectiveUser, loadData]
  );

  // Confirm Deadline (for slipping schedules)
  const handleConfirmDeadline = useCallback(
    async ({ notes, rationale } = {}) => {
      if (!activeProposal) return;
      setActionLoading(true);
      setActionSuccess(null);
      setError(null);

      const isMock = isMockAuthEnabled();
      if (isMock) {
        setDeadlineConsent({
          proposalId: activeProposal.id,
          confirmed: true,
          confirmedBy: effectiveUser.id,
          confirmedAt: new Date().toISOString(),
          changesCommittedDeadline: true,
        });
        setActionSuccess("Committed deadline shift confirmed by client/PM.");
        setActionLoading(false);
        return true;
      }

      const targetWorkspaceId = project?.workspaceId || workspaceId || "devsphere-core";
      const targetProjectId = project?.id || "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

      try {
        const consent = await confirmDeadline(targetWorkspaceId, targetProjectId, activeProposal.id, {
          expectedProposalVersion: activeProposal.version || 1,
          expectedProjectVersion: project?.version || 1,
          confirmation: true,
          rationale: rationale || notes,
          confirmedByRole: effectiveUser?.role || "PM",
        });
        setDeadlineConsent(consent);
        setActionSuccess("Committed deadline shift confirmed by client/PM.");
        return true;
      } catch (err) {
        setError(err);
        return false;
      } finally {
        setActionLoading(false);
      }
    },
    [activeProposal, project, workspaceId, effectiveUser]
  );

  // Apply Plan into active schedule
  const handleApplyPlan = useCallback(
    async ({ applyMode = "ALL" } = {}) => {
      if (!activeProposal) return;
      setActionLoading(true);
      setActionSuccess(null);
      setError(null);

      const isMock = isMockAuthEnabled();
      if (isMock) {
        setProposals((prev) =>
          prev.map((p) =>
            p.id === activeProposal.id
              ? { ...p, status: "APPLIED", appliedAt: new Date().toISOString() }
              : p
          )
        );
        // Update task schedule with proposed dates
        const proposedMap = new Map();
        (preview?.tasks || activeProposal.tasks || []).forEach((pt) => {
          proposedMap.set(pt.workItemId || pt.tempKey, pt);
        });

        setTasks((prev) =>
          prev.map((t) => {
            const match = proposedMap.get(t.id) || proposedMap.get(t.itemKey);
            if (match) {
              return {
                ...t,
                startAt: match.startAt || match.start,
                finishAt: match.finishAt || match.finish,
                assigneeId: match.developerId || t.assigneeId,
                assigneeName: match.developerName || t.assigneeName,
              };
            }
            return t;
          })
        );

        setActionSuccess("Plan proposal successfully applied into active project schedule!");
        setActionLoading(false);
        setConfirmDialogVisible(false);
        return;
      }

      const targetWorkspaceId = project?.workspaceId || workspaceId || "devsphere-core";
      const targetProjectId = project?.id || "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

      try {
        await applyPlanProposal(targetWorkspaceId, targetProjectId, activeProposal.id, {
          expectedProjectVersion: project?.version || 1,
          expectedProposalVersion: activeProposal.version || 1,
          applyMode,
        });
        setActionSuccess("Plan proposal successfully applied into active project schedule!");
        setConfirmDialogVisible(false);
        await loadData();
      } catch (err) {
        setError(err);
      } finally {
        setActionLoading(false);
      }
    },
    [activeProposal, preview, project, workspaceId, loadData]
  );

  // Reject Proposal
  const handleRejectProposal = useCallback(
    async ({ notes } = {}) => {
      if (!activeProposal) return;
      setActionLoading(true);
      setActionSuccess(null);
      setError(null);

      setProposals((prev) =>
        prev.map((p) =>
          p.id === activeProposal.id
            ? { ...p, status: "REJECTED", rejectionNotes: notes, rejectedAt: new Date().toISOString() }
            : p
        )
      );

      setActionSuccess(`Proposal ${activeProposal.id.slice(0, 8)} was rejected.`);
      setActionLoading(false);
      setConfirmDialogVisible(false);
    },
    [activeProposal]
  );

  return {
    project,
    tasks,
    displayTasks,
    dependencies,
    milestones,
    sprints,
    proposals,
    activeProposal,
    preview,
    deadlineConsent,
    cpmResult,
    timelineBounds,
    timelineIntervals,
    scheduleDiff,
    deadlineGating,
    viewMode,
    setViewMode,
    criticalOnly,
    setCriticalOnly,
    zoom,
    setZoom,
    loading,
    error,
    actionLoading,
    actionSuccess,
    confirmDialogVisible,
    setConfirmDialogVisible,
    effectiveUser,
    simulatedRole,
    setSimulatedRole,
    loadData,
    selectProposal,
    handleApproveProposal,
    handleConfirmDeadline,
    handleApplyPlan,
    handleRejectProposal,
  };
}
