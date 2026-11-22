"use client";

import { AlertCircle, Bell, Check, ClipboardList, GitPullRequest } from "lucide-react";
import { useState } from "react";
import styles from "../styles/WorkspaceHomeContent.module.css";

const notificationGroups = [
    {
        label: "Today",
        visibleRows: 2,
        items: [
            {
                id: "workspace-search",
                Icon: ClipboardList,
                title: "Task assigned",
                body: "You were assigned Implement workspace search.",
                workspace: "DevSphere",
                category: "Task",
                time: "10:42",
                unread: true,
            },
            {
                id: "pull-request-42",
                Icon: GitPullRequest,
                title: "Pull request ready for review",
                body: "Your review was requested on PR #42.",
                workspace: "DevSphere",
                category: "GitHub",
                time: "09:15",
                unread: true,
            },
            {
                id: "sprint-plan-ready",
                Icon: ClipboardList,
                title: "Planning proposal ready",
                body: "The revised sprint plan is ready for review.",
                workspace: "DevSphere",
                category: "Planning",
                time: "08:45",
                unread: true,
            },
            {
                id: "deadline-updated",
                Icon: ClipboardList,
                title: "Task deadline updated",
                body: "The deadline for dashboard testing was moved to 24 Nov.",
                workspace: "Client Portal",
                category: "Task",
                time: "08:10",
                unread: false,
            },
        ],
    },
    {
        label: "Earlier",
        visibleRows: 1,
        items: [
            {
                id: "ci-build",
                Icon: AlertCircle,
                title: "CI checks failed",
                body: "The build failed on the Client Portal branch.",
                workspace: "Client Portal",
                category: "GitHub",
                time: "18 Nov",
                unread: false,
            },
            {
                id: "accessibility-task-blocked",
                Icon: AlertCircle,
                title: "Task blocked",
                body: "Accessibility testing is waiting for the updated design.",
                workspace: "Design Library",
                category: "Alerts",
                time: "17 Nov",
                unread: true,
            },
            {
                id: "pull-request-36",
                Icon: GitPullRequest,
                title: "Pull request merged",
                body: "PR #36 was merged into the main branch.",
                workspace: "DevSphere",
                category: "GitHub",
                time: "16 Nov",
                unread: false,
            },
        ],
    },
];

export default function WorkspaceNotifications() {
    const [unreadOnly, setUnreadOnly] = useState(false);
    const [readIds, setReadIds] = useState([]);

    const groups = notificationGroups.map(({ label, visibleRows, items }) => ({
        label,
        visibleRows,
        items: items.map((item) => ({
            ...item,
            unread: item.unread && !readIds.includes(item.id),
        })),
    }));

    const unreadCount = groups.reduce(
        (total, { items }) => total + items.filter(({ unread }) => unread).length,
        0
    );
    const visibleGroups = groups
        .map(({ label, visibleRows, items }) => ({
            label,
            visibleRows,
            items: unreadOnly ? items.filter(({ unread }) => unread) : items,
        }))
        .filter(({ items }) => items.length > 0);

    return (
        <section id="notifications" className={styles.notificationsContent} aria-label="Notifications">
            <div className={styles.notificationFilters} role="group" aria-label="Filter notifications">
                <button type="button" aria-pressed={!unreadOnly} onClick={() => setUnreadOnly(false)}>
                    All
                </button>
                <button type="button" aria-pressed={unreadOnly} onClick={() => setUnreadOnly(true)}>
                    Unread · {unreadCount}
                </button>
            </div>

            {visibleGroups.length === 0 ? (
                <div className={styles.emptyNotifications} role="status">
                    <Bell size={24} aria-hidden="true" />
                    <p>{unreadOnly ? "You’re all caught up." : "No notifications yet."}</p>
                </div>
            ) : (
                visibleGroups.map(({ label, visibleRows, items }) => (
                    <div className={styles.notificationGroup} key={label}>
                        <h2>{label}</h2>
                        <div
                            className={styles.notificationScroll}
                            style={{ "--notification-visible-rows": visibleRows }}
                            role="region"
                            aria-label={`${label} notifications`}
                            tabIndex={0}
                        >
                            <ul>
                                {items.map(({ id, Icon, title, body, workspace, category, time, unread }) => (
                                    <li key={id} className={styles.notificationItem} data-unread={unread}>
                                        <span className={styles.notificationIcon}>
                                            <Icon size={18} aria-hidden="true" />
                                        </span>
                                        <div className={styles.notificationText}>
                                            <h3>{title}</h3>
                                            <p>{body}</p>
                                            <small className={styles.notificationContext}>
                                                {workspace}
                                                <span className={styles.notificationSeparator} aria-hidden="true">·</span>
                                                {category}
                                            </small>
                                        </div>
                                        <div className={styles.notificationMeta}>
                                            <span>{time}</span>
                                            {unread && (
                                                <button
                                                    type="button"
                                                    aria-label={`Mark as read: ${title}`}
                                                    title="Mark as read"
                                                    onClick={() => setReadIds((previous) => [...previous, id])}
                                                >
                                                    <Check size={16} aria-hidden="true" />
                                                </button>
                                            )}
                                        </div>
                                    </li>
                                ))}
                            </ul>
                        </div>
                    </div>
                ))
            )}
        </section>
    );
}

