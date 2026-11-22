import CreateWorkspaceDialog from "./CreateWorkspaceDialog";
import styles from "../styles/WorkspaceHomeHero.module.css";

export default function WorkspaceHomeHero({ workspaces, onCreate }) {
    return (
        <section className={styles.hero}>
            <h1 className={styles.title}>
                The platform for{" "}
                <span className={styles.accent}>software delivery</span>
            </h1>

            <p className={styles.description}>
                Plan together, connect your GitHub repositories,
                <br />
                and track progress from the first task.
            </p>

            <div className={styles.buttons}>
                <CreateWorkspaceDialog
                    className={`devsphere-button ${styles.primaryButton}`}
                    workspaces={workspaces}
                    onCreate={onCreate}
                />

                <a href="#my-workspaces" className="devsphere-button">
                    My workspaces
                </a>
            </div>
        </section>
    );
}
