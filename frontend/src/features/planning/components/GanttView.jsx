"use client";

import { useState } from "react";
import ProjectNavBar from "../../project/components/ProjectNavBar";
import GanttChart from "./GanttChart";
import LoadingState from "../../../components/ui/LoadingState";
import ErrorState from "../../../components/ui/ErrorState";
import EmptyState from "../../../components/ui/EmptyState";
import { usePlanningData } from "../hooks/usePlanningData";
import { CheckCircle2 } from "lucide-react";
import styles from "../styles/PlanningView.module.css";

export default function GanttView({ projectKey, workspaceId }) {
  const {
    project,
    displayTasks,
    dependencies,
    milestones,
    sprints,
    timelineBounds,
    timelineIntervals,
    viewMode,
    setViewMode,
    criticalOnly,
    setCriticalOnly,
    zoom,
    setZoom,
    loading,
    error,
    actionSuccess,
    loadData,
  } = usePlanningData({ projectKey, workspaceId });

  const [selectedTask, setSelectedTask] = useState(null);

  const resolvedKey = project?.projectKey || projectKey || "DSP";
  const resolvedProjectName = project?.name || "DevSphere Platform";
  const resolvedWorkspaceName = project?.workspaceName || "DevSphere Core";
  const resolvedWorkspaceId = project?.workspaceSlug || workspaceId || "devsphere-core";

  return (
    <div className={styles.page}>
      <ProjectNavBar
        projectKey={resolvedKey}
        projectName={resolvedProjectName}
        workspaceName={resolvedWorkspaceName}
        workspaceId={resolvedWorkspaceId}
        activeTab="gantt"
      />

      <main id="main-content" className={styles.mainContent} tabIndex={-1}>
        <section className={styles.headerSection} aria-labelledby="gantt-page-heading">
          <div className={styles.headerTitleRow}>
            <h1 id="gantt-page-heading">Gantt Timeline & Critical Path Schedule</h1>
          </div>
          <p className={styles.headerDesc}>
            Interactive project timeline mapping work items across sprints, visualizing
            precedence dependencies, and highlighting Critical Path Method (CPM) bottlenecks.
          </p>
        </section>

        {actionSuccess && (
          <div className={styles.successToast} role="status">
            <CheckCircle2 size={18} aria-hidden="true" />
            <span>{actionSuccess}</span>
          </div>
        )}

        {loading ? (
          <div className={styles.stateCardWrap}>
            <LoadingState label="Loading Gantt schedule timeline..." count={4} />
          </div>
        ) : error && displayTasks.length === 0 ? (
          <div className={styles.stateCardWrap}>
            <ErrorState
              title="Failed to load Gantt chart"
              message={error.message || "An error occurred while fetching timeline data."}
              onRetry={loadData}
              retryLabel="Reload Timeline"
            />
          </div>
        ) : displayTasks.length === 0 && !criticalOnly ? (
          <div className={styles.stateCardWrap}>
            <EmptyState
              title="No work items scheduled"
              description="There are currently no tasks or work items mapped along this project's timeline."
              actionLabel="Reload"
              onAction={loadData}
            />
          </div>
        ) : (
          <GanttChart
            tasks={displayTasks}
            dependencies={dependencies}
            milestones={milestones}
            sprints={sprints}
            timelineBounds={timelineBounds}
            timelineIntervals={timelineIntervals}
            viewMode={viewMode}
            onViewModeChange={setViewMode}
            criticalOnly={criticalOnly}
            onCriticalOnlyChange={setCriticalOnly}
            zoom={zoom}
            onZoomChange={setZoom}
            selectedTaskId={selectedTask?.id || selectedTask?.itemKey}
            onSelectTask={setSelectedTask}
          />
        )}
      </main>
    </div>
  );
}
