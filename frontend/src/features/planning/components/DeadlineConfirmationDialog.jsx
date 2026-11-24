"use client";

import { useState, useRef } from "react";
import {
  X,
  AlertTriangle,
  CheckCircle2,
  ShieldAlert,
  Lock,
  ArrowRight,
  Send,
} from "lucide-react";
import useFocusTrap from "../../../hooks/useFocusTrap";
import { formatDate } from "../utils/planning-utils";
import styles from "../styles/DeadlineConfirmationDialog.module.css";

export default function DeadlineConfirmationDialog({
  isOpen = false,
  proposal,
  deadlineGating,
  project,
  onConfirmAndApply,
  onApproveAndApply,
  onClose,
  submitting = false,
}) {
  const dialogRef = useRef(null);
  const actionButtonRef = useRef(null);

  const [acknowledged, setAcknowledged] = useState(false);
  const [rationale, setRationale] = useState("");
  const [localError, setLocalError] = useState("");

  useFocusTrap({
    active: isOpen,
    containerRef: dialogRef,
    initialFocusRef: actionButtonRef,
    onEscape: onClose,
    restoreFocus: true,
  });

  if (!isOpen || !proposal) return null;

  const requiresConfirmation = Boolean(deadlineGating?.requiresConfirmation);
  const slippingMilestones = deadlineGating?.slippingMilestones || [];
  const delayDays = deadlineGating?.delayDays || 0;

  const currentDeadline = formatDate(
    deadlineGating?.currentDeadline || project?.committedDeadline || "2026-12-15"
  );
  const proposedDeadline = formatDate(
    deadlineGating?.proposedDeadline || proposal?.forecastFinish || "2026-12-22"
  );

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLocalError("");

    if (requiresConfirmation && !acknowledged) {
      setLocalError("You must acknowledge and confirm the deadline extension.");
      return;
    }

    try {
      if (requiresConfirmation) {
        await onConfirmAndApply?.({
          notes: rationale,
          rationale,
          acknowledgedDelayDays: delayDays,
        });
      } else {
        await onApproveAndApply?.({ notes: rationale });
      }
    } catch (err) {
      setLocalError(err.message || "Failed to process plan proposal.");
    }
  };

  const isSubmitDisabled = submitting || (requiresConfirmation && !acknowledged);

  return (
    <div
      className={styles.overlay}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose?.();
      }}
      role="presentation"
    >
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="deadline-confirmation-title"
        data-testid="deadline-confirmation-dialog"
      >
        {/* Header */}
        <div className={styles.dialogHeader}>
          <div className={styles.titleRow}>
            {requiresConfirmation ? (
              <AlertTriangle className={styles.titleIconWarning} size={22} aria-hidden="true" />
            ) : (
              <CheckCircle2 className={styles.titleIconCheck} size={22} aria-hidden="true" />
            )}
            <h2 id="deadline-confirmation-title" className={styles.dialogTitle}>
              {requiresConfirmation
                ? "Mandatory Committed Deadline Confirmation"
                : "Approve & Apply Plan Proposal"}
            </h2>
          </div>
          <button
            type="button"
            className={styles.closeButton}
            onClick={onClose}
            aria-label="Close dialog"
            data-testid="close-dialog-btn"
          >
            <X size={18} />
          </button>
        </div>

        {/* Body */}
        <form onSubmit={handleSubmit}>
          <div className={styles.dialogBody}>
            {requiresConfirmation ? (
              <>
                <div className={styles.warningBox} role="alert">
                  <h3 className={styles.warningHeading}>
                    <ShieldAlert size={18} />
                    <span>Schedule Extension Requires PM/Client Consent</span>
                  </h3>
                  <p className={styles.warningText}>
                    Warning: This CP-SAT schedule proposal moves the committed client deadline
                    from <strong>{currentDeadline}</strong> to <strong>{proposedDeadline}</strong>.
                    {delayDays > 0 && ` (+${delayDays} calendar days extension).`}
                  </p>
                </div>

                {slippingMilestones.length > 0 && (
                  <div className={styles.slippingList}>
                    <span style={{ fontSize: "0.75rem", color: "#94a3b8", fontWeight: 600 }}>
                      Slipping Milestones:
                    </span>
                    {slippingMilestones.map((m) => (
                      <div key={m.milestoneId || m.name} className={styles.slippingItem}>
                        <span className={styles.slippingName}>{m.name}</span>
                        <span className={styles.slippingBadge}>+{m.delayDays}d slip</span>
                      </div>
                    ))}
                  </div>
                )}

                <div className={styles.confirmationForm}>
                  <label className={styles.checkboxLabel}>
                    <input
                      type="checkbox"
                      className={styles.checkboxInput}
                      checked={acknowledged}
                      onChange={(e) => setAcknowledged(e.target.checked)}
                      data-testid="deadline-consent-checkbox"
                    />
                    <span>
                      I explicitly acknowledge and confirm the committed client deadline shift to{" "}
                      <strong>{proposedDeadline}</strong> on behalf of the project stakeholder / PM.
                    </span>
                  </label>

                  <div>
                    <label
                      htmlFor="deadline-rationale"
                      style={{
                        display: "block",
                        fontSize: "0.75rem",
                        color: "var(--text-secondary)",
                        marginBottom: 4,
                      }}
                    >
                      Confirmation Rationale & Notes (Optional):
                    </label>
                    <textarea
                      id="deadline-rationale"
                      className={styles.notesInput}
                      placeholder="e.g. Client agreed to 7-day scope extension for CP-SAT solver optimization."
                      value={rationale}
                      onChange={(e) => setRationale(e.target.value)}
                      data-testid="deadline-rationale-input"
                    />
                  </div>
                </div>
              </>
            ) : (
              <div className={styles.successBox}>
                <h3 className={styles.successHeading}>
                  <CheckCircle2 size={18} />
                  <span>On-Schedule Plan Proposal</span>
                </h3>
                <p className={styles.successText}>
                  All work items and milestones in this proposal finish safely within the
                  committed project deadline (<strong>{currentDeadline}</strong>). You may
                  immediately approve and apply this schedule.
                </p>
              </div>
            )}

            <div className={styles.concurrencyNote}>
              <Lock size={12} />
              <span>
                Optimistic lock active: Project v{project?.version || 1}, Proposal v
                {proposal?.version || proposal?.proposalVersion || 1}.
              </span>
            </div>

            {localError && (
              <div className={styles.errorMessage} role="alert">
                {localError}
              </div>
            )}
          </div>

          {/* Footer */}
          <div className={styles.dialogFooter}>
            <button
              type="button"
              className={styles.btnCancel}
              onClick={onClose}
              disabled={submitting}
              data-testid="cancel-approval-btn"
            >
              Cancel
            </button>
            <button
              ref={actionButtonRef}
              type="submit"
              className={styles.btnSubmit}
              disabled={isSubmitDisabled}
              data-testid="confirm-apply-btn"
            >
              {submitting ? (
                <span>Processing...</span>
              ) : requiresConfirmation ? (
                <>
                  <ShieldAlert size={16} />
                  <span>Confirm Deadline & Apply</span>
                </>
              ) : (
                <>
                  <Send size={16} />
                  <span>Approve & Apply Schedule</span>
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
