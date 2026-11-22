"use client";

import {
    ArrowDown,
    ArrowUp,
    ArrowUpRight,
    Bell,
    BriefcaseBusiness,
    ChevronLeft,
    ChevronRight,
    CircleCheck,
    Clock,
    FolderCode,
    Inbox,
    MailPlus,
    RotateCcw,
    Search,
} from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import WorkspaceNotifications from "./WorkspaceNotifications";
import styles from "../styles/WorkspaceHomeContent.module.css";

const menuItems = [
    { href: "#my-workspaces", label: "Workspaces", Icon: BriefcaseBusiness },
    { href: "#invitations", label: "Invites", Icon: Inbox },
    { href: "#notifications", label: "Notifications", Icon: Bell },
];

const workspacesPerPage = 6;
const invitationsPerPage = 4;

export const sampleWorkspaces = [
    {
        name: "DevSphere",
        createdBy: "Kieu Tien",
        role: "Owner",
        status: "ACTIVE",
    },
    {
        name: "Client Portal",
        createdBy: "Linh Pham",
        role: "Member",
        status: "ACTIVE",
    },
    {
        name: "Engineering Tools",
        createdBy: "Kieu Tien",
        role: "Owner",
        status: "SETUP_INCOMPLETE",
    },
    {
        name: "Design Library",
        createdBy: "Minh Tran",
        role: "Member",
        status: "ACTIVE",
    },
    {
        name: "Release Hub",
        createdBy: "Kieu Tien",
        role: "Owner",
        status: "ACTIVE",
    },
    {
        name: "QA Lab",
        createdBy: "Alex Nguyen",
        role: "Member",
        status: "ACTIVE",
    },
];

const sampleInvitations = [
    { id: "platform-ops", workspace: "Platform Ops", inviter: "Kieu Tien", expiresAt: "2026-12-01T18:00:00Z" },
    { id: "research-lab", workspace: "Research Lab", inviter: "Alex Nguyen", expiresAt: "2026-12-03T18:00:00Z" },
    { id: "documentation", workspace: "Documentation", inviter: "Minh Tran", expiresAt: "2026-12-05T18:00:00Z" },
    { id: "developer-hub", workspace: "Developer Hub", inviter: "Linh Pham", expiresAt: "2026-12-08T18:00:00Z" },
];

const invitationDateFormat = new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeZone: "UTC" });

export default function WorkspaceHomeContent({ workspaces = sampleWorkspaces, pendingInvitations = sampleInvitations }) {
    const invitationGradientPrefix = useId();
    const workspaceGradientPrefix = useId();
    const [invitationResponses, setInvitationResponses] = useState({});
    const [invitePage, setInvitePage] = useState(1);
    const [inviteFilter, setInviteFilter] = useState("all");
    const [currentHref, setCurrentHref] = useState(menuItems[0].href);
    const [searchOpen, setSearchOpen] = useState(false);
    const [query, setQuery] = useState("");
    const [page, setPage] = useState(1);
    const [nameSort, setNameSort] = useState(null);
    const searchInputRef = useRef(null);
    const searchButtonRef = useRef(null);
    const matchingWorkspaces = workspaces.filter(({ name }) =>
        name.toLowerCase().includes(query.trim().toLowerCase())
    );
    if (nameSort) {
        matchingWorkspaces.sort((first, second) =>
            nameSort === "ascending"
                ? first.name.localeCompare(second.name)
                : second.name.localeCompare(first.name)
        );
    }
    const pageCount = Math.ceil(matchingWorkspaces.length / workspacesPerPage);
    const currentPage = Math.min(page, Math.max(1, pageCount));
    const pageStart = (currentPage - 1) * workspacesPerPage;
    const visibleWorkspaces = matchingWorkspaces.slice(pageStart, pageStart + workspacesPerPage);
    const isInvitationPreview = pendingInvitations === sampleInvitations;
    const awaitingResponse = pendingInvitations.filter(({ id }) => !invitationResponses[id]);
    const respondedInvites = pendingInvitations.filter(({ id }) => invitationResponses[id]);
    const inviteFilters = [
        { key: "all", label: "All", invitations: pendingInvitations, emptyMessage: "No invitations yet" },
        { key: "pending", label: "Pending", invitations: awaitingResponse, emptyMessage: "No pending invitations" },
        { key: "responded", label: "Responded", invitations: respondedInvites, emptyMessage: "No responded invitations yet" },
    ];
    const selectedInviteFilter = inviteFilters.find(({ key }) => key === inviteFilter);
    const matchingInvites = selectedInviteFilter.invitations;
    const invitePageCount = Math.ceil(matchingInvites.length / invitationsPerPage);
    const currentInvitePage = Math.min(invitePage, Math.max(1, invitePageCount));
    const inviteStart = (currentInvitePage - 1) * invitationsPerPage;
    const visibleInvites = matchingInvites.slice(inviteStart, inviteStart + invitationsPerPage);

    function selectInviteFilter(filter) {
        setInviteFilter(filter);
        setInvitePage(1);
    }

    function respondToInvitation(id, response) {
        setInvitationResponses((previous) => ({ ...previous, [id]: response }));
    }

    useEffect(() => {
        if (searchOpen) searchInputRef.current?.focus();
    }, [searchOpen]);

    function closeSearch() {
        setSearchOpen(false);
        setQuery("");
        setPage(1);
        searchButtonRef.current?.focus();
    }

    useEffect(() => {
        const sections = menuItems.flatMap(({ href }) => {
            const element = document.getElementById(href.slice(1));
            return element ? [{ href, element }] : [];
        });
        let frameId = null;

        function syncCurrentItem() {
            frameId = null;
            let activeHref = menuItems[0].href;

            for (const { href, element } of sections) {
                const scrollOffset = parseFloat(getComputedStyle(element).scrollMarginTop) || 0;
                if (element.getBoundingClientRect().top <= scrollOffset + 24) {
                    activeHref = href;
                }
            }

            setCurrentHref(activeHref);
        }

        function scheduleSync() {
            if (frameId === null) frameId = requestAnimationFrame(syncCurrentItem);
        }

        const observer = new ResizeObserver(scheduleSync);
        sections.forEach(({ element }) => observer.observe(element));
        window.addEventListener("scroll", scheduleSync, { passive: true });
        window.addEventListener("resize", scheduleSync);
        window.addEventListener("hashchange", scheduleSync);
        scheduleSync();

        return () => {
            observer.disconnect();
            if (frameId !== null) cancelAnimationFrame(frameId);
            window.removeEventListener("scroll", scheduleSync);
            window.removeEventListener("resize", scheduleSync);
            window.removeEventListener("hashchange", scheduleSync);
        };
    }, []);

    return (
        <section className={styles.section} aria-label="Workspace content">
            <nav className={styles.sidebar} aria-label="Workspace navigation">
                <ul className={styles.menu}>
                    {menuItems.map(({ href, label, Icon }) => (
                        <li key={href}>
                            <a
                                href={href}
                                className={styles.menuLink}
                                aria-current={href === currentHref ? "location" : undefined}
                            >
                                <Icon className={styles.menuIcon} size={18} aria-hidden="true" />
                                {label}
                            </a>
                        </li>
                    ))}
                </ul>
            </nav>

            <div className={styles.contentColumn}>
                <div
                    id="my-workspaces"
                    className={styles.workspaceContent}
                >
                    <div className={styles.workspaceToolbar}>
                        <div className={styles.workspaceSearch}>
                            <button
                                ref={searchButtonRef}
                                type="button"
                                className={styles.toolbarButton}
                                aria-label="Search workspaces"
                                aria-expanded={searchOpen}
                                onClick={() => searchOpen ? closeSearch() : setSearchOpen(true)}
                            >
                                <Search size={18} aria-hidden="true" />
                            </button>
                            {searchOpen && (
                                <input
                                    ref={searchInputRef}
                                    type="search"
                                    aria-label="Filter workspaces by name"
                                    placeholder="Search workspaces…"
                                    value={query}
                                    onChange={(event) => {
                                        setQuery(event.target.value);
                                        setPage(1);
                                    }}
                                    onKeyDown={(event) => {
                                        if (event.key === "Escape") closeSearch();
                                    }}
                                />
                            )}
                        </div>
                    </div>

                    <div
                        className={styles.workspaceTableFrame}
                        style={{ "--workspaces-per-page": workspacesPerPage }}
                        role="region"
                        aria-label="My Workspaces"
                        tabIndex={0}
                    >
                        <table className={styles.workspaceTable} aria-label="My Workspaces">
                            <thead>
                                <tr>
                                    <th scope="col" aria-sort={nameSort ?? "none"}>
                                        <button
                                            type="button"
                                            className={styles.workspaceSort}
                                            onClick={() => {
                                                setNameSort(nameSort === "ascending" ? "descending" : "ascending");
                                                setPage(1);
                                            }}
                                        >
                                            Workspace
                                            {nameSort === "ascending" ? (
                                                <ArrowUp size={14} aria-hidden="true" />
                                            ) : (
                                                <ArrowDown size={14} aria-hidden="true" />
                                            )}
                                        </button>
                                    </th>
                                    <th scope="col">Your role</th>
                                    <th scope="col">Created by</th>
                                    <th scope="col">Status</th>
                                    <th scope="col" className={styles.workspaceActionCell}>Action</th>
                                </tr>
                            </thead>
                            <tbody>
                                {visibleWorkspaces.map(({ slug, name, role, createdBy, status }, index) => {
                                    const gradientId = `${workspaceGradientPrefix}-${index}`;
                                    const creatorNames = createdBy.trim().split(/\s+/);
                                    const creatorInitials = (creatorNames[0].charAt(0) + (creatorNames.length > 1 ? creatorNames.at(-1).charAt(0) : "")).toUpperCase();
                                    const inSetup = status === "SETUP_INCOMPLETE";
                                    const StatusIcon = inSetup ? Clock : CircleCheck;
                                    const action = inSetup
                                        ? role === "Owner" && "Continue setup"
                                        : "Open";

                                    return (
                                        <tr key={slug ?? name}>
                                            <th scope="row">
                                                <span className={styles.workspaceName}>
                                                    <FolderCode
                                                        className={styles.workspaceAvatar}
                                                        size={20}
                                                        stroke={`url(#${gradientId})`}
                                                        strokeWidth={1.75}
                                                        aria-hidden="true"
                                                    >
                                                        <defs>
                                                            <linearGradient id={gradientId} x1="2" x2="23" y1="0" y2="0" gradientUnits="userSpaceOnUse">
                                                                <stop offset="0%" stopColor="var(--color-primary)" />
                                                                <stop offset="100%" stopColor="#38bdf8" />
                                                            </linearGradient>
                                                        </defs>
                                                    </FolderCode>
                                                    <span className={styles.workspaceNameText}>{name}</span>
                                                </span>
                                            </th>
                                            <td>{role}</td>
                                            <td className={styles.workspaceCreator}>
                                                <span className={styles.workspaceCreatorIdentity}>
                                                    <span className={styles.workspaceCreatorAvatar} aria-hidden="true">{creatorInitials}</span>
                                                    {createdBy}
                                                </span>
                                            </td>
                                            <td>
                                                <span className={styles.workspaceStatus} data-status={status}>
                                                    <StatusIcon size={14} aria-hidden="true" />
                                                    {inSetup ? "Setup" : "Active"}
                                                </span>
                                            </td>
                                            <td className={styles.workspaceActionCell}>
                                                {action && (
                                                    <ArrowUpRight
                                                        className={styles.workspaceAction}
                                                        size={18}
                                                        role="img"
                                                        aria-label={action}
                                                    >
                                                        <title>{action}</title>
                                                    </ArrowUpRight>
                                                )}
                                            </td>
                                        </tr>
                                    );
                                })}
                                {visibleWorkspaces.length === 0 && (
                                    <tr>
                                        <td colSpan={5} className={styles.workspaceEmptyCell}>
                                            <span role="status">
                                                {workspaces.length === 0
                                                    ? "You don’t have any workspaces yet."
                                                    : "No matching workspaces found."}
                                            </span>
                                        </td>
                                    </tr>
                                )}
                            </tbody>
                        </table>
                    </div>

                    {pageCount > 0 && (
                        <nav className={styles.workspacePagination} aria-label="Workspace pagination">
                            <button
                                type="button"
                                className={styles.workspacePageButton}
                                aria-label="Previous page"
                                disabled={currentPage === 1}
                                onClick={() => setPage(currentPage - 1)}
                            >
                                <ChevronLeft size={17} aria-hidden="true" />
                            </button>
                            <span className={styles.workspaceCurrentPage} aria-live="polite" aria-current="page">
                                {currentPage}
                            </span>
                            <button
                                type="button"
                                className={styles.workspacePageButton}
                                aria-label="Next page"
                                disabled={currentPage === pageCount}
                                onClick={() => setPage(currentPage + 1)}
                            >
                                <ChevronRight size={17} aria-hidden="true" />
                            </button>
                        </nav>
                    )}
                </div>

                <section id="invitations" className={styles.invitesContent} aria-labelledby="workspace-invitations-title">
                    <div className={styles.inviteIntro}>
                        <div>
                            <h2 id="workspace-invitations-title">Workspace Invitations</h2>
                            <p>Accept or decline invitations to join a workspace.</p>
                        </div>
                        <div className={styles.inviteFilters} role="group" aria-label="Filter invitations">
                            {inviteFilters.map(({ key, label, invitations }) => (
                                <button
                                    key={key}
                                    type="button"
                                    aria-pressed={inviteFilter === key}
                                    onClick={() => selectInviteFilter(key)}
                                >
                                    <strong className={styles.inviteFilterCount}>{invitations.length}</strong>
                                    <span>{label}</span>
                                </button>
                            ))}
                        </div>
                    </div>

                    <div className={styles.inviteList}>
                        {matchingInvites.length === 0 && (
                            <div className={styles.emptyInvites}>
                                <Inbox size={28} strokeWidth={1.5} aria-hidden="true" />
                                <h3 role="status">{selectedInviteFilter.emptyMessage}</h3>
                                {pendingInvitations.length === 0 && <p>New workspace invitations will appear here.</p>}
                            </div>
                        )}
                        {visibleInvites.map(({ id, workspace, inviter, expiresAt }) => {
                            const gradientId = `${invitationGradientPrefix}-${id}`;
                            const response = invitationResponses[id];

                            return (
                                <article key={id} className={styles.inviteCard}>
                                    <MailPlus
                                        className={styles.inviteSymbol}
                                        size={20}
                                        stroke={`url(#${gradientId})`}
                                        strokeWidth={1.75}
                                        aria-hidden="true"
                                    >
                                        <defs>
                                            <linearGradient id={gradientId} x1="2" x2="23" y1="0" y2="0" gradientUnits="userSpaceOnUse">
                                                <stop offset="0%" stopColor="var(--color-primary)" />
                                                <stop offset="100%" stopColor="#38bdf8" />
                                            </linearGradient>
                                        </defs>
                                    </MailPlus>
                                    <div className={styles.inviteInfo}>
                                        <h3>Join {workspace}</h3>
                                        <p>Invited by {inviter}</p>
                                    </div>

                                    <div className={styles.inviteDetails}>
                                        <dl className={styles.inviteFacts}>
                                            <div>
                                                <dt>Role</dt>
                                                <dd>Member</dd>
                                            </div>
                                            <div>
                                                <dt>Expires</dt>
                                                <dd><time dateTime={expiresAt}>{invitationDateFormat.format(new Date(expiresAt))}</time></dd>
                                            </div>
                                        </dl>

                                        <footer className={styles.inviteFooter}>
                                            <button
                                                type="button"
                                                className={`devsphere-button ${styles.inviteAccept}`}
                                                disabled={!isInvitationPreview}
                                                onClick={() => respondToInvitation(id, "accepted")}
                                            >
                                                Accept
                                            </button>
                                            <button
                                                type="button"
                                                className={`devsphere-button ${styles.inviteDecline}`}
                                                disabled={!isInvitationPreview}
                                                onClick={() => respondToInvitation(id, "declined")}
                                            >
                                                Decline
                                            </button>
                                            {response && (
                                                <div className={styles.inviteResponse}>
                                                    <span role="status">
                                                        {response === "accepted" ? "Invitation accepted" : "Invitation declined"}
                                                    </span>
                                                    <button
                                                        type="button"
                                                        className={`devsphere-button ${styles.inviteUndo}`}
                                                        onClick={() => respondToInvitation(id, null)}
                                                    >
                                                        <RotateCcw size={12} aria-hidden="true" />
                                                        Undo
                                                    </button>
                                                </div>
                                            )}
                                        </footer>
                                    </div>
                                </article>
                            );
                        })}
                        {invitePageCount > 1 && (
                            <nav className={styles.workspacePagination} aria-label="Invitation pagination">
                                <button
                                    type="button"
                                    className={styles.workspacePageButton}
                                    aria-label="Previous invitation page"
                                    disabled={currentInvitePage === 1}
                                    onClick={() => setInvitePage(currentInvitePage - 1)}
                                >
                                    <ChevronLeft size={17} aria-hidden="true" />
                                </button>
                                <span className={styles.workspaceCurrentPage} aria-live="polite" aria-current="page">
                                    {currentInvitePage}
                                </span>
                                <button
                                    type="button"
                                    className={styles.workspacePageButton}
                                    aria-label="Next invitation page"
                                    disabled={currentInvitePage === invitePageCount}
                                    onClick={() => setInvitePage(currentInvitePage + 1)}
                                >
                                    <ChevronRight size={17} aria-hidden="true" />
                                </button>
                            </nav>
                        )}
                    </div>
                </section>

                <WorkspaceNotifications />
            </div>
        </section>
    );
}
