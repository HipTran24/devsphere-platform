"use client";

import { useState, useMemo } from "react";
import {
  CheckCircle2,
  AlertTriangle,
  Clock,
  Calendar,
  Layers,
  ArrowRight,
  User,
  Sparkles,
  TrendingDown,
  TrendingUp,
  XCircle,
} from "lucide-react";
import {
  formatDate,
  formatDuration,
} from "../utils/planning-utils";
import styles from "../styles/ProposalDiffViewer.module.css";

export default function ProposalDiffViewer({
  proposals = [],
  activeProposal,
  preview,
  scheduleDiff,
  deadlineGating,
  onSelectProposal,
  onOpenApprovalModal,
  onRejectProposal,
}) {
  const [filterMode, setFilterMode] = useState("ALL"); // 'ALL' | 'SHIFTED' | 'DELAYED' | 'ADVANCED'

  const currentProp = preview || activeProposal;

  const solverStatus = (currentProp?.solverStatus || "OPTIMAL").toUpperCase();
  const isOptimal = solverStatus === "OPTIMAL";
  const isFeasible = solverStatus === "FEASIBLE";

  const diffItems = useMemo(() => scheduleDiff?.diffItems || [], [scheduleDiff]);
  const summary = scheduleDiff?.summary || {};

  const filteredDiffItems = useMemo(() => {
    if (filterMode === "SHIFTED") {
      return diffItems.filter((i) => i.delayDays !== 0);
    }
    if (filterMode === "DELAYED") {
      return diffItems.filter((i) => i.delayDays > 0);
    }
    if (filterMode === "ADVANCED") {
      return diffItems.filter((i) => i.delayDays < 0);
    }
    return diffItems;
  }, [diffItems, filterMode]);

  const milestoneImpacts = currentProp?.milestoneImpacts || [];

  const changesCommittedDeadline = Boolean(
    currentProp?.changesCommittedDeadline || deadlineGating?.requiresConfirmation
  );

  return (
    <div
      className={styles.diffContainer}
      role="region"
      aria-label="CP-SAT Proposal Before/After Diff Viewer"
      data-testid="proposal-diff-viewer"
    >
      {/* 1. Executive Solver Overview */}
      <div className={styles.overviewCard}>
        <div className={styles.overviewHeader}>
          <div className={styles.overviewTitleRow}>
            <h2 className={styles.overviewTitle}>CP-SAT Solver Proposal Overview</h2>
            <span
              className={`${styles.statusPill} ${
                isOptimal
                  ? styles.statusOptimal
                  : isFeasible
                  ? styles.statusFeasible
                  : styles.statusInfeasible
              }`}
              data-testid="solver-status-pill"
            >
              {isOptimal && <CheckCircle2 size={13} />}
              {isFeasible && <Clock size={13} />}
              {!isOptimal && !isFeasible && <AlertTriangle size={13} />}
              <span>{solverStatus}</span>
            </span>
          </div>

          {proposals.length > 1 && (
            <div className={styles.proposalSelectWrap}>
              <label htmlFor="proposal-select" className={styles.proposalSelectLabel}>
                Proposal:
              </label>
              <select
                id="proposal-select"
                className={styles.proposalSelect}
                value={currentProp?.id || ""}
                onChange={(e) => onSelectProposal?.(e.target.value)}
                data-testid="proposal-select"
              >
                {proposals.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.kind || "PROPOSAL"} — {p.id.slice(0, 8)}{" "}
                    {p.changesCommittedDeadline ? "(Slipping)" : "(On-time)"}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>

        {/* Metrics Grid */}
        <div className={styles.metricsGrid}>
          <div className={styles.metricCard}>
            <span className={styles.metricLabel}>
              <Layers size={13} /> Proposed Tasks
            </span>
            <span className={styles.metricValue}>
              {currentProp?.proposedTaskCount || diffItems.length || 0}
            </span>
            <span className={styles.metricSubtitle}>Work items rescheduled</span>
          </div>

          <div className={styles.metricCard}>
            <span className={styles.metricLabel}>
              <Clock size={13} /> Total Duration
            </span>
            <span className={styles.metricValue}>
              {formatDuration(currentProp?.totalMinutes || 2460)}
            </span>
            <span className={styles.metricSubtitle}>Total planned effort</span>
          </div>

          <div className={styles.metricCard}>
            <span className={styles.metricLabel}>
              <Calendar size={13} /> Forecast Finish
            </span>
            <span className={styles.metricValue}>
              {formatDate(currentProp?.forecastFinish || currentProp?.proposedCommittedDeadline)}
            </span>
            <span className={styles.metricSubtitle}>Solver optimized date</span>
          </div>

          <div className={styles.metricCard}>
            <span className={styles.metricLabel}>
              <AlertTriangle size={13} /> Committed Deadline
            </span>
            <span
              className={styles.metricValue}
              style={{ color: changesCommittedDeadline ? "#f87171" : "#34d399" }}
            >
              {formatDate(currentProp?.currentCommittedDeadline || "2026-12-15")}
            </span>
            <span className={styles.metricSubtitle}>
              {changesCommittedDeadline ? "Deadline shifts past target" : "Within target envelope"}
            </span>
          </div>
        </div>

        {/* Slipping Deadline Warning Banner */}
        {changesCommittedDeadline ? (
          <div
            className={styles.slippingAlertBanner}
            role="alert"
            data-testid="slipping-alert-banner"
          >
            <AlertTriangle className={styles.slippingAlertIcon} size={20} aria-hidden="true" />
            <div className={styles.slippingAlertText}>
              <h3 className={styles.slippingAlertTitle}>
                Mandatory Client / PM Deadline Confirmation Required
              </h3>
              <p className={styles.slippingAlertDesc}>
                This CP-SAT schedule proposal moves the committed client deadline from{" "}
                <strong>{formatDate(currentProp?.currentCommittedDeadline || "2026-12-15")}</strong> to{" "}
                <strong>
                  {formatDate(currentProp?.proposedCommittedDeadline || currentProp?.forecastFinish)}
                </strong>
                . Client or PM confirmation is mandatory before this proposal can be applied.
              </p>
            </div>
          </div>
        ) : (
          <div className={styles.nonSlippingBanner}>
            <CheckCircle2 size={16} />
            <span>
              All tasks finish within the committed deadline (
              {formatDate(currentProp?.currentCommittedDeadline || "2026-12-15")}).
            </span>
          </div>
        )}
      </div>

      {/* 2. Schedule Before/After Diff Table */}
      <div className={styles.sectionCard}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>
            <span>Work Item Schedule Comparison</span>
            <span style={{ fontSize: "0.8125rem", color: "var(--text-secondary)", fontWeight: 400 }}>
              ({summary.delayedCount || 0} delayed, {summary.advancedCount || 0} earlier,{" "}
              {summary.unchangedCount || 0} unchanged)
            </span>
          </h3>

          <div className={styles.filterControls} role="group" aria-label="Filter schedule diff">
            <button
              type="button"
              className={`${styles.filterBtn} ${filterMode === "ALL" ? styles.filterBtnActive : ""}`}
              onClick={() => setFilterMode("ALL")}
              data-testid="diff-filter-all"
            >
              All ({diffItems.length})
            </button>
            <button
              type="button"
              className={`${styles.filterBtn} ${filterMode === "SHIFTED" ? styles.filterBtnActive : ""}`}
              onClick={() => setFilterMode("SHIFTED")}
              data-testid="diff-filter-shifted"
            >
              Shifted ({(summary.delayedCount || 0) + (summary.advancedCount || 0)})
            </button>
            <button
              type="button"
              className={`${styles.filterBtn} ${filterMode === "DELAYED" ? styles.filterBtnActive : ""}`}
              onClick={() => setFilterMode("DELAYED")}
              data-testid="diff-filter-delayed"
            >
              Delayed ({summary.delayedCount || 0})
            </button>
            <button
              type="button"
              className={`${styles.filterBtn} ${filterMode === "ADVANCED" ? styles.filterBtnActive : ""}`}
              onClick={() => setFilterMode("ADVANCED")}
              data-testid="diff-filter-advanced"
            >
              Earlier ({summary.advancedCount || 0})
            </button>
          </div>
        </div>

        <div className={styles.tableWrap}>
          <table className={styles.diffTable} aria-label="Schedule diff before and after">
            <thead>
              <tr>
                <th scope="col">Work Item</th>
                <th scope="col">Original Baseline</th>
                <th scope="col">Proposed CP-SAT</th>
                <th scope="col">Schedule Shift</th>
                <th scope="col">Assignee</th>
              </tr>
            </thead>
            <tbody>
              {filteredDiffItems.map((item) => (
                <tr key={item.itemKey} data-testid={`diff-row-${item.itemKey}`}>
                  <td>
                    <span className={styles.diffItemKey}>{item.itemKey}</span>
                    <span>{item.title}</span>
                  </td>
                  <td>
                    {formatDate(item.baselineStart)} → {formatDate(item.baselineFinish)}
                  </td>
                  <td>
                    {formatDate(item.proposedStart)} → {formatDate(item.proposedFinish)}
                  </td>
                  <td>
                    <span
                      className={`${styles.shiftBadge} ${
                        item.shiftType === "SLIP"
                          ? styles.shiftSlip
                          : item.shiftType === "ADVANCED"
                          ? styles.shiftAdvanced
                          : styles.shiftUnchanged
                      }`}
                    >
                      {item.shiftType === "SLIP" && <TrendingUp size={12} />}
                      {item.shiftType === "ADVANCED" && <TrendingDown size={12} />}
                      <span>{item.shiftLabel}</span>
                    </span>
                  </td>
                  <td>
                    <div className={styles.assigneeBadge}>
                      <User size={13} />
                      <span className={item.assigneeChanged ? styles.assigneeChanged : ""}>
                        {item.proposedAssignee}
                      </span>
                      {item.assigneeChanged && (
                        <span style={{ fontSize: "0.6875rem", color: "#fbbf24" }}>
                          (was {item.currentAssignee})
                        </span>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* 3. Milestone Impact Table */}
      {milestoneImpacts.length > 0 && (
        <div className={styles.sectionCard}>
          <div className={styles.sectionHeader}>
            <h3 className={styles.sectionTitle}>Milestone Buffer & Impact Analysis</h3>
          </div>

          <div className={styles.tableWrap}>
            <table className={styles.diffTable} aria-label="Milestone impacts table">
              <thead>
                <tr>
                  <th scope="col">Milestone Name</th>
                  <th scope="col">Target Deadline</th>
                  <th scope="col">Delay / Shift</th>
                  <th scope="col">Buffer Status</th>
                </tr>
              </thead>
              <tbody>
                {milestoneImpacts.map((m) => {
                  const delay = Number(m.delayDays || 0);
                  const isSlipping = delay > 0;
                  return (
                    <tr key={m.milestoneId || m.name} data-testid={`milestone-impact-${m.milestoneId}`}>
                      <td>
                        <strong>{m.name}</strong>
                      </td>
                      <td>{formatDate(m.targetDate)}</td>
                      <td>
                        <span
                          className={`${styles.shiftBadge} ${
                            isSlipping ? styles.shiftSlip : styles.shiftAdvanced
                          }`}
                        >
                          {isSlipping ? `+${delay} days delay` : "On schedule"}
                        </span>
                      </td>
                      <td>
                        <span
                          className={`${styles.milestoneStatusPill} ${
                            isSlipping ? styles.milestoneSlipping : styles.milestoneOnTrack
                          }`}
                        >
                          {isSlipping ? "SLIPPING" : "ON_SCHEDULE"}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 4. Action Bar */}
      <div className={styles.actionBar}>
        <button
          type="button"
          className={styles.btnReject}
          onClick={onRejectProposal}
          data-testid="reject-proposal-btn"
        >
          <XCircle size={16} />
          <span>Reject Proposal</span>
        </button>

        <button
          type="button"
          className={styles.btnApprove}
          onClick={onOpenApprovalModal}
          data-testid="review-approve-plan-btn"
        >
          <CheckCircle2 size={16} />
          <span>Review & Approve Plan</span>
        </button>
      </div>
    </div>
  );
}
