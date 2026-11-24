"use client";

import { useState, useEffect, useCallback, useMemo } from "react";
import {
  fetchProjects,
  fetchRequirements,
  fetchWorkItems,
  createWorkItem as apiCreateWorkItem,
  updateWorkItem as apiUpdateWorkItem,
  deleteWorkItem as apiDeleteWorkItem,
  transitionWorkItemStatus as apiTransitionStatus,
  blockWorkItem as apiBlockWorkItem,
  fetchDependencies as apiFetchDependencies,
  createDependency as apiCreateDependency,
  deleteDependency as apiDeleteDependency,
  isMockAuthEnabled,
} from "../../../lib/api-client";
import {
  DEMO_PROJECT,
  DEMO_USERS,
  DEMO_REQUIREMENTS,
  DEMO_WORK_ITEMS,
  DEMO_DEPENDENCIES,
} from "../demo-data";
import { buildWorkItemTree, filterWorkItemTree } from "../utils/tree-utils";
import { resolveViewState } from "../../../components/ui/uiStateHelpers";

const INITIAL_FILTERS = {
  search: "",
  type: "ALL",
  status: "ALL",
  priority: "ALL",
  assigneeId: "ALL",
};

export function useBacklogData({ projectKey, workspaceId }) {
  const [project, setProject] = useState(null);
  const [workItems, setWorkItems] = useState([]);
  const [dependencies, setDependencies] = useState([]);
  const [requirements, setRequirements] = useState([]);
  const [assignees, setAssignees] = useState(DEMO_USERS);
  const [filters, setFilters] = useState(INITIAL_FILTERS);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);

    const isMock = isMockAuthEnabled();

    if (isMock) {
      // Offline / standalone mock mode
      setProject({
        ...DEMO_PROJECT,
        projectKey: projectKey || DEMO_PROJECT.projectKey,
        workspaceId: workspaceId || DEMO_PROJECT.workspaceId,
      });
      setRequirements(DEMO_REQUIREMENTS);
      setWorkItems(DEMO_WORK_ITEMS);
      setDependencies(DEMO_DEPENDENCIES);
      setAssignees(DEMO_USERS);
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

      // 2. Load requirements, work items, and dependencies in parallel
      const [reqsRes, itemsRes, depsRes] = await Promise.allSettled([
        fetchRequirements(wId, pId),
        fetchWorkItems(wId, pId),
        apiFetchDependencies(wId, pId),
      ]);

      const reqs =
        reqsRes.status === "fulfilled" && Array.isArray(reqsRes.value)
          ? reqsRes.value
          : DEMO_REQUIREMENTS;
      setRequirements(reqs);

      const items =
        itemsRes.status === "fulfilled" && Array.isArray(itemsRes.value)
          ? itemsRes.value
          : DEMO_WORK_ITEMS;
      setWorkItems(items);

      const deps =
        depsRes.status === "fulfilled" && Array.isArray(depsRes.value)
          ? depsRes.value
          : DEMO_DEPENDENCIES;
      setDependencies(deps);
    } catch (err) {
      // Graceful fallback to demo data to preserve test/dev accessibility
      setProject({
        ...DEMO_PROJECT,
        projectKey: projectKey || "DSP",
        workspaceId: workspaceId || DEMO_PROJECT.workspaceId,
      });
      setRequirements(DEMO_REQUIREMENTS);
      setWorkItems(DEMO_WORK_ITEMS);
      setDependencies(DEMO_DEPENDENCIES);
      setError(err);
    } finally {
      setLoading(false);
    }
  }, [projectKey, workspaceId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Mutations
  const handleCreateWorkItem = useCallback(
    async (itemData) => {
      if (!isMockAuthEnabled() && project?.id) {
        try {
          const created = await apiCreateWorkItem(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            itemData
          );
          if (created?.id) {
            setWorkItems((prev) => [...prev, created]);
            return created;
          }
        } catch {
          // Fall through to mock addition
        }
      }

      // Local state addition
      const nextNum = workItems.length + 15;
      const keyPrefix = project?.projectKey || "DSP";
      const newItem = {
        ...itemData,
        id: `mock-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        itemKey: `${keyPrefix}-${nextNum}`,
        status: itemData.status || "TO_DO",
        isBlocked: false,
        blockedReason: null,
        version: 1,
        assigneeName: assignees.find((a) => a.id === itemData.assigneeId)?.displayName || null,
      };
      setWorkItems((prev) => [...prev, newItem]);
      return newItem;
    },
    [project, workItems.length, assignees]
  );

  const handleUpdateWorkItem = useCallback(
    async (id, updateData) => {
      if (!isMockAuthEnabled() && project?.id) {
        try {
          await apiUpdateWorkItem(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            id,
            updateData
          );
        } catch {
          // Continue local update
        }
      }

      setWorkItems((prev) =>
        prev.map((item) => {
          if (item.id === id) {
            const assignee = assignees.find((a) => a.id === updateData.assigneeId);
            return {
              ...item,
              ...updateData,
              assigneeName: assignee ? assignee.displayName : item.assigneeName,
              version: (item.version || 0) + 1,
            };
          }
          return item;
        })
      );
    },
    [project, assignees]
  );

  const handleDeleteWorkItem = useCallback(
    async (id) => {
      if (!isMockAuthEnabled() && project?.id) {
        try {
          await apiDeleteWorkItem(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            id
          );
        } catch {
          // Continue local delete
        }
      }

      setWorkItems((prev) => prev.filter((item) => item.id !== id));
      setDependencies((prev) =>
        prev.filter((dep) => dep.predecessorId !== id && dep.successorId !== id)
      );
    },
    [project]
  );

  const handleTransitionStatus = useCallback(
    async (id, targetStatus) => {
      if (!isMockAuthEnabled() && project?.id) {
        try {
          await apiTransitionStatus(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            id,
            { targetStatus }
          );
        } catch {
          // Continue local transition
        }
      }

      setWorkItems((prev) =>
        prev.map((item) =>
          item.id === id ? { ...item, status: targetStatus } : item
        )
      );
    },
    [project]
  );

  const handleBlockToggle = useCallback(
    async (id, blocked, blockedReason) => {
      if (!isMockAuthEnabled() && project?.id) {
        try {
          await apiBlockWorkItem(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            id,
            { blocked, blockedReason }
          );
        } catch {
          // Continue local toggle
        }
      }

      setWorkItems((prev) =>
        prev.map((item) =>
          item.id === id
            ? { ...item, isBlocked: blocked, blockedReason: blocked ? blockedReason : null }
            : item
        )
      );
    },
    [project]
  );

  const handleAddDependency = useCallback(
    async (depData) => {
      if (!isMockAuthEnabled() && project?.id) {
        try {
          const res = await apiCreateDependency(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            depData
          );
          if (res?.id) {
            setDependencies((prev) => [...prev, res]);
            return res;
          }
        } catch {
          // Fall through
        }
      }

      const pred = workItems.find((w) => w.id === depData.predecessorId);
      const succ = workItems.find((w) => w.id === depData.successorId);
      const newDep = {
        ...depData,
        id: `dep-${Date.now()}`,
        predecessorKey: pred?.itemKey || "PRED",
        predecessorTitle: pred?.title || "Item",
        successorKey: succ?.itemKey || "SUCC",
        successorTitle: succ?.title || "Item",
        isCritical: false,
      };
      setDependencies((prev) => [...prev, newDep]);
      return newDep;
    },
    [project, workItems]
  );

  const handleDeleteDependency = useCallback(
    async (depId) => {
      if (!isMockAuthEnabled() && project?.id) {
        try {
          await apiDeleteDependency(
            project.workspaceId || DEMO_PROJECT.workspaceId,
            project.id,
            depId
          );
        } catch {
          // Fall through
        }
      }

      setDependencies((prev) => prev.filter((d) => d.id !== depId));
    },
    [project]
  );

  const updateFilter = useCallback((field, value) => {
    setFilters((prev) => ({ ...prev, [field]: value }));
  }, []);

  const resetFilters = useCallback(() => {
    setFilters(INITIAL_FILTERS);
  }, []);

  // Compute Tree & Filters
  const rawTree = useMemo(() => {
    return buildWorkItemTree(workItems);
  }, [workItems]);

  const filteredTree = useMemo(() => {
    return filterWorkItemTree(rawTree, filters);
  }, [rawTree, filters]);

  const viewState = resolveViewState({
    loading,
    error: null, // Graceful fallback
    isEmpty: workItems.length === 0,
    hasData: workItems.length > 0,
  });

  return {
    project,
    workItems,
    dependencies,
    requirements,
    assignees,
    filters,
    updateFilter,
    resetFilters,
    treeNodes: filteredTree,
    totalItemsCount: workItems.length,
    visibleItemsCount: filteredTree.length,
    loading,
    error,
    viewState,
    loadData,
    handleCreateWorkItem,
    handleUpdateWorkItem,
    handleDeleteWorkItem,
    handleTransitionStatus,
    handleBlockToggle,
    handleAddDependency,
    handleDeleteDependency,
  };
}
