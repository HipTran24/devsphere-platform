import Link from "next/link";
import uiStyles from "../components/ui/UiStates.module.css";
import styles from "./StatePages.module.css";

export const metadata = {
    title: "Page Not Found | DevSphere",
};

export default function NotFound() {
    return (
        <main id="main-content" tabIndex={-1} className={styles.page}>
            <section className={`${styles.panel} ${styles.notFoundCard}`} aria-labelledby="not-found-title">
                <span className={styles.statusBadge}>Error 404</span>
                <h1 id="not-found-title">Page not found</h1>
                <p>
                    The page you requested doesn’t exist or may have been moved. Choose a destination below to continue.
                </p>
                <nav className={styles.navLinks} aria-label="Recovery links">
                    <Link href="/workspaces" className={uiStyles.primaryAction}>
                        My Workspaces
                    </Link>
                    <Link href="/workspace-home" className={uiStyles.secondaryAction}>
                        Workspace Home
                    </Link>
                    <Link href="/login" className={uiStyles.secondaryAction}>
                        Sign In
                    </Link>
                </nav>
            </section>
        </main>
    );
}
