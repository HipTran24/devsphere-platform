"use client";

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from './AuthProvider';
import styles from '../styles/Workspace.module.css';

export default function WorkspaceHome() {
    const auth = useAuth();
    const router = useRouter();
    useEffect(() => {
        if (auth.ready && !auth.session) router.replace('/login');
    }, [auth.ready, auth.session, router]);
    if (!auth.ready || !auth.session) return <main className={styles.page}><p role="status">Loading workspace…</p></main>;
    return <main className={styles.page}>
        <header className={styles.header}><strong>DEVSPHERE</strong><div><span>{auth.session.user.displayName}</span>
            <button onClick={() => { auth.logout(); router.replace('/login'); }}>Log out</button></div></header>
        <section className={styles.content}>
            <p className={styles.demo}>Frontend demo · No real Google sign-in, account or server session.</p>
            <h1>My Workspaces</h1>
            <p className={styles.secondary}>Welcome, {auth.session.user.displayName}.</p>
            <div className={styles.empty}><h2>No workspaces yet</h2><p>Workspace creation and project data will be connected in the next feature.</p></div>
        </section>
    </main>;
}
