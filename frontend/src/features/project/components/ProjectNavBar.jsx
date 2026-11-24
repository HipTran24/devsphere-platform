"use client";

import Link from "next/link";
import {
  ChevronRight,
  Folder,
  Layers,
  Kanban,
  Calendar,
  CheckCircle2,
} from "lucide-react";
import styles from "../styles/ProjectNavBar.module.css";

export default function ProjectNavBar({
  projectKey = "DSP",
  projectName = "DevSphere Platform",
  workspaceName = "DevSphere Core",
  workspaceId = "devsphere-core",
  activeTab = "backlog",
}) {
  const tabs = [
    {
      id: "backlog",
      label: "Backlog",
      href: `/p/${projectKey}/backlog`,
      icon: Layers,
    },
    {
      id: "sprints",
      label: "Sprints",
      href: `/p/${projectKey}/sprints`,
      icon: Kanban,
    },
    {
      id: "gantt",
      label: "Gantt",
      href: `/p/${projectKey}/gantt`,
      icon: Calendar,
    },
    {
      id: "plan-approval",
      label: "Plan Approval",
      href: `/p/${projectKey}/plan-approval`,
      icon: CheckCircle2,
    },
  ];

  return (
    <header className={styles.navContainer} role="banner">
      <div className={styles.topBar}>
        <nav aria-label="Breadcrumb" className={styles.breadcrumbNav}>
          <ol className={styles.breadcrumbList}>
            <li className={styles.breadcrumbItem}>
              <Link href="/workspaces" className={styles.breadcrumbLink}>
                <Folder size={15} aria-hidden="true" />
                <span>Workspaces</span>
              </Link>
            </li>
            <li className={styles.separator} aria-hidden="true">
              <ChevronRight size={14} />
            </li>
            <li className={styles.breadcrumbItem}>
              <Link
                href={`/workspaces/${workspaceId}/backlog`}
                className={styles.breadcrumbLink}
              >
                <span>{workspaceName}</span>
              </Link>
            </li>
            <li className={styles.separator} aria-hidden="true">
              <ChevronRight size={14} />
            </li>
            <li className={styles.breadcrumbItem}>
              <span className={styles.breadcrumbCurrent} aria-current="location">
                <span>{projectName}</span>
                <span className={styles.keyBadge} aria-label={`Project Key ${projectKey}`}>
                  {projectKey}
                </span>
              </span>
            </li>
          </ol>
        </nav>

        <div className={styles.topActions}>
          <Link href="/workspaces" className={styles.workspacesLink}>
            Switch Workspace
          </Link>
        </div>
      </div>

      <nav aria-label="Project tabs" className={styles.tabsNav}>
        <ul className={styles.tabList} role="tablist">
          {tabs.map((tab) => {
            const Icon = tab.icon;
            const isActive = activeTab === tab.id;
            return (
              <li key={tab.id} className={styles.tabItem} role="presentation">
                <Link
                  href={tab.href}
                  className={`${styles.tabLink} ${isActive ? styles.tabActive : ""}`}
                  role="tab"
                  aria-selected={isActive}
                  aria-current={isActive ? "page" : undefined}
                >
                  <Icon size={16} className={styles.tabIcon} aria-hidden="true" />
                  <span>{tab.label}</span>
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
    </header>
  );
}
