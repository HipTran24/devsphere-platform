import LoadingState from "../components/ui/LoadingState";
import styles from "./StatePages.module.css";

export default function Loading() {
    return (
        <main id="main-content" tabIndex={-1} className={styles.page}>
            <LoadingState variant="page" label="Loading DevSphere…" />
        </main>
    );
}
