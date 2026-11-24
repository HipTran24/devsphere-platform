"use client";

import { Calendar, CheckCircle2, Clock, ShieldCheck, Flame } from "lucide-react";
import { formatDateRange } from "../utils/sprint-utils";
import styles from "../styles/SprintHeader.module.css";

export default function SprintHeader({
  sprints = [],
  activeSprint = null,
  onSelectSprint,
  metrics = {},
  effectiveUser = null,
  simulatedRole = null,
  onSimulateRole,
}) {
  const status = (activeSprint?.status || "ACTIVE").toUpperCase();

  const getStatusBadgeClass = () => {
    if (status === "ACTIVE") return styles.statusActive;
    if (status === "PLANNED") return styles.statusPlanned;
    return styles.statusClosed;
  };

  const currentRole =
    simulatedRole ||
    effectiveUser?.role ||
    effectiveUser?.systemRole ||
    "PM";

  const {
    committedPoints = 0,
    completedPoints = 0,
    completionPercentage = 0,
    remainingItems = 0,
    totalSpentHours = "0.0",
  } = metrics;

  return (
    <section className={styles.headerContainer} aria-labelledby="sprint-header-title">
      <div className={styles.topRow}>
        <div className={styles.titleArea}>
          <div className={styles.metaRow}>
            {sprints.length > 1 ? (
              <select
                aria-label="Select sprint"
                className={styles.sprintSelector}
                value={activeSprint?.id || ""}
                onChange={(e) => onSelectSprint?.(e.target.value)}
              >
                {sprints.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name} ({s.status})
                  </option>
                ))}
              </select>
            ) : (
              <span className={styles.sprintHeading}>{activeSprint?.name || "Active Sprint"}</span>
            )}

            <span
              className={`${styles.statusBadge} ${getStatusBadgeClass()}`}
              aria-label={`Sprint status: ${status}`}
            >
              {status === "ACTIVE" && <Flame size={13} aria-hidden="true" />}
              {status === "PLANNED" && <Clock size={13} aria-hidden="true" />}
              {status === "CLOSED" && <CheckCircle2 size={13} aria-hidden="true" />}
              <span>{status}</span>
            </span>
          </div>

          <h2 id="sprint-header-title" className={styles.sprintHeading}>
            {activeSprint?.name || "Sprint Board"}
          </h2>

          {activeSprint?.goal && (
            <p className={styles.sprintGoal}>
              <strong>Goal:</strong> {activeSprint.goal}
            </p>
          )}

          <div className={styles.dateRow}>
            <Calendar size={14} aria-hidden="true" />
            <time>
              {formatDateRange(activeSprint?.startDate, activeSprint?.endDate)}
            </time>
          </div>
        </div>

        <div className={styles.controlsArea}>
          <div className={styles.roleSimulator}>
            <ShieldCheck size={16} aria-hidden="true" />
            <label htmlFor="role-select" className={styles.roleLabel}>
              Role:
            </label>
            <select
              id="role-select"
              aria-label="Simulate user role for acceptance flow"
              className={styles.roleSelect}
              value={currentRole}
              onChange={(e) => onSimulateRole?.(e.target.value)}
            >
              <option value="PM">Sarah PM (PM)</option>
              <option value="DEV">Alex Dev (DEV)</option>
              <option value="QA">Quinn QA (QA)</option>
              <option value="ADMIN">Admin User (ADMIN)</option>
            </select>
          </div>
        </div>
      </div>

      <div className={styles.metricsContainer}>
        <div className={styles.metricsSummary}>
          <div className={styles.statsGroup}>
            <div className={styles.statItem}>
              <span className={styles.statLabel}>Story Points</span>
              <span className={styles.statValue}>
                <span>{completedPoints} / {committedPoints} SP</span>
                <span className={styles.statSubtext}>({completionPercentage}%)</span>
              </span>
            </div>

            <div className={styles.statItem}>
              <span className={styles.statLabel}>Work Items</span>
              <span className={styles.statValue}>
                <span>{remainingItems} remaining</span>
              </span>
            </div>

            <div className={styles.statItem}>
              <span className={styles.statLabel}>Time Logged</span>
              <span className={styles.statValue}>
                <span>{totalSpentHours}h spent</span>
              </span>
            </div>
          </div>
        </div>

        <div
          role="progressbar"
          aria-valuenow={completionPercentage}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`Sprint progress: ${completionPercentage}% completed (${completedPoints} of ${committedPoints} story points)`}
          className={styles.progressBarTrack}
        >
          <div
            className={`${styles.progressBarFill} ${
              completionPercentage === 100 ? styles.progressBarComplete : ""
            }`}
            style={{ width: `${completionPercentage}%` }}
          />
        </div>
      </div>
    </section>
  );
}
