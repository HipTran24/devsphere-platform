"use client";

import { useState, useEffect, useCallback, useMemo } from "react";
import {
  fetchProjects,
  fetchSprints,
  fetchWorkItems,
  fetchQualityGate,
  transitionWorkItemStatus,
  acceptWorkItem,
  rejectWorkItem,
  blockWorkItem,
  updateWorkItem,
  isMockAuthEnabled,
} from "../../../lib/api-client";
import {
  DEMO_SPRINTS,
  DEMO_SPRINT_WORK_ITEMS,
  DEMO_QUALITY_GATES,
} from "../demo-data";
import { DEMO_PROJECT, DEMO_USERS } from "../../backlog/demo-data";
import {
  calculateSprintMetrics,
  getNextColumn,
  getPrevColumn,
  isPmUser,
} from "../utils/sprint-utils";
import { useAuth } from "../../auth/components/AuthProvider";

export function useSprintData({ projectKey, workspaceId, initialSprintId } = {}) {
  const authContext = useAuth();
  const currentUser = authContext?.session?.user || null;

  const [project, setProject] = useState(null);
  const [sprints, setSprints] = useState([]);
  const [activeSprintId, setActiveSprintId] = useState(initialSprintId || null);
  const [workItems, setWorkItems] = useState([]);
  const [qualityGates, setQualityGates] = useState({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Active user role override for testing / simulation
  const [simulatedRole, setSimulatedRole] = useState(null);

  // Active drawer and PM acceptance dialog state
  const [drawerItem, setDrawerItem] = useState(null);
  const [acceptanceItem, setAcceptanceItem] = useState(null);

  const activeSprint = useMemo(() => {
    if (!sprints.length) return null;
    if (activeSprintId) {
      const found = sprints.find((s) => s.id === activeSprintId);
      if (found) return found;
    }
    // Default to active sprint or first sprint
    const active = sprints.find((s) => (s.status || "").toUpperCase() === "ACTIVE");
    return active || sprints[0];
  }, [sprints, activeSprintId]);

  const effectiveUser = useMemo(() => {
    if (simulatedRole) {
      return {
        ...(currentUser || DEMO_USERS[1]),
        role: simulatedRole,
        systemRole: simulatedRole,
        email: simulatedRole === 'PM' ? 'pm@devsphere.io' : `${simulatedRole.toLowerCase()}@devsphere.io`,
      };
    }
    if (currentUser) return currentUser;
    // In mock mode default to PM (Sarah PM) so acceptance flow is testable
    return DEMO_USERS[1];
  }, [currentUser, simulatedRole]);

  const metrics = useMemo(() => {
    return calculateSprintMetrics(workItems, activeSprint);
  }, [workItems, activeSprint]);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);

    const isMock = isMockAuthEnabled();

    if (isMock) {
      setProject({
        ...DEMO_PROJECT,
        projectKey: projectKey || DEMO_PROJECT.projectKey,
        workspaceId: workspaceId || DEMO_PROJECT.workspaceId,
      });
      setSprints(DEMO_SPRINTS);
      if (!activeSprintId) {
        setActiveSprintId(DEMO_SPRINTS[0]?.id || null);
      }
      setWorkItems(DEMO_SPRINT_WORK_ITEMS);
      setQualityGates(DEMO_QUALITY_GATES);
      setLoading(false);
      return;
    }

    try {
      // 1. Resolve project and workspace
      const targetWorkspaceId = workspaceId || DEMO_PROJECT.workspaceId;
      const projectsRes = await fetchProjects(targetWorkspaceId).catch(() => null);
      const projectList = Array.isArray(projectsRes)
        ? projectsRes
        : projectsRes?.items || [];

      let resolvedProject = null;
      if (projectKey) {
        resolvedProject = projectList.find(
          (p) => (p.projectKey || p.key || "").toUpperCase() === projectKey.toUpperCase()
        );
      } else if (projectList.length > 0) {
        resolvedProject = projectList[0];
      }

      if (!resolvedProject) {
        resolvedProject = {
          ...DEMO_PROJECT,
          projectKey: projectKey || "DSP",
          workspaceId: targetWorkspaceId,
        };
      }
      setProject(resolvedProject);

      const pId = resolvedProject.id;
      const wId = resolvedProject.workspaceId || targetWorkspaceId;

      // 2. Fetch sprints
      const sprintListRes = await fetchSprints(wId, pId).catch(() => null);
      const loadedSprints = Array.isArray(sprintListRes)
        ? sprintListRes
        : sprintListRes?.items || DEMO_SPRINTS;

      setSprints(loadedSprints);

      const selectedId =
        activeSprintId ||
        loadedSprints.find((s) => (s.status || "").toUpperCase() === "ACTIVE")?.id ||
        loadedSprints[0]?.id;
      setActiveSprintId(selectedId);

      // 3. Fetch sprint work items
      const itemsRes = await fetchWorkItems(wId, pId, { sprintId: selectedId }).catch(() => null);
      const rawItems = Array.isArray(itemsRes)
        ? itemsRes
        : itemsRes?.items || DEMO_SPRINT_WORK_ITEMS;

      setWorkItems(rawItems);

      // 4. Fetch quality gates for in-review and active code tasks
      const gatesMap = { ...DEMO_QUALITY_GATES };
      const inReviewItems = rawItems.filter(
        (i) => i.status === "IN_REVIEW" || i.workKind === "CODE"
      );

      await Promise.allSettled(
        inReviewItems.map(async (item) => {
          try {
            const gate = await fetchQualityGate(wId, pId, item.id);
            if (gate && gate.status) {
              gatesMap[item.id] = gate;
            }
          } catch {
            // Keep fallback
          }
        })
      );

      setQualityGates(gatesMap);
    } catch (err) {
      // Fallback gracefully on network / config errors
      setProject({
        ...DEMO_PROJECT,
        projectKey: projectKey || "DSP",
        workspaceId: workspaceId || DEMO_PROJECT.workspaceId,
      });
      setSprints(DEMO_SPRINTS);
      if (!activeSprintId) {
        setActiveSprintId(DEMO_SPRINTS[0]?.id || null);
      }
      setWorkItems(DEMO_SPRINT_WORK_ITEMS);
      setQualityGates(DEMO_QUALITY_GATES);
      setError(err);
    } finally {
      setLoading(false);
    }
  }, [projectKey, workspaceId, activeSprintId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Select another sprint
  const selectSprint = useCallback(
    async (sprintId) => {
      setActiveSprintId(sprintId);
      if (!isMockAuthEnabled() && project?.id) {
        try {
          const itemsRes = await fetchWorkItems(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            { sprintId }
          );
          if (Array.isArray(itemsRes)) {
            setWorkItems(itemsRes);
          }
        } catch {
          // Keep current items if offline
        }
      }
    },
    [project]
  );

  // Move card left / right
  const handleMoveCard = useCallback(
    async (itemId, direction) => {
      const item = workItems.find((i) => i.id === itemId);
      if (!item) return;

      const targetStatus =
        direction === "next"
          ? getNextColumn(item.status)
          : getPrevColumn(item.status);

      if (!targetStatus) return;

      // Business rule: moving from IN_REVIEW to DONE requires PM acceptance!
      if (item.status === "IN_REVIEW" && targetStatus === "DONE") {
        setAcceptanceItem(item);
        return;
      }

      // Optimistic local state update
      setWorkItems((prev) =>
        prev.map((i) => (i.id === itemId ? { ...i, status: targetStatus } : i))
      );

      if (!isMockAuthEnabled() && project?.id) {
        try {
          await transitionWorkItemStatus(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            itemId,
            { targetStatus }
          );
        } catch {
          // Revert on error if needed
        }
      }
    },
    [workItems, project]
  );

  // PM Accept work item into DONE
  const handleAcceptItem = useCallback(
    async (itemId) => {
      const isPm = isPmUser(effectiveUser);
      if (!isPm) {
        throw new Error("Only Project Managers can accept items into Done.");
      }

      // Optimistic update
      setWorkItems((prev) =>
        prev.map((i) =>
          i.id === itemId ? { ...i, status: "DONE", acceptedBy: effectiveUser?.id } : i
        )
      );

      setAcceptanceItem(null);
      if (drawerItem?.id === itemId) {
        setDrawerItem((prev) => (prev ? { ...prev, status: "DONE" } : null));
      }

      if (!isMockAuthEnabled() && project?.id) {
        try {
          await acceptWorkItem(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            itemId
          );
        } catch {
          // Continue local state
        }
      }
    },
    [effectiveUser, drawerItem, project]
  );

  // PM Reject work item back to IN_PROGRESS
  const handleRejectItem = useCallback(
    async (itemId, rejectionReason = "") => {
      const isPm = isPmUser(effectiveUser);
      if (!isPm) {
        throw new Error("Only Project Managers can reject review items.");
      }

      const returnStatus = "IN_PROGRESS";

      // Optimistic update
      setWorkItems((prev) =>
        prev.map((i) =>
          i.id === itemId
            ? {
                ...i,
                status: returnStatus,
                rejectionReason,
              }
            : i
        )
      );

      setAcceptanceItem(null);
      if (drawerItem?.id === itemId) {
        setDrawerItem((prev) => (prev ? { ...prev, status: returnStatus } : null));
      }

      if (!isMockAuthEnabled() && project?.id) {
        try {
          await rejectWorkItem(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            itemId,
            { reason: rejectionReason, targetStatus: returnStatus }
          );
        } catch {
          // Continue local state
        }
      }
    },
    [effectiveUser, drawerItem, project]
  );

  // Block / Unblock item with mandatory reason
  const handleToggleBlocked = useCallback(
    async (itemId, isBlocked, blockedReason = "") => {
      if (isBlocked && (!blockedReason || !blockedReason.trim())) {
        throw new Error("A blocker reason is mandatory when blocking a task.");
      }

      const reason = isBlocked ? blockedReason.trim() : null;

      setWorkItems((prev) =>
        prev.map((i) =>
          i.id === itemId
            ? { ...i, isBlocked, blockedReason: reason }
            : i
        )
      );

      if (drawerItem?.id === itemId) {
        setDrawerItem((prev) =>
          prev ? { ...prev, isBlocked, blockedReason: reason } : null
        );
      }

      if (!isMockAuthEnabled() && project?.id) {
        try {
          await blockWorkItem(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            itemId,
            { blocked: isBlocked, blockedReason: reason }
          );
        } catch {
          // Continue
        }
      }
    },
    [drawerItem, project]
  );

  // Log time spent
  const handleLogTime = useCallback(
    async (itemId, additionalMinutes) => {
      const minutes = Number(additionalMinutes);
      if (isNaN(minutes) || minutes <= 0) {
        throw new Error("Please enter a valid positive duration in minutes.");
      }

      setWorkItems((prev) =>
        prev.map((i) => {
          if (i.id === itemId) {
            const updatedMinutes = (i.timeSpentMinutes || 0) + minutes;
            return { ...i, timeSpentMinutes: updatedMinutes };
          }
          return i;
        })
      );

      if (drawerItem?.id === itemId) {
        setDrawerItem((prev) =>
          prev
            ? { ...prev, timeSpentMinutes: (prev.timeSpentMinutes || 0) + minutes }
            : null
        );
      }

      if (!isMockAuthEnabled() && project?.id) {
        try {
          const current = workItems.find((i) => i.id === itemId);
          const totalSpent = (current?.timeSpentMinutes || 0) + minutes;
          await updateWorkItem(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            itemId,
            { timeSpentMinutes: totalSpent }
          );
        } catch {
          // Continue
        }
      }
    },
    [drawerItem, workItems, project]
  );

  // Toggle Acceptance Criteria item completed/uncompleted
  const handleToggleCriterion = useCallback(
    (itemId, criterionId) => {
      setWorkItems((prev) =>
        prev.map((i) => {
          if (i.id === itemId && Array.isArray(i.acceptanceCriteria)) {
            const updatedCriteria = i.acceptanceCriteria.map((ac) =>
              ac.id === criterionId ? { ...ac, completed: !ac.completed } : ac
            );
            return { ...i, acceptanceCriteria: updatedCriteria };
          }
          return i;
        })
      );

      if (drawerItem?.id === itemId && Array.isArray(drawerItem.acceptanceCriteria)) {
        setDrawerItem((prev) => {
          if (!prev) return null;
          const updatedCriteria = prev.acceptanceCriteria.map((ac) =>
            ac.id === criterionId ? { ...ac, completed: !ac.completed } : ac
          );
          return { ...prev, acceptanceCriteria: updatedCriteria };
        });
      }
    },
    [drawerItem]
  );

  return {
    project,
    sprints,
    activeSprint,
    activeSprintId,
    selectSprint,
    workItems,
    qualityGates,
    metrics,
    effectiveUser,
    simulatedRole,
    setSimulatedRole,
    drawerItem,
    openDrawer: (item) => setDrawerItem(item),
    closeDrawer: () => setDrawerItem(null),
    acceptanceItem,
    openAcceptance: (item) => setAcceptanceItem(item),
    closeAcceptance: () => setAcceptanceItem(null),
    loading,
    error,
    loadData,
    handleMoveCard,
    handleAcceptItem,
    handleRejectItem,
    handleToggleBlocked,
    handleLogTime,
    handleToggleCriterion,
  };
}
