"use client";

import Link from "next/link";
import ErrorState from "../components/ui/ErrorState";
import { formatUiError } from "../components/ui/uiStateHelpers";
import uiStyles from "../components/ui/UiStates.module.css";
import styles from "./StatePages.module.css";

export default function AppError({ error, reset, retry }) {
    const formatted = formatUiError(
        error,
        "We hit an unexpected error while loading this page. Try again or return to Sign In."
    );

    function handleRetry() {
        if (typeof retry === "function") {
            retry();
            return;
        }
        if (typeof reset === "function") {
            reset();
        }
    }

    return (
        <main id="main-content" tabIndex={-1} className={styles.page}>
            <div className={styles.panel}>
                <ErrorState
                    headingLevel="h1"
                    title={formatted.title}
                    message={formatted.message}
                    code={formatted.code}
                    onRetry={handleRetry}
                    retryLabel="Try again"
                    actions={
                        <Link href="/login" className={uiStyles.secondaryAction}>
                            Back to Sign In
                        </Link>
                    }
                />
            </div>
        </main>
    );
}
