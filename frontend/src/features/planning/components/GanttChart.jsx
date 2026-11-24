"use client";

import { useMemo, useState } from "react";
import {
  Calendar,
  AlertTriangle,
  ZoomIn,
  ZoomOut,
  Sparkles,
  Milestone as MilestoneIcon,
  Flag,
} from "lucide-react";
import GanttBar from "./GanttBar";
import {
  getDatePositionPercent,
  getBarWidthPercent,
  formatDate,
} from "../utils/planning-utils";
import styles from "../styles/GanttChart.module.css";

export default function GanttChart({
  tasks = [],
  dependencies = [],
  milestones = [],
  sprints = [],
  timelineBounds,
  timelineIntervals = [],
  viewMode = "DAYS",
  onViewModeChange,
  criticalOnly = false,
  onCriticalOnlyChange,
  zoom = "normal",
  onZoomChange,
  onSelectTask,
  selectedTaskId,
}) {
  const [activeMilestone, setActiveMilestone] = useState(null);
  const [currentTime] = useState(() => Date.now());

  const { minTime, maxTime } = timelineBounds || {};

  // Row height mapping based on zoom level
  const rowHeight = zoom === "compact" ? 32 : zoom === "detailed" ? 56 : 44;
  const baseOffset = 48 + 28 + 38; // header + sprints + milestones

  // Today position
  const todayPct = useMemo(() => {
    return getDatePositionPercent(currentTime, minTime, maxTime);
  }, [currentTime, minTime, maxTime]);

  // Compute dependency lines
  const dependencyLines = useMemo(() => {
    if (!dependencies.length || !tasks.length || !minTime || !maxTime) return [];

    const taskIndexMap = new Map();
    const taskObjMap = new Map();
    tasks.forEach((t, idx) => {
      const id = t.id || t.workItemId || t.itemKey;
      taskIndexMap.set(id, idx);
      taskObjMap.set(id, t);
    });

    const lines = [];
    dependencies.forEach((dep) => {
      const predId = dep.predecessorId || dep.from;
      const succId = dep.successorId || dep.to;

      if (taskIndexMap.has(predId) && taskIndexMap.has(succId)) {
        const pIdx = taskIndexMap.get(predId);
        const sIdx = taskIndexMap.get(succId);
        const predTask = taskObjMap.get(predId);
        const succTask = taskObjMap.get(succId);

        const x1Pct = getDatePositionPercent(
          predTask.finishAt || predTask.endDate,
          minTime,
          maxTime
        );
        const x2Pct = getDatePositionPercent(
          succTask.startAt || succTask.startDate,
          minTime,
          maxTime
        );

        const y1 = baseOffset + pIdx * rowHeight + rowHeight / 2;
        const y2 = baseOffset + sIdx * rowHeight + rowHeight / 2;

        const isCriticalLine = Boolean(predTask.isCritical && succTask.isCritical);

        lines.push({
          id: `${predId}-${succId}`,
          x1Pct,
          x2Pct,
          y1,
          y2,
          isCritical: isCriticalLine,
        });
      }
    });

    return lines;
  }, [dependencies, tasks, minTime, maxTime, rowHeight, baseOffset]);

  return (
    <div
      className={styles.chartContainer}
      role="region"
      aria-label="Gantt Timeline Chart"
      data-testid="gantt-chart-container"
    >
      {/* Toolbar */}
      <div className={styles.toolbar}>
        <div className={styles.toolbarLeft}>
          <div className={styles.segmentedControl} role="tablist" aria-label="Timeline View Scale">
            <button
              type="button"
              role="tab"
              aria-selected={viewMode === "DAYS"}
              className={`${styles.segmentBtn} ${viewMode === "DAYS" ? styles.segmentBtnActive : ""}`}
              onClick={() => onViewModeChange?.("DAYS")}
              data-testid="view-mode-days"
            >
              Days
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={viewMode === "WEEKS"}
              className={`${styles.segmentBtn} ${viewMode === "WEEKS" ? styles.segmentBtnActive : ""}`}
              onClick={() => onViewModeChange?.("WEEKS")}
              data-testid="view-mode-weeks"
            >
              Weeks
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={viewMode === "SPRINTS"}
              className={`${styles.segmentBtn} ${viewMode === "SPRINTS" ? styles.segmentBtnActive : ""}`}
              onClick={() => onViewModeChange?.("SPRINTS")}
              data-testid="view-mode-sprints"
            >
              Sprints
            </button>
          </div>

          <button
            type="button"
            className={`${styles.criticalToggleBtn} ${criticalOnly ? styles.criticalToggleActive : ""}`}
            onClick={() => onCriticalOnlyChange?.(!criticalOnly)}
            aria-pressed={criticalOnly}
            data-testid="critical-path-toggle"
          >
            <AlertTriangle size={15} aria-hidden="true" />
            <span>{criticalOnly ? "Showing Critical Path Only" : "Filter Critical Path"}</span>
          </button>
        </div>

        <div className={styles.toolbarRight}>
          <div className={styles.segmentedControl} aria-label="Zoom Level">
            <button
              type="button"
              className={`${styles.segmentBtn} ${zoom === "compact" ? styles.segmentBtnActive : ""}`}
              onClick={() => onZoomChange?.("compact")}
              title="Compact View"
              aria-label="Compact Zoom"
            >
              <ZoomOut size={14} />
            </button>
            <button
              type="button"
              className={`${styles.segmentBtn} ${zoom === "normal" ? styles.segmentBtnActive : ""}`}
              onClick={() => onZoomChange?.("normal")}
              title="Normal View"
              aria-label="Normal Zoom"
            >
              Normal
            </button>
            <button
              type="button"
              className={`${styles.segmentBtn} ${zoom === "detailed" ? styles.segmentBtnActive : ""}`}
              onClick={() => onZoomChange?.("detailed")}
              title="Detailed View"
              aria-label="Detailed Zoom"
            >
              <ZoomIn size={14} />
            </button>
          </div>

          <div className={styles.legend}>
            <div className={styles.legendItem}>
              <span className={`${styles.legendDot} ${styles.legendCritical}`} aria-hidden="true" />
              <span>Critical Path (CPM)</span>
            </div>
            <div className={styles.legendItem}>
              <span className={`${styles.legendDot} ${styles.legendNormal}`} aria-hidden="true" />
              <span>Standard Task</span>
            </div>
            <div className={styles.legendItem}>
              <span className={`${styles.legendDot} ${styles.legendDone}`} aria-hidden="true" />
              <span>Completed</span>
            </div>
            <div className={styles.legendItem}>
              <span className={styles.legendDiamond} aria-hidden="true" />
              <span>Milestone</span>
            </div>
          </div>
        </div>
      </div>

      {/* Main Gantt Timeline Scroll Area */}
      <div className={styles.timelineScrollArea} data-testid="gantt-scroll-area">
        <div className={styles.timelineInner}>
          {/* Header row with Date Intervals */}
          <div className={styles.timelineHeader} role="row">
            <div className={styles.headerLeftCell} role="columnheader">
              <span>Work Item</span>
              <span style={{ fontSize: "0.6875rem", fontWeight: 400 }}>{tasks.length} items</span>
            </div>

            <div className={styles.headerIntervals} role="row">
              {timelineIntervals.map((interval) => (
                <div
                  key={interval.id}
                  className={`${styles.intervalCell} ${interval.isWeekend ? styles.intervalWeekend : ""}`}
                  role="columnheader"
                >
                  <span className={styles.intervalLabel}>{interval.label}</span>
                  {interval.subLabel && (
                    <span className={styles.intervalSubLabel}>{interval.subLabel}</span>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* Sprints Boundary Row */}
          {sprints.length > 0 && (
            <div className={styles.sprintsRegionTrack} aria-label="Sprint Boundaries">
              <div
                style={{
                  position: "sticky",
                  left: 0,
                  width: 260,
                  minWidth: 260,
                  zIndex: 20,
                  padding: "0 12px",
                  fontSize: "0.6875rem",
                  fontWeight: 700,
                  textTransform: "uppercase",
                  color: "#93c5fd",
                  backgroundColor: "#14171d",
                  borderRight: "1px solid rgba(255,255,255,0.08)",
                  display: "flex",
                  alignItems: "center",
                  gap: 6,
                }}
              >
                <Calendar size={13} />
                <span>Sprints</span>
              </div>
              <div style={{ position: "relative", flex: 1, height: "100%" }}>
                {sprints.map((sprint) => {
                  const left = getDatePositionPercent(sprint.startDate, minTime, maxTime);
                  const width = getBarWidthPercent(
                    sprint.startDate,
                    sprint.endDate,
                    minTime,
                    maxTime
                  );
                  return (
                    <div
                      key={sprint.id}
                      className={styles.sprintPill}
                      style={{ left: `${left}%`, width: `${width}%` }}
                      title={`${sprint.name} (${formatDate(sprint.startDate)} - ${formatDate(sprint.endDate)})`}
                    >
                      <span>{sprint.name}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* Milestones Row */}
          {milestones.length > 0 && (
            <div className={styles.milestonesRow} aria-label="Committed Milestones">
              <div className={styles.milestoneLabelCell}>
                <MilestoneIcon size={14} />
                <span>Milestones</span>
              </div>
              <div style={{ position: "relative", flex: 1, height: "100%" }}>
                {milestones.map((m) => {
                  const left = getDatePositionPercent(m.targetDate, minTime, maxTime);
                  return (
                    <div
                      key={m.id}
                      className={styles.milestoneDiamondMarker}
                      style={{ left: `${left}%` }}
                      title={`${m.name} - Target: ${formatDate(m.targetDate)}`}
                      onClick={() => setActiveMilestone(m)}
                      data-testid={`milestone-marker-${m.id}`}
                    >
                      <div className={styles.diamondShape} aria-hidden="true" />
                      <span className={styles.milestoneText}>{m.name}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* Background Grid Columns */}
          <div className={styles.gridBackground} aria-hidden="true">
            {timelineIntervals.map((interval) => (
              <div
                key={`grid-${interval.id}`}
                className={`${styles.gridCol} ${interval.isWeekend ? styles.gridColWeekend : ""}`}
              />
            ))}
          </div>

          {/* Today vertical line */}
          {todayPct >= 0 && todayPct <= 100 && (
            <div
              className={styles.todayMarker}
              style={{ left: `calc(260px + ${todayPct}% * (100% - 260px) / 100)` }}
              aria-label="Today"
            >
              <span className={styles.todayLabel}>Today</span>
            </div>
          )}

          {/* SVG Dependency Connectors */}
          <svg className={styles.dependencySvgOverlay} aria-hidden="true">
            <defs>
              <marker
                id="arrow-normal"
                viewBox="0 0 10 10"
                refX="6"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#94a3b8" opacity="0.6" />
              </marker>
              <marker
                id="arrow-critical"
                viewBox="0 0 10 10"
                refX="6"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#ef4444" />
              </marker>
            </defs>

            {dependencyLines.map((line) => {
              // Stepped orthogonal bezier path: start from x1, horizontal midpoint, vertical step, end at x2
              const pathData = `M ${line.x1Pct}% ${line.y1} C ${(line.x1Pct + line.x2Pct) / 2}% ${line.y1}, ${(line.x1Pct + line.x2Pct) / 2}% ${line.y2}, ${line.x2Pct}% ${line.y2}`;
              return (
                <path
                  key={line.id}
                  d={pathData}
                  className={`${styles.depPath} ${line.isCritical ? styles.depPathCritical : ""}`}
                  markerEnd={line.isCritical ? "url(#arrow-critical)" : "url(#arrow-normal)"}
                />
              );
            })}
          </svg>

          {/* Task Rows */}
          {tasks.length === 0 ? (
            <div className={styles.emptyFiltered}>
              <Flag size={28} style={{ margin: "0 auto 10px", opacity: 0.5 }} />
              <p>No work items match the current filter.</p>
            </div>
          ) : (
            tasks.map((task, idx) => (
              <GanttBar
                key={task.id || task.itemKey || idx}
                task={task}
                minTime={minTime}
                maxTime={maxTime}
                zoom={zoom}
                isSelected={selectedTaskId === (task.id || task.itemKey)}
                onSelect={onSelectTask}
              />
            ))
          )}
        </div>
      </div>
    </div>
  );
}
