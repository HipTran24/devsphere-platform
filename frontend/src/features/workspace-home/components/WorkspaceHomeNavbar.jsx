import styles from "../styles/WorkspaceHomeNavbar.module.css";
import Link from "next/link";
import WorkspaceAccountDropdown from "./WorkspaceAccountDropdown";

export default function WorkspaceHomeNavbar() {
    return (
        <header className={styles.header}>
            <nav className={styles.navbar} aria-label="Main navigation">
                <div className="devsphere-brand">
                    <span className="devsphere-logo-frame" aria-hidden="true" />
                    <span>DEVSPHERE</span>
                </div>
                <ul className={styles.navLinks}>
                    <li>
                        <Link href="/workspace-home" className={styles.navLink}>
                            Home
                        </Link>
                    </li>

                    <li>
                        <a href="#gioi-thieu" className={styles.navLink}>
                            About
                        </a>
                    </li>

                    <li>
                        <a href="#huong-dan" className={styles.navLink}>
                            Instructions
                        </a>
                    </li>

                    <li>
                        <a href="#lien-he" className={styles.navLink}>
                            Contact
                        </a>
                    </li>
                </ul>

                <WorkspaceAccountDropdown />
            </nav>
        </header>
    );
}
