"use client";

import ProjectNavBar from "../../project/components/ProjectNavBar";
import ProposalDiffViewer from "./ProposalDiffViewer";
import DeadlineConfirmationDialog from "./DeadlineConfirmationDialog";
import LoadingState from "../../../components/ui/LoadingState";
import ErrorState from "../../../components/ui/ErrorState";
import EmptyState from "../../../components/ui/EmptyState";
import { usePlanningData } from "../hooks/usePlanningData";
import { CheckCircle2 } from "lucide-react";
import styles from "../styles/PlanningView.module.css";

export default function PlanApprovalView({ projectKey, workspaceId }) {
  const {
    project,
    proposals,
    activeProposal,
    preview,
    scheduleDiff,
    deadlineGating,
    loading,
    error,
    actionLoading,
    actionSuccess,
    confirmDialogVisible,
    setConfirmDialogVisible,
    loadData,
    selectProposal,
    handleApproveProposal,
    handleConfirmDeadline,
    handleApplyPlan,
    handleRejectProposal,
  } = usePlanningData({ projectKey, workspaceId });

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
        activeTab="plan-approval"
      />

      <main id="main-content" className={styles.mainContent} tabIndex={-1}>
        <section className={styles.headerSection} aria-labelledby="plan-approval-heading">
          <div className={styles.headerTitleRow}>
            <h1 id="plan-approval-heading">Plan Approval & Schedule Proposal Diff</h1>
          </div>
          <p className={styles.headerDesc}>
            Review Google OR-Tools CP-SAT solver schedule proposals against current baselines,
            inspect milestone impacts, and confirm committed deadline changes before applying.
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
            <LoadingState label="Loading CP-SAT proposal diff..." count={4} />
          </div>
        ) : error && !activeProposal ? (
          <div className={styles.stateCardWrap}>
            <ErrorState
              title="Failed to load plan proposal"
              message={error.message || "An error occurred while fetching proposal data."}
              onRetry={loadData}
              retryLabel="Reload Proposal"
            />
          </div>
        ) : !activeProposal ? (
          <div className={styles.stateCardWrap}>
            <EmptyState
              title="No plan proposals found"
              description="There are currently no pending CP-SAT solver proposals for this project."
              actionLabel="Reload"
              onAction={loadData}
            />
          </div>
        ) : (
          <>
            <ProposalDiffViewer
              proposals={proposals}
              activeProposal={activeProposal}
              preview={preview}
              scheduleDiff={scheduleDiff}
              deadlineGating={deadlineGating}
              onSelectProposal={selectProposal}
              onOpenApprovalModal={() => setConfirmDialogVisible(true)}
              onRejectProposal={() =>
                handleRejectProposal({ notes: "Rejected during proposal review." })
              }
            />

            <DeadlineConfirmationDialog
              isOpen={confirmDialogVisible}
              proposal={preview || activeProposal}
              deadlineGating={deadlineGating}
              project={project}
              onConfirmAndApply={async ({ notes, rationale }) => {
                const confirmed = await handleConfirmDeadline({ notes, rationale });
                if (confirmed) {
                  await handleApplyPlan();
                }
              }}
              onApproveAndApply={async ({ notes }) => {
                await handleApproveProposal({ notes });
                await handleApplyPlan();
              }}
              onClose={() => setConfirmDialogVisible(false)}
              submitting={actionLoading}
            />
          </>
        )}
      </main>
    </div>
  );
}
