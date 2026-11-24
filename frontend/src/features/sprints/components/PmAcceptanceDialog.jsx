"use client";

import { useState, useRef } from "react";
import {
  X,
  CheckCircle2,
  AlertOctagon,
  ShieldAlert,
  RotateCcw,
  ShieldCheck,
  Lock,
} from "lucide-react";
import useFocusTrap from "../../../hooks/useFocusTrap";
import { isPmUser, evaluateQualityGate } from "../utils/sprint-utils";
import styles from "../styles/PmAcceptanceDialog.module.css";

export default function PmAcceptanceDialog({
  item,
  qualityGate = null,
  effectiveUser = null,
  onAccept,
  onReject,
  onClose,
}) {
  const dialogRef = useRef(null);
  const actionButtonRef = useRef(null);

  const [rejectionNotes, setRejectionNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  useFocusTrap({
    active: Boolean(item),
    containerRef: dialogRef,
    initialFocusRef: actionButtonRef,
    onEscape: onClose,
    restoreFocus: true,
  });

  if (!item) return null;

  const isPm = isPmUser(effectiveUser);
  const gateEvaluation = evaluateQualityGate(qualityGate);
  const isGatePassed = gateEvaluation.passed;
  const blockers = Array.isArray(qualityGate?.blockers)
    ? qualityGate.blockers
    : [];

  const handleAcceptClick = async () => {
    if (!isPm) return;
    if (!isGatePassed) return;

    try {
      setSubmitting(true);
      setError("");
      await onAccept?.(item.id);
    } catch (err) {
      setError(err.message || "Failed to accept work item.");
      setSubmitting(false);
    }
  };

  const handleRejectClick = async () => {
    if (!isPm) return;

    try {
      setSubmitting(true);
      setError("");
      await onReject?.(item.id, rejectionNotes.trim());
    } catch (err) {
      setError(err.message || "Failed to reject work item.");
      setSubmitting(false);
    }
  };

  return (
    <div
      className={styles.backdrop}
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          onClose();
        }
      }}
      role="presentation"
    >
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="pm-dialog-title"
      >
        <div className={styles.header}>
          <div className={styles.titleArea}>
            <ShieldCheck size={18} color="#2dd4bf" aria-hidden="true" />
            <h3 id="pm-dialog-title" className={styles.title}>
              PM Acceptance Flow (In Review → Done)
            </h3>
          </div>

          <button
            type="button"
            className={styles.closeButton}
            onClick={onClose}
            aria-label="Close PM acceptance dialog"
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

        <div className={styles.body}>
          <div className={styles.taskSummary}>
            <span className={styles.taskKey}>{item.itemKey}</span>
            <p className={styles.taskTitle}>{item.title}</p>
          </div>

          {!isPm ? (
            <div className={styles.permissionAlert} role="alert">
              <Lock size={18} aria-hidden="true" />
              <div>
                <strong style={{ display: "block", marginBottom: "4px" }}>
                  PM Role Required
                </strong>
                <span>
                  Only Project Managers or Workspace PMs can accept items from In Review
                  into Done. Your current active role does not possess PM sign-off authority.
                </span>
              </div>
            </div>
          ) : (
            <>
              {isGatePassed ? (
                <div className={`${styles.gateBox} ${styles.gatePassed}`} role="status">
                  <div className={`${styles.gateHeader} ${styles.gateHeaderPassed}`}>
                    <CheckCircle2 size={18} aria-hidden="true" />
                    <span>Quality Gate PASSED</span>
                  </div>
                  <ul className={styles.gateDetailsList}>
                    <li>Continuous Integration (CI): All test suites and builds passed</li>
                    <li>Peer Review: Approved with positive code review verdict</li>
                    <li>Acceptance Criteria: All required criteria satisfied</li>
                  </ul>
                  <p style={{ margin: 0, fontSize: "13px", color: "#6ee7b7" }}>
                    This task satisfies all engineering standards and is ready for final PM completion.
                  </p>
                </div>
              ) : (
                <div className={`${styles.gateBox} ${styles.gateFailed}`} role="alert">
                  <div className={`${styles.gateHeader} ${styles.gateHeaderFailed}`}>
                    <AlertOctagon size={18} aria-hidden="true" />
                    <span>Quality Gate FAILED / PENDING</span>
                  </div>
                  <p className={styles.blockerWarning}>
                    This task has not passed all automated quality criteria. DevSphere
                    governance prevents moving unverified work into Done.
                  </p>

                  {blockers.length > 0 && (
                    <ul className={styles.gateDetailsList}>
                      {blockers.map((b, idx) => (
                        <li key={idx} style={{ color: "#fca5a5" }}>
                          {b}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}

              {!isGatePassed && (
                <div className={styles.rejectionSection}>
                  <label htmlFor="rejection-notes" className={styles.rejectionLabel}>
                    Rejection / Change Request Notes:
                  </label>
                  <textarea
                    id="rejection-notes"
                    className={styles.rejectionTextarea}
                    placeholder="Provide remediation instructions for the developer..."
                    value={rejectionNotes}
                    onChange={(e) => setRejectionNotes(e.target.value)}
                  />
                </div>
              )}
            </>
          )}

          {error && (
            <div
              style={{
                fontSize: "12px",
                color: "#f87171",
                background: "rgba(239, 68, 68, 0.12)",
                padding: "8px 12px",
                borderRadius: "6px",
              }}
              role="alert"
            >
              {error}
            </div>
          )}
        </div>

        <div className={styles.footer}>
          <button
            type="button"
            className={styles.btnSecondary}
            onClick={onClose}
            disabled={submitting}
          >
            Cancel
          </button>

          {isPm && !isGatePassed && (
            <button
              type="button"
              className={styles.btnDanger}
              onClick={handleRejectClick}
              disabled={submitting}
            >
              <RotateCcw size={14} aria-hidden="true" />
              <span>PM Reject (Return to In Progress)</span>
            </button>
          )}

          {isPm && isGatePassed && (
            <button
              ref={actionButtonRef}
              type="button"
              className={styles.btnPrimary}
              onClick={handleAcceptClick}
              disabled={submitting}
            >
              <CheckCircle2 size={14} aria-hidden="true" />
              <span>PM Accept & Complete</span>
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
