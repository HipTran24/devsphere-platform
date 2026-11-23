import { Mail } from "lucide-react";
import styles from "../styles/WorkspaceHomeFooter.module.css";

export default function WorkspaceHomeFooter() {
    return (
        <footer id="lien-he" className={styles.footer}>
            <div className={styles.inner}>
                <div className={styles.socialLinks}>
                    <a
                        className={styles.socialIcon}
                        href="https://github.com/HipTran24/devsphere-platform"
                        aria-label="DevSphere on GitHub"
                        title="GitHub"
                    >
                        <svg viewBox="0 0 16 16" width="18" height="18" fill="currentColor" aria-hidden="true">
                            <path d="M8 0a8 8 0 0 0-2.53 15.59c.4.07.55-.17.55-.38v-1.49c-2.23.48-2.7-.94-2.7-.94-.36-.93-.89-1.18-.89-1.18-.73-.5.06-.49.06-.49.8.06 1.22.82 1.22.82.72 1.22 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.01.08-2.11 0 0 .67-.22 2.2.82A7.7 7.7 0 0 1 8 3.86c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.91.08 2.11.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48v2.21c0 .21.15.46.55.38A8 8 0 0 0 8 0Z" />
                        </svg>
                    </a>
                    <span className={styles.socialIcon} role="img" aria-label="LinkedIn, link unavailable" title="LinkedIn · Coming soon">
                        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
                            <path d="M5 3.5a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4ZM3.2 9.3h3.6v11.5H3.2V9.3Zm6 0h3.5v1.6h.1a3.8 3.8 0 0 1 3.4-1.9c3.6 0 4.3 2.4 4.3 5.4v6.4h-3.7v-5.7c0-1.4 0-3-1.8-3s-2.1 1.4-2.1 2.9v5.8H9.2V9.3Z" />
                        </svg>
                    </span>
                    <span className={styles.socialIcon} role="img" aria-label="Email, link unavailable" title="Email · Coming soon">
                        <Mail size={18} aria-hidden="true" />
                    </span>
                    <span className={styles.socialIcon} role="img" aria-label="YouTube, link unavailable" title="YouTube · Coming soon">
                        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
                            <path fillRule="evenodd" d="M21.6 7.2a2.5 2.5 0 0 0-1.8-1.8C18.2 5 12 5 12 5s-6.2 0-7.8.4a2.5 2.5 0 0 0-1.8 1.8C2 8.8 2 12 2 12s0 3.2.4 4.8a2.5 2.5 0 0 0 1.8 1.8C5.8 19 12 19 12 19s6.2 0 7.8-.4a2.5 2.5 0 0 0 1.8-1.8C22 15.2 22 12 22 12s0-3.2-.4-4.8ZM10 15V9l5 3-5 3Z" />
                        </svg>
                    </span>
                </div>

                <small className={styles.copyright}>© 2026 DevSphere</small>
            </div>
        </footer>
    );
}

