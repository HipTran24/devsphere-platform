"use client";

import styles from "./UiStates.module.css";

export default function EmptyState({
    icon: Icon,
    title,
    description,
    actionLabel,
    onAction,
    compact = false,
    headingLevel = "h2",
    className = "",
}) {
    const Heading = headingLevel === "h1" ? "h1" : headingLevel === "h3" ? "h3" : "h2";

    return (
        <div
            className={`${styles.stateCard} ${styles.emptyCard} ${compact ? styles.stateCardCompact : ""} ${className}`.trim()}
            role="status"
            aria-live="polite"
        >
            {Icon && (
                <span className={styles.stateIconWrap} aria-hidden="true">
                    <Icon size={compact ? 18 : 22} strokeWidth={1.6} />
                </span>
            )}
            {title && <Heading className={styles.stateTitle}>{title}</Heading>}
            {description && <p className={styles.stateMessage}>{description}</p>}
            {actionLabel && onAction && (
                <div className={styles.stateActions}>
                    <button
                        type="button"
                        className={styles.primaryAction}
                        onClick={onAction}
                    >
                        {actionLabel}
                    </button>
                </div>
            )}
        </div>
    );
}
