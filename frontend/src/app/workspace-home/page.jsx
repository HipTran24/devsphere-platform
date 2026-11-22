import WorkspaceHomeNavbar from "../../features/workspace-home/components/WorkspaceHomeNavbar";
import WorkspaceHomeFooter from "../../features/workspace-home/components/WorkspaceHomeFooter";
import WorkspaceSprintMotion from "../../features/workspace-home/components/WorkspaceSprintMotion";
import styles from "../../features/workspace-home/styles/WorkspaceHomeNavbar.module.css";
import WorkspaceHomeMain from "../../features/workspace-home/components/WorkspaceHomeMain";

export const metadata = {
    title: "Workspace Home | DevSphere",
};

export default function WorkspaceHomePage() {
    return (
        <div className={styles.workspaceHome}>
            <WorkspaceHomeNavbar />
            <WorkspaceHomeMain>
                <WorkspaceSprintMotion />
            </WorkspaceHomeMain>
            <WorkspaceHomeFooter />
        </div>
    );
}
