"use client";

import {
  Code,
  FileText,
  AlertTriangle,
  GitPullRequest,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";
import QualityGateBadge from "./QualityGateBadge";
import styles from "../styles/KanbanCard.module.css";

export default function KanbanCard({
  item,
  qualityGate = null,
  onOpenDrawer,
  onMoveCard,
  canMoveLeft = false,
  canMoveRight = false,
}) {
  const isCode = item.workKind === "CODE" || !item.workKind;
  const isBlocked = Boolean(item.isBlocked);
  const priority = (item.priority || "MEDIUM").toUpperCase();

  const getPriorityClass = () => {
    if (priority === "CRITICAL") return styles.priorityCritical;
    if (priority === "HIGH") return styles.priorityHigh;
    if (priority === "LOW") return styles.priorityLow;
    return styles.priorityMedium;
  };

  const getInitials = (name = "") => {
    if (!name) return "?";
    return name
      .split(" ")
      .map((part) => part[0])
      .join("")
      .toUpperCase()
      .slice(0, 2);
  };

  const handleKeyDown = (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onOpenDrawer?.(item);
    }
  };

  return (
    <article
      className={`${styles.card} ${isBlocked ? styles.cardBlocked : ""}`}
      tabIndex={0}
      onClick={() => onOpenDrawer?.(item)}
      onKeyDown={handleKeyDown}
      aria-label={`${item.itemKey}: ${item.title}. Priority ${priority}. ${
        isBlocked ? `Blocked: ${item.blockedReason || "Blocked"}` : ""
      }`}
    >
      <div className={styles.headerRow}>
        <div className={styles.keyGroup}>
          <span className={styles.keyBadge}>{item.itemKey}</span>
          <span
            className={`${styles.kindBadge} ${
              isCode ? styles.kindCode : styles.kindNonCode
            }`}
          >
            {isCode ? (
              <>
                <Code size={10} aria-hidden="true" />
                <span>Code</span>
              </>
            ) : (
              <>
                <FileText size={10} aria-hidden="true" />
                <span>Non-Code</span>
              </>
            )}
          </span>
        </div>

        <span className={`${styles.priorityBadge} ${getPriorityClass()}`}>
          {priority}
        </span>
      </div>

      <h3 className={styles.title}>{item.title}</h3>

      {isBlocked && (
        <div className={styles.blockedAlert} role="alert">
          <AlertTriangle size={13} aria-hidden="true" />
          <span>{item.blockedReason || "Work item is blocked"}</span>
        </div>
      )}

      <div className={styles.badgeRow}>
        {item.prNumber && (
          <span
            className={styles.prBadge}
            aria-label={`GitHub Pull Request #${item.prNumber}`}
          >
            <GitPullRequest size={12} aria-hidden="true" />
            <span>#{item.prNumber}</span>
          </span>
        )}

        {(qualityGate || isCode) && (
          <QualityGateBadge gate={qualityGate} itemKey={item.itemKey} />
        )}
      </div>

      <div className={styles.footerRow}>
        <div className={styles.assigneeGroup}>
          <div
            className={styles.avatar}
            aria-label={`Assigned to ${item.assigneeName || "Unassigned"}`}
          >
            {getInitials(item.assigneeName)}
          </div>
          <span className={styles.assigneeName}>
            {item.assigneeName || "Unassigned"}
          </span>
        </div>

        {item.storyPoints != null && (
          <span className={styles.pointsBadge} aria-label={`${item.storyPoints} Story Points`}>
            {item.storyPoints} SP
          </span>
        )}
      </div>

      <div className={styles.actionsRow} onClick={(e) => e.stopPropagation()}>
        <button
          type="button"
          className={`${styles.moveButton} ${
            !canMoveLeft ? styles.moveButtonDisabled : ""
          }`}
          disabled={!canMoveLeft}
          onClick={() => onMoveCard?.(item.id, "prev")}
          aria-label={`Move ${item.itemKey} to previous column`}
        >
          <ChevronLeft size={13} aria-hidden="true" />
          <span>Prev</span>
        </button>

        <button
          type="button"
          className={`${styles.moveButton} ${
            !canMoveRight ? styles.moveButtonDisabled : ""
          }`}
          disabled={!canMoveRight}
          onClick={() => onMoveCard?.(item.id, "next")}
          aria-label={`Move ${item.itemKey} to next column`}
        >
          <span>Next</span>
          <ChevronRight size={13} aria-hidden="true" />
        </button>
      </div>
    </article>
  );
}
