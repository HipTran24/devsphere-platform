"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { FolderPlus } from "lucide-react";
import { useAuth } from "./AuthProvider";
import { isAuthMock } from "../api";
import LoadingState from "../../../components/ui/LoadingState";
import ErrorState from "../../../components/ui/ErrorState";
import EmptyState from "../../../components/ui/EmptyState";
import {
    formatUiError,
    isRetryableWorkspaceStatus,
    resolveViewState,
} from "../../../components/ui/uiStateHelpers";
import useFocusTrap from "../../../hooks/useFocusTrap";
import styles from "../styles/Workspace.module.css";

export default function WorkspaceHome() {
    const auth = useAuth();
    const router = useRouter();

    const [workspaces, setWorkspaces] = useState([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);
    const [showModal, setShowModal] = useState(false);
    const [name, setName] = useState("");
    const [slug, setSlug] = useState("");
    const [description, setDescription] = useState("");
    const [creating, setCreating] = useState(false);
    const [createError, setCreateError] = useState("");

    const modalRef = useRef(null);
    const nameInputRef = useRef(null);

    const closeModal = useCallback(() => {
        setShowModal(false);
        setCreateError("");
    }, []);

    useFocusTrap({
        active: showModal,
        containerRef: modalRef,
        initialFocusRef: nameInputRef,
        onEscape: closeModal,
        restoreFocus: true,
    });

    useEffect(() => {
        if (auth.ready && !auth.session) {
            router.replace("/login");
        }
    }, [auth.ready, auth.session, router]);

    useEffect(() => {
        if (!auth.session) return;
        const controller = new AbortController();
        let active = true;

        async function loadWorkspaces() {
            setLoading(true);
            setLoadError(null);
            try {
                const token = auth.session.token || auth.session.accessToken;
                const headers = { Accept: "application/json" };
                if (token) headers.Authorization = `Bearer ${token}`;

                const res = await fetch("/api/v1/workspaces", {
                    headers,
                    credentials: "include",
                    signal: controller.signal,
                });

                if (!active) return;

                if (res.ok) {
                    const data = await res.json();
                    const list = data.items || data.content || (Array.isArray(data) ? data : []);
                    setWorkspaces(list);
                    setLoadError(null);
                } else if (isRetryableWorkspaceStatus(res.status)) {
                    if (isAuthMock && res.status === 500 && reloadToken === 0) {
                        setWorkspaces([]);
                        setLoadError(null);
                    } else {
                        const errData = await res.json().catch(() => ({}));
                        setLoadError(
                            formatUiError(
                                { status: res.status, ...errData },
                                "Unable to load workspaces right now. Please try again."
                            )
                        );
                    }
                } else {
                    setWorkspaces([]);
                    setLoadError(null);
                }
            } catch (error) {
                if (!active || error?.name === "AbortError") return;
                // Keep unconfigured demo environments non-fatal while surfacing explicit retryable failures.
                if (reloadToken > 0) {
                    setLoadError(
                        formatUiError(
                            error,
                            "Unable to reach the workspace service. Check your connection and try again."
                        )
                    );
                } else if (auth.session?.mock || isAuthMock) {
                    setWorkspaces([
                        {
                            id: "devsphere-core",
                            name: "DevSphere Core",
                            slug: "devsphere-core",
                            role: "OWNER",
                            status: "ACTIVE",
                            projectKey: "DSP",
                            description: "Primary engineering workspace for DevSphere Platform",
                        },
                    ]);
                    setLoadError(null);
                } else {
                    setWorkspaces([]);
                    setLoadError(null);
                }
            } finally {
                if (active) setLoading(false);
            }
        }

        loadWorkspaces();
        return () => {
            active = false;
            controller.abort();
        };
    }, [auth.session, reloadToken]);

    function handleNameChange(val) {
        setName(val);
        const autoSlug = val.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
        setSlug(autoSlug);
    }

    async function handleCreate(e) {
        e.preventDefault();
        setCreating(true);
        setCreateError("");

        try {
            const token = auth.session?.token || auth.session?.accessToken;
            const headers = {
                "Content-Type": "application/json",
                Accept: "application/json",
            };
            if (token) headers.Authorization = `Bearer ${token}`;

            const res = await fetch("/api/v1/workspaces", {
                method: "POST",
                headers,
                credentials: "include",
                body: JSON.stringify({
                    name: name.trim(),
                    slug: slug.trim(),
                    description: description.trim() || undefined,
                }),
            });

            if (res.ok) {
                const created = await res.json();
                setWorkspaces((prev) => [created, ...prev]);
                setName("");
                setSlug("");
                setDescription("");
                setShowModal(false);
            } else {
                const errData = await res.json().catch(() => ({}));
                setCreateError(errData.detail || errData.title || "Failed to create workspace");
            }
        } catch (err) {
            setCreateError(err.message || "Network error");
        } finally {
            setCreating(false);
        }
    }

    if (!auth.ready || !auth.session) {
        return (
            <main id="main-content" tabIndex={-1} className={styles.page}>
                <LoadingState variant="page" label="Loading workspace…" />
            </main>
        );
    }

    const viewState = resolveViewState({
        loading,
        error: loadError,
        isEmpty: workspaces.length === 0,
    });

    return (
        <main id="main-content" tabIndex={-1} className={styles.page}>
            <header className={styles.header}>
                <strong>DEVSPHERE</strong>
                <div>
                    <span>{auth.session.user.displayName}</span>
                    <button
                        type="button"
                        onClick={() => {
                            auth.logout();
                            router.replace("/login");
                        }}
                    >
                        Log out
                    </button>
                </div>
            </header>

            <section className={styles.content} aria-labelledby="workspaces-heading">
                <p className={styles.demo}>Frontend demo · No real Google sign-in, account or server session.</p>

                <div className={styles.titleRow}>
                    <h1 id="workspaces-heading">My Workspaces</h1>
                    {viewState !== "empty" && (
                        <button
                            type="button"
                            className={styles.createBtn}
                            aria-haspopup="dialog"
                            onClick={() => setShowModal(true)}
                        >
                            + Create Workspace
                        </button>
                    )}
                </div>

                <p className={styles.secondary}>Welcome, {auth.session.user.displayName}.</p>

                {viewState === "loading" && (
                    <LoadingState variant="grid" label="Loading workspaces…" count={3} />
                )}

                {viewState === "error" && (
                    <ErrorState
                        className={styles.stateSpacing}
                        title={loadError.title}
                        message={loadError.message}
                        code={loadError.code}
                        onRetry={() => setReloadToken((count) => count + 1)}
                        retryLabel="Retry"
                    />
                )}

                {viewState === "empty" && (
                    <EmptyState
                        className={styles.stateSpacing}
                        icon={FolderPlus}
                        title="No workspaces yet"
                        description="Create your first workspace to start collaborating on projects."
                        actionLabel="+ Create Workspace"
                        onAction={() => setShowModal(true)}
                    />
                )}

                {viewState === "ready" && (
                    <div className={styles.workspaceGrid}>
                        {workspaces.map((ws) => {
                            const isOwner = ws.role === "OWNER" || ws.ownerId === auth.session.user?.id;
                            const targetUrl = ws.projectKey
                                ? `/p/${ws.projectKey}/backlog`
                                : `/workspaces/${ws.id || ws.slug}/backlog`;
                            return (
                                <article
                                    key={ws.id || ws.slug}
                                    className={styles.workspaceCard}
                                    tabIndex={0}
                                    role="button"
                                    aria-label={`Open workspace ${ws.name} backlog`}
                                    onClick={() => router.push(targetUrl)}
                                    onKeyDown={(e) => {
                                        if (e.key === "Enter" || e.key === " ") {
                                            e.preventDefault();
                                            router.push(targetUrl);
                                        }
                                    }}
                                >
                                    <div className={styles.cardHeader}>
                                        <h2 className={styles.cardTitle}>{ws.name}</h2>
                                        <div className={styles.badges}>
                                            <span className={`${styles.badge} ${isOwner ? styles.badgeOwner : styles.badgeMember}`}>
                                                {isOwner ? "OWNER" : "MEMBER"}
                                            </span>
                                            {ws.status && (
                                                <span className={`${styles.badge} ${styles.badgeStatus}`}>
                                                    {ws.status}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                    <span className={styles.cardSlug}>slug: {ws.slug}</span>
                                    {ws.description && <p className={styles.cardDesc}>{ws.description}</p>}
                                    <div className={styles.cardActions}>
                                        <button
                                            type="button"
                                            className={styles.openBacklogBtn}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                router.push(targetUrl);
                                            }}
                                        >
                                            Open Backlog &rarr;
                                        </button>
                                    </div>
                                </article>
                            );
                        })}
                    </div>
                )}
            </section>

            {showModal && (
                <div
                    className={styles.modalOverlay}
                    onMouseDown={(event) => {
                        if (event.target === event.currentTarget) closeModal();
                    }}
                >
                    <div
                        ref={modalRef}
                        className={styles.modalCard}
                        role="dialog"
                        aria-modal="true"
                        aria-labelledby="modal-title"
                        tabIndex={-1}
                    >
                        <h2 id="modal-title">Create Workspace</h2>

                        {createError && (
                            <div className={styles.errorMessage} role="alert" aria-live="assertive" aria-atomic="true">
                                {createError}
                            </div>
                        )}

                        <form onSubmit={handleCreate} aria-busy={creating}>
                            <div className={styles.formGroup}>
                                <label htmlFor="ws-name">Workspace Name *</label>
                                <input
                                    ref={nameInputRef}
                                    id="ws-name"
                                    type="text"
                                    required
                                    maxLength={160}
                                    disabled={creating}
                                    value={name}
                                    onChange={(e) => handleNameChange(e.target.value)}
                                    placeholder="e.g. Acme Engineering"
                                />
                            </div>

                            <div className={styles.formGroup}>
                                <label htmlFor="ws-slug">Workspace Slug *</label>
                                <input
                                    id="ws-slug"
                                    type="text"
                                    required
                                    minLength={3}
                                    maxLength={80}
                                    pattern="^[a-z0-9]+(-[a-z0-9]+)*$"
                                    disabled={creating}
                                    value={slug}
                                    onChange={(e) => setSlug(e.target.value)}
                                    placeholder="e.g. acme-engineering"
                                />
                            </div>

                            <div className={styles.formGroup}>
                                <label htmlFor="ws-desc">Description</label>
                                <textarea
                                    id="ws-desc"
                                    rows={3}
                                    disabled={creating}
                                    value={description}
                                    onChange={(e) => setDescription(e.target.value)}
                                    placeholder="Optional description of the workspace"
                                />
                            </div>

                            <div className={styles.formActions}>
                                <button
                                    type="button"
                                    className={styles.cancelBtn}
                                    disabled={creating}
                                    onClick={closeModal}
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    className={styles.submitBtn}
                                    disabled={creating}
                                >
                                    {creating ? "Creating…" : "Create"}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </main>
    );
}
