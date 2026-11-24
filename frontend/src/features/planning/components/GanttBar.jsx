"use client";

import { useMemo } from "react";
import {
  getDatePositionPercent,
  getBarWidthPercent,
  formatDate,
  formatDuration,
} from "../utils/planning-utils";
import styles from "../styles/GanttBar.module.css";

export default function GanttBar({
  task,
  minTime,
  maxTime,
  zoom = "normal",
  isSelected = false,
  onSelect,
}) {
  const leftPct = useMemo(() => {
    return getDatePositionPercent(task.startAt || task.startDate, minTime, maxTime);
  }, [task.startAt, task.startDate, minTime, maxTime]);

  const widthPct = useMemo(() => {
    return getBarWidthPercent(
      task.startAt || task.startDate,
      task.finishAt || task.endDate,
      minTime,
      maxTime
    );
  }, [task.startAt, task.startDate, task.finishAt, task.endDate, minTime, maxTime]);

  const isDone = (task.status || "").toUpperCase() === "DONE";
  const isCritical = Boolean(task.isCritical);

  let barClass = styles.barNormal;
  if (isCritical && isDone) {
    barClass = styles.barCriticalDone;
  } else if (isCritical) {
    barClass = styles.barCritical;
  } else if (isDone) {
    barClass = styles.barDone;
  }

  const zoomClass =
    zoom === "compact"
      ? styles.zoomCompact
      : zoom === "detailed"
      ? styles.zoomDetailed
      : "";

  const handleKeyDown = (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onSelect?.(task);
    }
  };

  const progress = Number(task.progressPercent || (isDone ? 100 : 0));
  const slackDays = Number(task.totalFloat || 0);

  return (
    <div
      className={`${styles.barRow} ${zoomClass} ${isSelected ? styles.barRowSelected : ""}`}
      role="row"
      data-testid={`gantt-row-${task.itemKey || task.id}`}
    >
      <div className={styles.taskLabelCell} role="rowheader">
        <span className={styles.itemKey}>{task.itemKey || "DSP"}</span>
        <span className={styles.itemTitle} title={task.title}>
          {task.title}
        </span>
        {isCritical && (
          <span
            className={styles.criticalBadge}
            title="Critical Path Bottleneck (0 Slack)"
            aria-label="Critical Path"
          >
            CPM
          </span>
        )}
      </div>

      <div className={styles.barTrack} role="gridcell">
        <div
          className={`${styles.barContainer} ${barClass}`}
          style={{
            left: `${leftPct}%`,
            width: `${widthPct}%`,
          }}
          tabIndex={0}
          role="button"
          aria-label={`${task.itemKey}: ${task.title}. ${isCritical ? "Critical path task. " : ""}Starts ${formatDate(task.startAt)}, finishes ${formatDate(task.finishAt)}. Progress: ${progress}%. Slack: ${slackDays} days.`}
          onClick={() => onSelect?.(task)}
          onKeyDown={handleKeyDown}
          data-testid={`gantt-bar-${task.itemKey || task.id}`}
        >
          {progress > 0 && (
            <div
              className={styles.progressFill}
              style={{ width: `${Math.min(100, Math.max(0, progress))}%` }}
              aria-hidden="true"
            />
          )}

          <div className={styles.barContent}>
            <span className={styles.barTitle}>{task.title}</span>
            <div className={styles.barMeta}>
              <span>{task.assigneeName || "Unassigned"}</span>
              {task.estimateMinutes > 0 && (
                <span>• {formatDuration(task.estimateMinutes)}</span>
              )}
              {isCritical ? (
                <span className={styles.slackBadge}>0d slack</span>
              ) : slackDays > 0 ? (
                <span className={styles.slackBadge}>{slackDays}d float</span>
              ) : null}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
