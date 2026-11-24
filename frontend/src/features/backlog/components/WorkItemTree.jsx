"use client";

import { useState, useCallback, useMemo, useRef, useEffect } from "react";
import { FolderPlus } from "lucide-react";
import WorkItemRow from "./WorkItemRow";
import EmptyState from "../../../components/ui/EmptyState";
import { flattenTree } from "../utils/tree-utils";
import useKeyboardList from "../../../hooks/useKeyboardList";
import styles from "../styles/WorkItemTree.module.css";

export default function WorkItemTree({
  treeNodes = [],
  onItemClick,
  onCreateClick,
}) {
  const [collapsedIds, setCollapsedIds] = useState(() => new Set());
  const rowRefs = useRef([]);

  const toggleCollapse = useCallback((id) => {
    setCollapsedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  const visibleRows = useMemo(() => {
    return flattenTree(treeNodes, collapsedIds);
  }, [treeNodes, collapsedIds]);

  const { activeIndex, setActiveIndex, handleKeyDown } = useKeyboardList({
    count: visibleRows.length,
    initialIndex: 0,
    wrap: true,
  });

  useEffect(() => {
    if (activeIndex >= 0 && rowRefs.current[activeIndex]) {
      rowRefs.current[activeIndex].focus();
    }
  }, [activeIndex]);

  if (visibleRows.length === 0) {
    return (
      <div className={styles.emptyStateWrap}>
        <EmptyState
          icon={FolderPlus}
          title="No work items found"
          description="Try adjusting your search or filters, or create a new work item to populate the backlog."
          actionLabel="+ Create Work Item"
          onAction={onCreateClick}
        />
      </div>
    );
  }

  return (
    <div
      className={styles.treeContainer}
      role="table"
      aria-label="Backlog Work Items Tree"
      aria-rowcount={visibleRows.length}
      onKeyDown={(e) => {
        if (e.key === "ArrowUp" || e.key === "ArrowDown" || e.key === "Home" || e.key === "End") {
          handleKeyDown(e);
        }
      }}
    >
      <div className={styles.tableHeader} role="row">
        <span role="columnheader">Item / Title</span>
        <span role="columnheader">Type</span>
        <span role="columnheader">Status</span>
        <span role="columnheader">Priority</span>
        <span role="columnheader">Assignee</span>
        <span role="columnheader">Points / Est</span>
        <span role="columnheader">Actions</span>
      </div>

      <div className={styles.tableBody} role="rowgroup">
        {visibleRows.map((row, index) => (
          <WorkItemRow
            key={row.id}
            item={row}
            depth={row.depth}
            isCollapsed={row.isCollapsed}
            hasChildren={row.hasChildren}
            onToggleCollapse={toggleCollapse}
            onItemClick={onItemClick}
            isFocused={activeIndex === index}
            onFocus={() => setActiveIndex(index)}
            ref={(el) => {
              rowRefs.current[index] = el;
            }}
          />
        ))}
      </div>
    </div>
  );
}
