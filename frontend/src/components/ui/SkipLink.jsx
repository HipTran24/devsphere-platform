"use client";

import styles from "./SkipLink.module.css";

export default function SkipLink({
    targetId = "main-content",
    label = "Skip to main content",
}) {
    function handleActivate(event) {
        const target = document.getElementById(targetId);
        if (!target) return;
        event.preventDefault();
        if (!target.hasAttribute("tabindex")) {
            target.setAttribute("tabindex", "-1");
        }
        target.focus({ preventScroll: false });
        if (typeof target.scrollIntoView === "function") {
            target.scrollIntoView({ block: "start" });
        }
    }

    return (
        <a
            href={`#${targetId}`}
            className={styles.skipLink}
            onClick={handleActivate}
        >
            {label}
        </a>
    );
}
