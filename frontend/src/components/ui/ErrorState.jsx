"use client";

import { AlertTriangle, RotateCw } from "lucide-react";
import styles from "./UiStates.module.css";

export default function ErrorState({
    title = "Something went wrong",
    message = "We couldn’t complete this request. Please try again.",
    code,
    onRetry,
    retryLabel = "Try again",
    compact = false,
    headingLevel = "h2",
    actions,
    className = "",
}) {
    const Heading = headingLevel === "h1" ? "h1" : headingLevel === "h3" ? "h3" : "h2";

    return (
        <div
            className={`${styles.stateCard} ${styles.errorCard} ${compact ? styles.stateCardCompact : ""} ${className}`.trim()}
            role="alert"
            aria-live="assertive"
            aria-atomic="true"
        >
            <span className={`${styles.stateIconWrap} ${styles.errorIconWrap}`} aria-hidden="true">
                <AlertTriangle size={compact ? 18 : 22} />
            </span>
            <Heading className={styles.stateTitle}>{title}</Heading>
            <p className={styles.stateMessage}>{message}</p>
            {code && <code className={styles.stateCode}>{code}</code>}
            {(onRetry || actions) && (
                <div className={styles.stateActions}>
                    {onRetry && (
                        <button
                            type="button"
                            className={styles.primaryAction}
                            onClick={onRetry}
                        >
                            <RotateCw size={15} aria-hidden="true" />
                            <span>{retryLabel}</span>
                        </button>
                    )}
                    {actions}
                </div>
            )}
        </div>
    );
}
