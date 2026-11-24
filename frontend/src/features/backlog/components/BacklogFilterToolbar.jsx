"use client";

import { Search, X, Plus, Network, ListTree } from "lucide-react";
import styles from "../styles/BacklogFilterToolbar.module.css";

export default function BacklogFilterToolbar({
  filters = {},
  onFilterChange,
  onResetFilters,
  totalItemsCount = 0,
  visibleItemsCount = 0,
  onCreateClick,
  activeView = "tree", // 'tree' or 'dag'
  onViewChange,
  assignees = [],
}) {
  const isFiltered =
    filters.search ||
    filters.type !== "ALL" ||
    filters.status !== "ALL" ||
    filters.priority !== "ALL" ||
    filters.assigneeId !== "ALL";

  return (
    <div className={styles.toolbar} role="search" aria-label="Backlog filters and actions">
      <div className={styles.topRow}>
        <div className={styles.searchWrap}>
          <Search size={16} className={styles.searchIcon} aria-hidden="true" />
          <input
            type="search"
            className={styles.searchInput}
            placeholder="Search work items by key or title..."
            value={filters.search || ""}
            onChange={(e) => onFilterChange("search", e.target.value)}
            aria-label="Search work items"
          />
          {filters.search && (
            <button
              type="button"
              className={styles.clearSearchBtn}
              onClick={() => onFilterChange("search", "")}
              aria-label="Clear search text"
            >
              <X size={14} />
            </button>
          )}
        </div>

        <div className={styles.actionsGroup}>
          {/* View toggle: Tree vs DAG */}
          <div className={styles.viewToggleGroup} role="group" aria-label="View toggle">
            <button
              type="button"
              className={`${styles.viewToggleBtn} ${activeView === "tree" ? styles.viewToggleBtnActive : ""}`}
              onClick={() => onViewChange("tree")}
              aria-pressed={activeView === "tree"}
            >
              <ListTree size={15} aria-hidden="true" />
              <span>Tree View</span>
            </button>
            <button
              type="button"
              className={`${styles.viewToggleBtn} ${activeView === "dag" ? styles.viewToggleBtnActive : ""}`}
              onClick={() => onViewChange("dag")}
              aria-pressed={activeView === "dag"}
            >
              <Network size={15} aria-hidden="true" />
              <span>Dependency DAG</span>
            </button>
          </div>

          <button
            type="button"
            className={styles.createBtn}
            onClick={onCreateClick}
            aria-label="Create Work Item"
          >
            <Plus size={16} aria-hidden="true" />
            <span>Create Item</span>
          </button>
        </div>
      </div>

      <div className={styles.filtersRow}>
        <div className={styles.filterField}>
          <label htmlFor="filter-type" className={styles.filterLabel}>Type</label>
          <select
            id="filter-type"
            className={styles.filterSelect}
            value={filters.type || "ALL"}
            onChange={(e) => onFilterChange("type", e.target.value)}
          >
            <option value="ALL">All Types</option>
            <option value="EPIC">Epic</option>
            <option value="USER_STORY">User Story</option>
            <option value="TASK">Task</option>
            <option value="SUBTASK">Subtask</option>
            <option value="BUG">Bug</option>
          </select>
        </div>

        <div className={styles.filterField}>
          <label htmlFor="filter-status" className={styles.filterLabel}>Status</label>
          <select
            id="filter-status"
            className={styles.filterSelect}
            value={filters.status || "ALL"}
            onChange={(e) => onFilterChange("status", e.target.value)}
          >
            <option value="ALL">All Statuses</option>
            <option value="TO_DO">To Do</option>
            <option value="IN_PROGRESS">In Progress</option>
            <option value="IN_REVIEW">In Review</option>
            <option value="DONE">Done</option>
          </select>
        </div>

        <div className={styles.filterField}>
          <label htmlFor="filter-priority" className={styles.filterLabel}>Priority</label>
          <select
            id="filter-priority"
            className={styles.filterSelect}
            value={filters.priority || "ALL"}
            onChange={(e) => onFilterChange("priority", e.target.value)}
          >
            <option value="ALL">All Priorities</option>
            <option value="CRITICAL">Critical</option>
            <option value="HIGH">High</option>
            <option value="MEDIUM">Medium</option>
            <option value="LOW">Low</option>
          </select>
        </div>

        {assignees.length > 0 && (
          <div className={styles.filterField}>
            <label htmlFor="filter-assignee" className={styles.filterLabel}>Assignee</label>
            <select
              id="filter-assignee"
              className={styles.filterSelect}
              value={filters.assigneeId || "ALL"}
              onChange={(e) => onFilterChange("assigneeId", e.target.value)}
            >
              <option value="ALL">All Assignees</option>
              {assignees.map((user) => (
                <option key={user.id} value={user.id}>
                  {user.displayName || user.name || user.email}
                </option>
              ))}
            </select>
          </div>
        )}

        {isFiltered && (
          <button
            type="button"
            className={styles.resetBtn}
            onClick={onResetFilters}
            aria-label="Reset all filters"
          >
            Reset Filters
          </button>
        )}

        <div className={styles.statusSummary} aria-live="polite">
          Showing {visibleItemsCount} of {totalItemsCount} items
        </div>
      </div>
    </div>
  );
}
