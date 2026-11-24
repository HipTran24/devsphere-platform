"use client";

import { useState, useRef } from "react";
import {
  X,
  CheckSquare,
  Clock,
  AlertTriangle,
  ShieldAlert,
  Unlock,
} from "lucide-react";
import useFocusTrap from "../../../hooks/useFocusTrap";
import { formatMinutes } from "../utils/sprint-utils";
import styles from "../styles/TaskDrawer.module.css";

export default function TaskDrawer({
  item,
  onClose,
  onToggleCriterion,
  onLogTime,
  onToggleBlocked,
}) {
  const drawerRef = useRef(null);
  const closeButtonRef = useRef(null);

  const [minutesInput, setMinutesInput] = useState("");
  const [timeError, setTimeError] = useState("");
  const [isBlockingFormOpen, setIsBlockingFormOpen] = useState(false);
  const [blockedReasonInput, setBlockedReasonInput] = useState("");
  const [blockError, setBlockError] = useState("");

  useFocusTrap({
    active: Boolean(item),
    containerRef: drawerRef,
    initialFocusRef: closeButtonRef,
    onEscape: onClose,
    restoreFocus: true,
  });

  if (!item) return null;

  const isBlocked = Boolean(item.isBlocked);
  const status = (item.status || "TO_DO").toUpperCase();
  const criteria = Array.isArray(item.acceptanceCriteria)
    ? item.acceptanceCriteria
    : [];

  const getStatusPillClass = () => {
    if (status === "TO_DO") return styles.statusToDo;
    if (status === "IN_PROGRESS") return styles.statusInProgress;
    if (status === "IN_REVIEW") return styles.statusInReview;
    return styles.statusDone;
  };

  const handleLogTimeSubmit = async (e) => {
    e.preventDefault();
    setTimeError("");
    const parsed = parseInt(minutesInput, 10);
    if (isNaN(parsed) || parsed <= 0) {
      setTimeError("Please enter a valid positive number of minutes.");
      return;
    }
    try {
      await onLogTime?.(item.id, parsed);
      setMinutesInput("");
    } catch (err) {
      setTimeError(err.message || "Failed to log time.");
    }
  };

  const handleConfirmBlock = async (e) => {
    e.preventDefault();
    setBlockError("");
    if (!blockedReasonInput.trim()) {
      setBlockError("A blocker reason is mandatory when blocking a task.");
      return;
    }
    try {
      await onToggleBlocked?.(item.id, true, blockedReasonInput.trim());
      setIsBlockingFormOpen(false);
      setBlockedReasonInput("");
    } catch (err) {
      setBlockError(err.message || "Failed to block task.");
    }
  };

  const handleUnblock = async () => {
    setBlockError("");
    try {
      await onToggleBlocked?.(item.id, false);
    } catch (err) {
      setBlockError(err.message || "Failed to unblock task.");
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
      <aside
        ref={drawerRef}
        className={styles.drawer}
        role="dialog"
        aria-modal="true"
        aria-labelledby="drawer-task-title"
      >
        <div className={styles.drawerHeader}>
          <div className={styles.headerMeta}>
            <span className={styles.keyBadge}>{item.itemKey}</span>
            <span className={`${styles.statusPill} ${getStatusPillClass()}`}>
              {status.replace("_", " ")}
            </span>
          </div>

          <button
            ref={closeButtonRef}
            type="button"
            className={styles.closeButton}
            onClick={onClose}
            aria-label="Close task details drawer"
          >
            <X size={18} aria-hidden="true" />
          </button>
        </div>

        <div className={styles.drawerBody}>
          <h2 id="drawer-task-title" className={styles.title}>
            {item.title}
          </h2>

          <div className={styles.metaGrid}>
            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>Assignee</span>
              <span className={styles.metaValue}>
                {item.assigneeName || "Unassigned"}
              </span>
            </div>

            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>Priority</span>
              <span className={styles.metaValue}>
                {item.priority || "Medium"}
              </span>
            </div>

            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>Estimate</span>
              <span className={styles.metaValue}>
                {item.estimateMinutes
                  ? `${formatMinutes(item.estimateMinutes)} (${item.storyPoints || 0} SP)`
                  : `${item.storyPoints || 0} SP`}
              </span>
            </div>

            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>Work Kind</span>
              <span className={styles.metaValue}>
                {item.workKind || "CODE"}
              </span>
            </div>
          </div>

          <section className={styles.section} aria-labelledby="desc-heading">
            <h3 id="desc-heading" className={styles.sectionTitle}>
              Description
            </h3>
            <p className={styles.descriptionText}>
              {item.description || "No description provided."}
            </p>
          </section>

          <section className={styles.section} aria-labelledby="ac-heading">
            <h3 id="ac-heading" className={styles.sectionTitle}>
              <CheckSquare size={16} aria-hidden="true" />
              <span>Acceptance Criteria ({criteria.filter((c) => c.completed).length}/{criteria.length})</span>
            </h3>

            {criteria.length === 0 ? (
              <p className={styles.descriptionText}>No acceptance criteria defined.</p>
            ) : (
              <ul className={styles.criteriaList}>
                {criteria.map((c) => (
                  <li key={c.id} className={styles.criteriaItem}>
                    <input
                      type="checkbox"
                      id={`ac-${c.id}`}
                      className={styles.criteriaCheckbox}
                      checked={Boolean(c.completed)}
                      onChange={() => onToggleCriterion?.(item.id, c.id)}
                    />
                    <label
                      htmlFor={`ac-${c.id}`}
                      className={`${styles.criteriaText} ${
                        c.completed ? styles.criteriaCompleted : ""
                      }`}
                    >
                      {c.title}
                    </label>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className={styles.section} aria-labelledby="time-heading">
            <h3 id="time-heading" className={styles.sectionTitle}>
              <Clock size={16} aria-hidden="true" />
              <span>Time Spent</span>
            </h3>

            <div className={styles.timeBox}>
              <div className={styles.timeCurrent}>
                Total logged:{" "}
                <span className={styles.timeCurrentHighlight}>
                  {formatMinutes(item.timeSpentMinutes || 0)} ({item.timeSpentMinutes || 0} mins)
                </span>
              </div>

              <form onSubmit={handleLogTimeSubmit} className={styles.timeForm}>
                <input
                  type="number"
                  min="1"
                  step="1"
                  placeholder="Minutes to add"
                  aria-label="Additional minutes to log"
                  className={styles.timeInput}
                  value={minutesInput}
                  onChange={(e) => setMinutesInput(e.target.value)}
                />
                <button type="submit" className={styles.btnPrimary}>
                  Log Time
                </button>
              </form>

              {timeError && (
                <div className={styles.errorBanner} role="alert">
                  {timeError}
                </div>
              )}
            </div>
          </section>

          <section className={styles.section} aria-labelledby="block-heading">
            <h3 id="block-heading" className={styles.sectionTitle}>
              <ShieldAlert size={16} aria-hidden="true" />
              <span>Blocker Status</span>
            </h3>

            {isBlocked ? (
              <div className={styles.blockedBox} role="alert">
                <div style={{ display: "flex", alignItems: "center", gap: "6px" }}>
                  <AlertTriangle size={15} color="#f87171" aria-hidden="true" />
                  <strong style={{ fontSize: "13px", color: "#fca5a5" }}>
                    Work item is currently blocked
                  </strong>
                </div>
                <p className={styles.blockedReasonText}>
                  Reason: {item.blockedReason || "No reason given."}
                </p>
                <div>
                  <button
                    type="button"
                    className={styles.btnSecondary}
                    onClick={handleUnblock}
                  >
                    <Unlock size={14} style={{ marginRight: "4px" }} aria-hidden="true" />
                    Unblock Task
                  </button>
                </div>
              </div>
            ) : isBlockingFormOpen ? (
              <form onSubmit={handleConfirmBlock} className={styles.blockForm}>
                <label
                  htmlFor="blocker-reason"
                  style={{ fontSize: "12px", color: "#d4d4d8", fontWeight: 600 }}
                >
                  Mandatory Blocker Reason:
                </label>
                <textarea
                  id="blocker-reason"
                  className={styles.blockTextarea}
                  placeholder="Explain why this task is blocked..."
                  value={blockedReasonInput}
                  onChange={(e) => setBlockedReasonInput(e.target.value)}
                />

                {blockError && (
                  <div className={styles.errorBanner} role="alert">
                    {blockError}
                  </div>
                )}

                <div style={{ display: "flex", gap: "8px" }}>
                  <button type="submit" className={styles.btnDanger}>
                    Confirm Block
                  </button>
                  <button
                    type="button"
                    className={styles.btnSecondary}
                    onClick={() => {
                      setIsBlockingFormOpen(false);
                      setBlockError("");
                    }}
                  >
                    Cancel
                  </button>
                </div>
              </form>
            ) : (
              <div>
                <button
                  type="button"
                  className={styles.btnDanger}
                  onClick={() => setIsBlockingFormOpen(true)}
                >
                  <AlertTriangle size={14} style={{ marginRight: "4px" }} aria-hidden="true" />
                  Block Task
                </button>
              </div>
            )}
          </section>
        </div>
      </aside>
    </div>
  );
}
