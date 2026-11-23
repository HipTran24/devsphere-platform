"use client";

import { AlertCircle, Bell, Check, CheckCheck, ClipboardList, GitPullRequest } from "lucide-react";
import { useState } from "react";
import LoadingState from "../../../components/ui/LoadingState";
import ErrorState from "../../../components/ui/ErrorState";
import EmptyState from "../../../components/ui/EmptyState";
import { formatUiError } from "../../../components/ui/uiStateHelpers";
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

export default function WorkspaceNotifications({
    groups: initialGroups = notificationGroups,
    loading = false,
    error = null,
    onRetry,
}) {
    const [unreadOnly, setUnreadOnly] = useState(false);
    const [readIds, setReadIds] = useState([]);
    const [statusAnnouncement, setStatusAnnouncement] = useState("");

    const groups = initialGroups.map(({ label, visibleRows, items }) => ({
        label,
        visibleRows,
        items: items.map((item) => ({
            ...item,
            unread: item.unread && !readIds.includes(item.id),
        })),
    }));

    const unreadItems = groups.flatMap(({ items }) => items.filter(({ unread }) => unread));
    const unreadCount = unreadItems.length;
    const visibleGroups = groups
        .map(({ label, visibleRows, items }) => ({
            label,
            visibleRows,
            items: unreadOnly ? items.filter(({ unread }) => unread) : items,
        }))
        .filter(({ items }) => items.length > 0);

    function markItemAsRead(id, title) {
        setReadIds((previous) => (previous.includes(id) ? previous : [...previous, id]));
        setStatusAnnouncement(`Marked ${title} as read.`);
    }

    function markAllAsRead() {
        if (unreadCount === 0) return;
        const idsToMark = unreadItems.map(({ id }) => id);
        setReadIds((previous) => Array.from(new Set([...previous, ...idsToMark])));
        setStatusAnnouncement("All notifications marked as read.");
    }

    return (
        <section id="notifications" className={styles.notificationsContent} aria-label="Notifications">
            <div className={styles.notificationHeader}>
                <div className={styles.notificationFilters} role="group" aria-label="Filter notifications">
                    <button type="button" aria-pressed={!unreadOnly} onClick={() => setUnreadOnly(false)}>
                        All
                    </button>
                    <button type="button" aria-pressed={unreadOnly} onClick={() => setUnreadOnly(true)}>
                        Unread · {unreadCount}
                    </button>
                </div>

                {!loading && !error && (
                    <button
                        type="button"
                        className={styles.markAllReadButton}
                        disabled={unreadCount === 0}
                        onClick={markAllAsRead}
                    >
                        <CheckCheck size={15} aria-hidden="true" />
                        <span>Mark all as read</span>
                    </button>
                )}
            </div>

            <div className={styles.creationAnnouncement} role="status" aria-live="polite" aria-atomic="true">
                {statusAnnouncement}
            </div>

            {loading ? (
                <LoadingState variant="list" label="Loading notifications…" count={3} />
            ) : error ? (
                <ErrorState
                    compact
                    title={formatUiError(error).title}
                    message={formatUiError(error, "Unable to load notifications.").message}
                    code={formatUiError(error).code}
                    onRetry={onRetry}
                    retryLabel="Retry"
                />
            ) : visibleGroups.length === 0 ? (
                <EmptyState
                    compact
                    icon={Bell}
                    title={unreadOnly ? "You’re all caught up." : "No notifications yet."}
                    description={
                        unreadOnly
                            ? "All unread notifications have been reviewed."
                            : "Workspace updates and activity alerts will appear here."
                    }
                    actionLabel={unreadOnly ? "Show all notifications" : undefined}
                    onAction={unreadOnly ? () => setUnreadOnly(false) : undefined}
                />
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
                                                    onClick={() => markItemAsRead(id, title)}
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
