import LoadingState from "../components/ui/LoadingState";
import styles from "./StatePages.module.css";

export default function Loading() {
    return (
        <div role="status" aria-live="polite" className={styles.page}>
            <LoadingState variant="page" label="Loading DevSphere…" />
        </div>
    );
}
