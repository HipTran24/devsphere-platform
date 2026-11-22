"use client";

import { Bell, GitFork, LogOut, Shield, UserRound } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import { useAuth } from "../../auth/components/AuthProvider";
import styles from "../styles/WorkspaceHomeNavbar.module.css";

const pendingItems = [
    { label: "Profile", Icon: UserRound },
    { label: "Security", Icon: Shield },
    { label: "GitHub integration", Icon: GitFork },
];

const previewUser = {
    displayName: "An Tuấn",
    email: "antuan@example.com",
};

export default function WorkspaceAccountDropdown() {
    const { session, logout } = useAuth();
    const router = useRouter();
    const user = session?.user ?? previewUser;
    const panelId = useId();
    const accountRef = useRef(null);
    const triggerRef = useRef(null);
    const [open, setOpen] = useState(false);

    useEffect(() => {
        if (!open) return;

        function closeOutside(event) {
            if (!accountRef.current?.contains(event.target)) setOpen(false);
        }

        document.addEventListener("pointerdown", closeOutside);
        return () => document.removeEventListener("pointerdown", closeOutside);
    }, [open]);

    function handleEscape(event) {
        if (event.key !== "Escape" || !open) return;
        event.stopPropagation();
        setOpen(false);
        triggerRef.current?.focus();
    }

    function signOut() {
        setOpen(false);
        logout();
        router.replace("/login");
    }

    return (
        <div
            ref={accountRef}
            className={styles.account}
            onKeyDown={handleEscape}
            onBlur={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
            }}
        >
            <button
                ref={triggerRef}
                type="button"
                className={styles.avatar}
                aria-label="Account"
                aria-expanded={open}
                aria-controls={panelId}
                onClick={() => setOpen((current) => !current)}
            >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                    <circle cx="12" cy="7" r="4" />
                    <path d="M4 21v-2a8 6 0 0 1 16 0v2z" />
                </svg>
            </button>

            <div id={panelId} className={styles.accountDropdown} hidden={!open}>
                <div className={styles.accountIdentity}>
                    <div className={styles.accountPortrait} aria-hidden="true">
                        <UserRound size={18} />
                    </div>
                    <div className={styles.accountDetails}>
                        <strong>{user.displayName || user.email}</strong>
                        <span>{user.email}</span>
                    </div>
                </div>
                <ul className={styles.accountActions}>
                    {pendingItems.map(({ label, Icon }) => (
                        <li key={label} className={styles.accountPending}>
                            <Icon size={16} aria-hidden="true" />
                            <span>{label}</span>
                        </li>
                    ))}
                    <li>
                        <a className={styles.accountAction} href="#notifications" onClick={() => setOpen(false)}>
                            <Bell size={16} aria-hidden="true" />
                            Notifications
                        </a>
                    </li>
                </ul>
                <div className={styles.accountSession}>
                    <button type="button" className={styles.accountAction} onClick={signOut}>
                        <LogOut size={16} aria-hidden="true" />
                        Sign out
                    </button>
                </div>
            </div>
        </div>
    );
}
