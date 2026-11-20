import styles from "../styles/Recovery.module.css";

export default function RecoveryBadge({ kind }) {
    if (kind === "mail") return <div className={styles.lockIcon} aria-hidden="true">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 19H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v6" />
            <path d="m2 7 10 6L22 7" />
            <path d="m16 19 2 2 4-4" stroke="var(--color-text)" />
        </svg>
    </div>;
    if (kind === "lock") return <div className={styles.lockIcon} aria-hidden="true">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
            <path d="M8 10V7a4 4 0 0 1 8 0v3" />
            <rect x="4" y="10" width="16" height="11" rx="2" />
            <circle cx="12" cy="14.5" r="1.2" fill="currentColor" stroke="none" />
            <path d="M12 15.7V18" />
        </svg>
    </div>;
    return <div className={styles.lockIcon} aria-hidden="true">
        <svg width="52" height="52" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
            <path d="m12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6Z" />
            <circle cx="12" cy="10.5" r="2" />
            <path d="M12 12.5V17" />
        </svg>
    </div>;
}
