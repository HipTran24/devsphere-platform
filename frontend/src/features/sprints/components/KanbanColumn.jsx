"use client";

import KanbanCard from "./KanbanCard";
import styles from "../styles/KanbanColumn.module.css";

export default function KanbanColumn({
  column,
  items = [],
  qualityGates = {},
  onOpenDrawer,
  onMoveCard,
}) {
  const pointsTally = items.reduce(
    (sum, item) => sum + (Number(item.storyPoints) || 0),
    0
  );

  const getIndicatorClass = () => {
    if (column.id === "TO_DO") return styles.indicatorSlate;
    if (column.id === "IN_PROGRESS") return styles.indicatorBlue;
    if (column.id === "IN_REVIEW") return styles.indicatorPurple;
    return styles.indicatorEmerald;
  };

  return (
    <section
      className={styles.column}
      aria-label={`${column.title} column with ${items.length} items`}
    >
      <div className={styles.columnHeader}>
        <div className={styles.titleGroup}>
          <span
            className={`${styles.columnIndicator} ${getIndicatorClass()}`}
            aria-hidden="true"
          />
          <h3 className={styles.columnTitle}>{column.title}</h3>
        </div>

        <div className={styles.badgesGroup}>
          <span
            className={styles.countBadge}
            aria-label={`${items.length} items`}
          >
            {items.length}
          </span>
          <span
            className={styles.pointsBadge}
            aria-label={`${pointsTally} story points`}
          >
            {pointsTally} SP
          </span>
        </div>
      </div>

      <div className={styles.cardList} role="list">
        {items.length === 0 ? (
          <div className={styles.emptyColumn}>
            <span>No items in this column</span>
          </div>
        ) : (
          items.map((item) => (
            <div key={item.id} role="listitem">
              <KanbanCard
                item={item}
                qualityGate={qualityGates[item.id]}
                onOpenDrawer={onOpenDrawer}
                onMoveCard={onMoveCard}
                canMoveLeft={column.id !== "TO_DO"}
                canMoveRight={column.id !== "DONE"}
              />
            </div>
          ))
        )}
      </div>
    </section>
  );
}
