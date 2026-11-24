"use client";

import KanbanColumn from "./KanbanColumn";
import { KANBAN_COLUMNS, partitionWorkItemsByColumn } from "../utils/sprint-utils";
import styles from "../styles/SprintBoard.module.css";

export default function SprintBoard({
  workItems = [],
  qualityGates = {},
  onOpenDrawer,
  onMoveCard,
}) {
  const partitioned = partitionWorkItemsByColumn(workItems);

  return (
    <div
      className={styles.boardContainer}
      role="region"
      aria-label="Sprint Kanban Board with 4 columns"
    >
      {KANBAN_COLUMNS.map((column) => (
        <KanbanColumn
          key={column.id}
          column={column}
          items={partitioned[column.id] || []}
          qualityGates={qualityGates}
          onOpenDrawer={onOpenDrawer}
          onMoveCard={onMoveCard}
        />
      ))}
    </div>
  );
}
