"use client";

import {
  ChevronDown,
  ChevronRight,
  Bookmark,
  CheckSquare,
  AlertCircle,
  AlertTriangle,
  Flame,
  ArrowUp,
  Minus,
  ArrowDown,
  Circle,
  Clock,
  CheckCircle2,
} from "lucide-react";
import { formatEstimate, formatStoryPoints } from "../utils/tree-utils";
import styles from "../styles/WorkItemTree.module.css";

function getTypeDetails(type) {
  const normalized = type === "STORY" ? "USER_STORY" : type;
  switch (normalized) {
    case "EPIC":
      return { label: "Epic", icon: Bookmark, className: styles.typeEpic };
    case "USER_STORY":
      return { label: "Story", icon: Bookmark, className: styles.typeStory };
    case "TASK":
      return { label: "Task", icon: CheckSquare, className: styles.typeTask };
    case "SUBTASK":
      return { label: "Subtask", icon: CheckSquare, className: styles.typeSubtask };
    case "BUG":
      return { label: "Bug", icon: AlertCircle, className: styles.typeBug };
    default:
      return { label: type, icon: CheckSquare, className: styles.typeTask };
  }
}

function getStatusDetails(status) {
  switch (status) {
    case "TO_DO":
      return { label: "To Do", icon: Circle, className: styles.statusToDo };
    case "IN_PROGRESS":
      return { label: "In Progress", icon: Clock, className: styles.statusInProgress };
    case "IN_REVIEW":
      return { label: "In Review", icon: Clock, className: styles.statusInReview };
    case "DONE":
      return { label: "Done", icon: CheckCircle2, className: styles.statusDone };
    default:
      return { label: status, icon: Circle, className: styles.statusToDo };
  }
}

function getPriorityDetails(priority) {
  switch (priority) {
    case "CRITICAL":
      return { label: "Critical", icon: Flame, className: styles.priorityCritical };
    case "HIGH":
      return { label: "High", icon: ArrowUp, className: styles.priorityHigh };
    case "MEDIUM":
      return { label: "Medium", icon: Minus, className: styles.priorityMedium };
    case "LOW":
      return { label: "Low", icon: ArrowDown, className: styles.priorityLow };
    default:
      return { label: priority, icon: Minus, className: styles.priorityMedium };
  }
}

export default function WorkItemRow({
  item,
  depth = 0,
  isCollapsed = false,
  hasChildren = false,
  onToggleCollapse,
  onItemClick,
  isFocused = false,
  onFocus,
  ref,
}) {
  const typeDetails = getTypeDetails(item.type);
  const TypeIcon = typeDetails.icon;

  const statusDetails = getStatusDetails(item.status);
  const StatusIcon = statusDetails.icon;

  const priorityDetails = getPriorityDetails(item.priority);
  const PriorityIcon = priorityDetails.icon;

  const estimateText = formatEstimate(item.estimateMinutes);
  const pointsText = formatStoryPoints(item.storyPoints);
  const estimateDisplay = pointsText && estimateText
    ? `${pointsText} • ${estimateText}`
    : pointsText || estimateText || "—";

  const assigneeInitials = item.assigneeName
    ? item.assigneeName
        .split(" ")
        .map((p) => p[0])
        .join("")
        .toUpperCase()
        .slice(0, 2)
    : null;

  return (
    <div
      ref={ref}
      onFocus={onFocus}
      className={`${styles.itemRow} ${isFocused ? styles.itemRowFocused : ""} ${item.isBlocked ? styles.itemRowBlocked : ""}`}
      style={{ paddingLeft: `${16 + depth * 22}px` }}
      onClick={() => onItemClick(item)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onItemClick(item);
        } else if (e.key === "ArrowRight" && isCollapsed && hasChildren) {
          e.preventDefault();
          onToggleCollapse(item.id);
        } else if (e.key === "ArrowLeft" && !isCollapsed && hasChildren) {
          e.preventDefault();
          onToggleCollapse(item.id);
        }
      }}
      tabIndex={0}
      role="row"
      aria-expanded={hasChildren ? !isCollapsed : undefined}
      aria-label={`${item.itemKey} ${item.title}, Status: ${statusDetails.label}, Priority: ${priorityDetails.label}`}
    >
      {/* Title & Hierarchy Cell */}
      <div className={styles.titleCell} role="cell">
        {hasChildren ? (
          <button
            type="button"
            className={styles.chevronBtn}
            onClick={(e) => {
              e.stopPropagation();
              onToggleCollapse(item.id);
            }}
            aria-label={isCollapsed ? `Expand ${item.itemKey}` : `Collapse ${item.itemKey}`}
          >
            {isCollapsed ? <ChevronRight size={16} /> : <ChevronDown size={16} />}
          </button>
        ) : (
          <span className={styles.chevronPlaceholder} aria-hidden="true" />
        )}

        <span className={styles.keyBadge}>{item.itemKey}</span>

        <span className={styles.titleText} title={item.title}>
          {item.title}
        </span>

        {hasChildren && item.childCount > 0 && (
          <span className={styles.childCountBadge} title={`${item.childCount} sub-items`}>
            {item.childCount}
          </span>
        )}

        {item.isBlocked && (
          <span
            className={styles.blockedFlag}
            title={item.blockedReason ? `Blocked: ${item.blockedReason}` : "Item is blocked"}
            aria-label={`Blocked: ${item.blockedReason || "Flagged as blocked"}`}
          >
            <AlertTriangle size={15} />
          </span>
        )}
      </div>

      {/* Type Badge */}
      <div role="cell">
        <span className={`${styles.typeBadge} ${typeDetails.className}`}>
          <TypeIcon size={12} aria-hidden="true" />
          <span>{typeDetails.label}</span>
        </span>
      </div>

      {/* Status Pill */}
      <div role="cell">
        <span className={`${styles.statusPill} ${statusDetails.className}`}>
          <StatusIcon size={12} aria-hidden="true" />
          <span>{statusDetails.label}</span>
        </span>
      </div>

      {/* Priority */}
      <div role="cell">
        <span className={`${styles.priorityBadge} ${priorityDetails.className}`}>
          <PriorityIcon size={13} aria-hidden="true" />
          <span>{priorityDetails.label}</span>
        </span>
      </div>

      {/* Assignee */}
      <div className={styles.assigneeCell} role="cell">
        {item.assigneeName ? (
          <>
            <span className={styles.assigneeAvatar} aria-hidden="true">
              {assigneeInitials}
            </span>
            <span className={styles.assigneeName} title={item.assigneeName}>
              {item.assigneeName}
            </span>
          </>
        ) : (
          <span className={styles.unassigned}>Unassigned</span>
        )}
      </div>

      {/* Estimate / Story Points */}
      <div className={styles.estimateCell} role="cell">
        {estimateDisplay}
      </div>

      {/* Actions */}
      <div className={styles.rowActions} role="cell">
        <button
          type="button"
          className={styles.actionBtn}
          onClick={(e) => {
            e.stopPropagation();
            onItemClick(item);
          }}
          aria-label={`Edit ${item.itemKey}`}
        >
          Edit
        </button>
      </div>
    </div>
  );
}
