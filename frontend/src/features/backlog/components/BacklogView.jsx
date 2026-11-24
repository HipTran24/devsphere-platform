"use client";

import { useState } from "react";
import ProjectNavBar from "../../project/components/ProjectNavBar";
import BacklogFilterToolbar from "./BacklogFilterToolbar";
import WorkItemTree from "./WorkItemTree";
import DependencyDag from "./DependencyDag";
import CreateWorkItemModal from "./CreateWorkItemModal";
import EditWorkItemModal from "./EditWorkItemModal";
import LoadingState from "../../../components/ui/LoadingState";
import ErrorState from "../../../components/ui/ErrorState";
import { useBacklogData } from "../hooks/useBacklogData";
import styles from "../styles/BacklogView.module.css";

export default function BacklogView({ projectKey, workspaceId }) {
  const [activeView, setActiveView] = useState("tree");
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [selectedItem, setSelectedItem] = useState(null);

  const {
    project,
    workItems,
    dependencies,
    requirements,
    assignees,
    filters,
    updateFilter,
    resetFilters,
    treeNodes,
    totalItemsCount,
    visibleItemsCount,
    loading,
    error,
    loadData,
    handleCreateWorkItem,
    handleUpdateWorkItem,
    handleDeleteWorkItem,
    handleTransitionStatus,
    handleBlockToggle,
    handleAddDependency,
    handleDeleteDependency,
  } = useBacklogData({ projectKey, workspaceId });

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
        activeTab="backlog"
      />

      <main id="main-content" className={styles.mainContent} tabIndex={-1}>
        <section className={styles.headerSection} aria-labelledby="backlog-heading">
          <div className={styles.headerTitleRow}>
            <h1 id="backlog-heading">Backlog & Requirements Hierarchy</h1>
          </div>
          <p className={styles.headerDesc}>
            Organize Epics, User Stories, Tasks, and Subtasks. Manage precedence dependencies
            and track work breakdown structures.
          </p>
        </section>

        {loading ? (
          <div className={styles.stateCardWrap}>
            <LoadingState label="Loading backlog hierarchy..." count={5} />
          </div>
        ) : error && workItems.length === 0 ? (
          <div className={styles.stateCardWrap}>
            <ErrorState
              title="Failed to load backlog"
              message={error.message || "An error occurred while fetching work items."}
              onRetry={loadData}
              retryLabel="Reload Backlog"
            />
          </div>
        ) : (
          <>
            <BacklogFilterToolbar
              filters={filters}
              onFilterChange={updateFilter}
              onResetFilters={resetFilters}
              totalItemsCount={totalItemsCount}
              visibleItemsCount={visibleItemsCount}
              onCreateClick={() => setIsCreateOpen(true)}
              activeView={activeView}
              onViewChange={setActiveView}
              assignees={assignees}
            />

            {activeView === "tree" ? (
              <WorkItemTree
                treeNodes={treeNodes}
                onItemClick={(item) => setSelectedItem(item)}
                onCreateClick={() => setIsCreateOpen(true)}
              />
            ) : (
              <DependencyDag
                workItems={workItems}
                dependencies={dependencies}
                onAddDependency={handleAddDependency}
                onDeleteDependency={handleDeleteDependency}
              />
            )}
          </>
        )}
      </main>

      {/* Create Modal */}
      <CreateWorkItemModal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        onCreate={handleCreateWorkItem}
        existingItems={workItems}
        requirements={requirements}
        assignees={assignees}
      />

      {/* Edit Modal */}
      {selectedItem && (
        <EditWorkItemModal
          item={selectedItem}
          isOpen={Boolean(selectedItem)}
          onClose={() => setSelectedItem(null)}
          onUpdate={handleUpdateWorkItem}
          onDelete={handleDeleteWorkItem}
          onTransitionStatus={handleTransitionStatus}
          onBlockToggle={handleBlockToggle}
          existingItems={workItems}
          requirements={requirements}
          assignees={assignees}
        />
      )}
    </div>
  );
}
