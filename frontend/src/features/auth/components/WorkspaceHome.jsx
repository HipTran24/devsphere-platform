"use client";

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from './AuthProvider';
import styles from '../styles/Workspace.module.css';

export default function WorkspaceHome() {
    const auth = useAuth();
    const router = useRouter();

    const [workspaces, setWorkspaces] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showModal, setShowModal] = useState(false);
    const [name, setName] = useState('');
    const [slug, setSlug] = useState('');
    const [description, setDescription] = useState('');
    const [creating, setCreating] = useState(false);
    const [createError, setCreateError] = useState('');

    useEffect(() => {
        if (auth.ready && !auth.session) {
            router.replace('/login');
        }
    }, [auth.ready, auth.session, router]);

    useEffect(() => {
        if (!auth.session) return;
        let active = true;

        async function loadWorkspaces() {
            setLoading(true);
            try {
                const token = auth.session.token || auth.session.accessToken;
                const headers = { 'Accept': 'application/json' };
                if (token) headers['Authorization'] = `Bearer ${token}`;

                const res = await fetch('/api/v1/workspaces', {
                    headers,
                    credentials: 'include'
                });
                if (res.ok) {
                    const data = await res.json();
                    const list = data.items || data.content || (Array.isArray(data) ? data : []);
                    if (active) setWorkspaces(list);
                }
            } catch {
                // Non-fatal for mock/demo mode
            } finally {
                if (active) setLoading(false);
            }
        }

        loadWorkspaces();
        return () => { active = false; };
    }, [auth.session]);

    function handleNameChange(val) {
        setName(val);
        const autoSlug = val.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
        setSlug(autoSlug);
    }

    async function handleCreate(e) {
        e.preventDefault();
        setCreating(true);
        setCreateError('');

        try {
            const token = auth.session?.token || auth.session?.accessToken;
            const headers = {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
            };
            if (token) headers['Authorization'] = `Bearer ${token}`;

            const res = await fetch('/api/v1/workspaces', {
                method: 'POST',
                headers,
                credentials: 'include',
                body: JSON.stringify({
                    name: name.trim(),
                    slug: slug.trim(),
                    description: description.trim() || undefined
                })
            });

            if (res.ok) {
                const created = await res.json();
                setWorkspaces(prev => [created, ...prev]);
                setName('');
                setSlug('');
                setDescription('');
                setShowModal(false);
            } else {
                const errData = await res.json().catch(() => ({}));
                setCreateError(errData.detail || errData.title || 'Failed to create workspace');
            }
        } catch (err) {
            setCreateError(err.message || 'Network error');
        } finally {
            setCreating(false);
        }
    }

    if (!auth.ready || !auth.session) {
        return <main className={styles.page}><p role="status">Loading workspace…</p></main>;
    }

    return (
        <main className={styles.page}>
            <header className={styles.header}>
                <strong>DEVSPHERE</strong>
                <div>
                    <span>{auth.session.user.displayName}</span>
                    <button onClick={() => { auth.logout(); router.replace('/login'); }}>Log out</button>
                </div>
            </header>

            <section className={styles.content}>
                <p className={styles.demo}>Frontend demo · No real Google sign-in, account or server session.</p>

                <div className={styles.titleRow}>
                    <h1>My Workspaces</h1>
                    <button className={styles.createBtn} onClick={() => setShowModal(true)}>
                        + Create Workspace
                    </button>
                </div>

                <p className={styles.secondary}>Welcome, {auth.session.user.displayName}.</p>

                {loading ? (
                    <p role="status">Loading workspaces…</p>
                ) : workspaces.length === 0 ? (
                    <div className={styles.empty}>
                        <h2>No workspaces yet</h2>
                        <p>Create your first workspace to start collaborating on projects.</p>
                    </div>
                ) : (
                    <div className={styles.workspaceGrid}>
                        {workspaces.map(ws => {
                            const isOwner = ws.role === 'OWNER' || ws.ownerId === auth.session.user?.id;
                            return (
                                <div key={ws.id || ws.slug} className={styles.workspaceCard}>
                                    <div className={styles.cardHeader}>
                                        <h3 className={styles.cardTitle}>{ws.name}</h3>
                                        <div className={styles.badges}>
                                            <span className={`${styles.badge} ${isOwner ? styles.badgeOwner : styles.badgeMember}`}>
                                                {isOwner ? 'OWNER' : 'MEMBER'}
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
                                </div>
                            );
                        })}
                    </div>
                )}
            </section>

            {showModal && (
                <div className={styles.modalOverlay} role="dialog" aria-modal="true" aria-labelledby="modal-title">
                    <div className={styles.modalCard}>
                        <h2 id="modal-title">Create Workspace</h2>

                        {createError && (
                            <div className={styles.errorMessage} role="alert">
                                {createError}
                            </div>
                        )}

                        <form onSubmit={handleCreate}>
                            <div className={styles.formGroup}>
                                <label htmlFor="ws-name">Workspace Name *</label>
                                <input
                                    id="ws-name"
                                    type="text"
                                    required
                                    maxLength={160}
                                    value={name}
                                    onChange={e => handleNameChange(e.target.value)}
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
                                    value={slug}
                                    onChange={e => setSlug(e.target.value)}
                                    placeholder="e.g. acme-engineering"
                                />
                            </div>

                            <div className={styles.formGroup}>
                                <label htmlFor="ws-desc">Description</label>
                                <textarea
                                    id="ws-desc"
                                    rows={3}
                                    value={description}
                                    onChange={e => setDescription(e.target.value)}
                                    placeholder="Optional description of the workspace"
                                />
                            </div>

                            <div className={styles.formActions}>
                                <button
                                    type="button"
                                    className={styles.cancelBtn}
                                    onClick={() => { setShowModal(false); setCreateError(''); }}
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    className={styles.submitBtn}
                                    disabled={creating}
                                >
                                    {creating ? 'Creating…' : 'Create'}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </main>
    );
}
