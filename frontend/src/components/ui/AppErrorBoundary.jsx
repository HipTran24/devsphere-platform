"use client";

import { Component } from "react";
import Link from "next/link";
import ErrorState from "./ErrorState";
import { formatUiError } from "./uiStateHelpers";
import styles from "./UiStates.module.css";

export default class AppErrorBoundary extends Component {
    constructor(props) {
        super(props);
        this.state = { error: null };
        this.handleReset = this.handleReset.bind(this);
    }

    static getDerivedStateFromError(error) {
        return { error };
    }

    componentDidCatch(error, info) {
        if (typeof this.props.onError === "function") {
            this.props.onError(error, info);
        }
    }

    handleReset() {
        this.setState({ error: null });
    }

    render() {
        if (this.state.error) {
            const formatted = formatUiError(
                this.state.error,
                "An unexpected interface error occurred. You can retry or return to Sign In."
            );

            return (
                <main id="main-content" tabIndex={-1} className={styles.loadingPage}>
                    <ErrorState
                        headingLevel="h1"
                        title={formatted.title}
                        message={formatted.message}
                        code={formatted.code}
                        onRetry={this.handleReset}
                        retryLabel="Try again"
                        actions={
                            <Link href="/login" className={styles.secondaryAction}>
                                Back to Sign In
                            </Link>
                        }
                    />
                </main>
            );
        }

        return this.props.children;
    }
}
