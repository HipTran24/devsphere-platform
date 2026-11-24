"use client";

import ProjectNavBar from "../../project/components/ProjectNavBar";
import SprintHeader from "./SprintHeader";
import SprintBoard from "./SprintBoard";
import TaskDrawer from "./TaskDrawer";
import PmAcceptanceDialog from "./PmAcceptanceDialog";
import LoadingState from "../../../components/ui/LoadingState";
import ErrorState from "../../../components/ui/ErrorState";
import EmptyState from "../../../components/ui/EmptyState";
import { useSprintData } from "../hooks/useSprintData";
import styles from "../styles/SprintView.module.css";

export default function SprintView({ projectKey, workspaceId }) {
  const {
    project,
    sprints,
    activeSprint,
    selectSprint,
    workItems,
    qualityGates,
    metrics,
    effectiveUser,
    simulatedRole,
    setSimulatedRole,
    drawerItem,
    openDrawer,
    closeDrawer,
    acceptanceItem,
    closeAcceptance,
    loading,
    error,
    loadData,
    handleMoveCard,
    handleAcceptItem,
    handleRejectItem,
    handleToggleBlocked,
    handleLogTime,
    handleToggleCriterion,
  } = useSprintData({ projectKey, workspaceId });

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
        activeTab="sprints"
      />

      <main id="main-content" className={styles.mainContent} tabIndex={-1}>
        <section className={styles.headerSection} aria-labelledby="sprints-page-heading">
          <div className={styles.headerTitleRow}>
            <h1 id="sprints-page-heading">Sprint Board & Flow Control</h1>
          </div>
          <p className={styles.headerDesc}>
            Manage sprint execution, track story points burndown, inspect automated Quality
            Gates, and execute PM acceptance sign-offs.
          </p>
        </section>

        {loading ? (
          <div className={styles.stateCardWrap}>
            <LoadingState label="Loading sprint kanban board..." count={4} />
          </div>
        ) : error && workItems.length === 0 ? (
          <div className={styles.stateCardWrap}>
            <ErrorState
              title="Failed to load sprint board"
              message={error.message || "An error occurred while fetching sprint data."}
              onRetry={loadData}
              retryLabel="Reload Sprint"
            />
          </div>
        ) : sprints.length === 0 ? (
          <div className={styles.stateCardWrap}>
            <EmptyState
              title="No sprints found"
              description="There are currently no active or planned sprints in this project."
              actionLabel="Reload"
              onAction={loadData}
            />
          </div>
        ) : (
          <>
            <SprintHeader
              sprints={sprints}
              activeSprint={activeSprint}
              onSelectSprint={selectSprint}
              metrics={metrics}
              effectiveUser={effectiveUser}
              simulatedRole={simulatedRole}
              onSimulateRole={setSimulatedRole}
            />

            <SprintBoard
              workItems={workItems}
              qualityGates={qualityGates}
              onOpenDrawer={openDrawer}
              onMoveCard={handleMoveCard}
            />

            {drawerItem && (
              <TaskDrawer
                item={drawerItem}
                onClose={closeDrawer}
                onToggleCriterion={handleToggleCriterion}
                onLogTime={handleLogTime}
                onToggleBlocked={handleToggleBlocked}
              />
            )}

            {acceptanceItem && (
              <PmAcceptanceDialog
                item={acceptanceItem}
                qualityGate={qualityGates[acceptanceItem.id]}
                effectiveUser={effectiveUser}
                onAccept={handleAcceptItem}
                onReject={handleRejectItem}
                onClose={closeAcceptance}
              />
            )}
          </>
        )}
      </main>
    </div>
  );
}
