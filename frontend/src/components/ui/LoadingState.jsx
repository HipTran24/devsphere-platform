import styles from "./UiStates.module.css";

const DEFAULT_COUNTS = {
    page: 3,
    grid: 3,
    list: 4,
    inline: 1,
};

export default function LoadingState({
    variant = "page",
    label = "Loading…",
    count,
    className = "",
}) {
    const resolvedVariant = DEFAULT_COUNTS[variant] ? variant : "page";
    const itemCount = Math.max(1, count ?? DEFAULT_COUNTS[resolvedVariant]);
    const items = Array.from({ length: itemCount }, (_, index) => index);

    if (resolvedVariant === "inline") {
        return (
            <div
                className={`${styles.loadingInline} ${className}`.trim()}
                role="status"
                aria-live="polite"
                aria-busy="true"
                aria-label={label}
            >
                <span className={`${styles.loadingDot} ${styles.skeletonBlock}`} aria-hidden="true" />
                <span>{label}</span>
            </div>
        );
    }

    if (resolvedVariant === "list") {
        return (
            <div
                className={`${styles.loadingList} ${className}`.trim()}
                role="status"
                aria-live="polite"
                aria-busy="true"
                aria-label={label}
            >
                <span className={styles.srOnly}>{label}</span>
                {items.map((item) => (
                    <div key={item} className={styles.loadingListRow} aria-hidden="true">
                        <span className={`${styles.skeletonBlock} ${styles.loadingAvatar}`} />
                        <div className={styles.loadingRowBody}>
                            <span className={`${styles.skeletonBlock} ${styles.loadingCardTitle}`} />
                            <span className={`${styles.skeletonBlock} ${styles.loadingCardLineShort}`} />
                        </div>
                        <span className={`${styles.skeletonBlock} ${styles.loadingRowBadge}`} />
                    </div>
                ))}
            </div>
        );
    }

    if (resolvedVariant === "grid") {
        return (
            <div
                className={`${styles.loadingGrid} ${className}`.trim()}
                role="status"
                aria-live="polite"
                aria-busy="true"
                aria-label={label}
            >
                <span className={styles.srOnly}>{label}</span>
                {items.map((item) => (
                    <div key={item} className={styles.loadingCard} aria-hidden="true">
                        <span className={`${styles.skeletonBlock} ${styles.loadingCardTitle}`} />
                        <span className={`${styles.skeletonBlock} ${styles.loadingCardMeta}`} />
                        <span className={`${styles.skeletonBlock} ${styles.loadingCardLine}`} />
                        <span className={`${styles.skeletonBlock} ${styles.loadingCardLineShort}`} />
                    </div>
                ))}
            </div>
        );
    }

    return (
        <div
            className={`${styles.loadingPage} ${className}`.trim()}
            role="status"
            aria-live="polite"
            aria-busy="true"
            aria-label={label}
        >
            <span className={styles.srOnly}>{label}</span>
            <div className={styles.loadingHeader} aria-hidden="true">
                <span className={`${styles.skeletonBlock} ${styles.loadingTitleBar}`} />
                <span className={`${styles.skeletonBlock} ${styles.loadingActionBar}`} />
            </div>
            <div className={styles.loadingGrid} aria-hidden="true">
                {items.map((item) => (
                    <div key={item} className={styles.loadingCard}>
                        <span className={`${styles.skeletonBlock} ${styles.loadingCardTitle}`} />
                        <span className={`${styles.skeletonBlock} ${styles.loadingCardMeta}`} />
                        <span className={`${styles.skeletonBlock} ${styles.loadingCardLine}`} />
                        <span className={`${styles.skeletonBlock} ${styles.loadingCardLineShort}`} />
                    </div>
                ))}
            </div>
        </div>
    );
}
