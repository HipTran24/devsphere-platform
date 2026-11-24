"use client";

import { useState } from "react";
import WorkspaceHomeContent, { sampleWorkspaces } from "./WorkspaceHomeContent";
import WorkspaceHomeHero from "./WorkspaceHomeHero";
import styles from "../styles/WorkspaceHomeContent.module.css";

export default function WorkspaceHomeMain({ children }) {
    const [workspaces, setWorkspaces] = useState(sampleWorkspaces);
    const [announcement, setAnnouncement] = useState("");

    function createWorkspace(workspace) {
        setWorkspaces((current) => [
            { ...workspace, role: "Owner", status: "SETUP_INCOMPLETE", createdBy: "You" },
            ...current,
        ]);
        setAnnouncement(`${workspace.name} was added to My Workspaces for this preview.`);
    }

    return (
        <main id="main-content" tabIndex={-1}>
            <WorkspaceHomeHero workspaces={workspaces} onCreate={createWorkspace} />
            <div className={styles.creationAnnouncement} role="status">{announcement}</div>
            {children}
            <WorkspaceHomeContent workspaces={workspaces} />
        </main>
    );
}
