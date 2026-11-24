"use client";

import { Bell, GitFork, LogOut, Shield, UserRound } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import { useAuth } from "../../auth/components/AuthProvider";
import { isKeyboardListKey, nextKeyboardIndex } from "../../../hooks/useKeyboardList";
import styles from "../styles/WorkspaceHomeNavbar.module.css";

const pendingItems = [
    { label: "Profile", Icon: UserRound },
    { label: "Security", Icon: Shield },
    { label: "GitHub integration", Icon: GitFork },
];

const TOTAL_MENU_ITEMS = pendingItems.length + 2;

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
    const itemRefs = useRef([]);
    const [open, setOpen] = useState(false);
    const [activeIndex, setActiveIndex] = useState(0);

    useEffect(() => {
        if (!open) return;

        function closeOutside(event) {
            if (!accountRef.current?.contains(event.target)) setOpen(false);
        }

        document.addEventListener("pointerdown", closeOutside);
        return () => document.removeEventListener("pointerdown", closeOutside);
    }, [open]);

    useEffect(() => {
        if (!open) return;
        itemRefs.current[activeIndex]?.focus();
    }, [open, activeIndex]);

    function closeAndRestoreFocus() {
        setOpen(false);
        triggerRef.current?.focus();
    }

    function openAtIndex(index) {
        setActiveIndex(index);
        setOpen(true);
    }

    function handleContainerKeyDown(event) {
        if (!open) return;

        if (event.key === "Escape") {
            event.preventDefault();
            event.stopPropagation();
            closeAndRestoreFocus();
            return;
        }

        if (isKeyboardListKey(event.key, "vertical")) {
            event.preventDefault();
            const currentFocused = itemRefs.current.indexOf(document.activeElement);
            const baseIndex = currentFocused >= 0 ? currentFocused : activeIndex;
            const next = nextKeyboardIndex(baseIndex, event.key, TOTAL_MENU_ITEMS, { wrap: true });
            if (next >= 0) {
                setActiveIndex(next);
                itemRefs.current[next]?.focus();
            }
        }
    }

    function handleTriggerKeyDown(event) {
        if (open) return;
        if (event.key === "ArrowDown") {
            event.preventDefault();
            openAtIndex(0);
        } else if (event.key === "ArrowUp") {
            event.preventDefault();
            openAtIndex(TOTAL_MENU_ITEMS - 1);
        }
    }

    function signOut() {
        setOpen(false);
        logout();
        router.replace("/login");
    }

    const notificationsIndex = pendingItems.length;
    const signOutIndex = pendingItems.length + 1;

    return (
        <div
            ref={accountRef}
            className={styles.account}
            onKeyDown={handleContainerKeyDown}
            onBlur={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
            }}
        >
            <button
                ref={triggerRef}
                type="button"
                className={styles.avatar}
                aria-label="Account"
                aria-haspopup="menu"
                aria-expanded={open}
                aria-controls={panelId}
                onKeyDown={handleTriggerKeyDown}
                onClick={() => {
                    if (open) {
                        setOpen(false);
                    } else {
                        openAtIndex(0);
                    }
                }}
            >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                    <circle cx="12" cy="7" r="4" />
                    <path d="M4 21v-2a8 6 0 0 1 16 0v2z" />
                </svg>
            </button>

            <div
                id={panelId}
                role="menu"
                aria-label="Account menu"
                className={styles.accountDropdown}
                hidden={!open}
            >
                <div className={styles.accountIdentity}>
                    <div className={styles.accountPortrait} aria-hidden="true">
                        <UserRound size={18} />
                    </div>
                    <div className={styles.accountDetails}>
                        <strong>{user.displayName || user.email}</strong>
                        <span>{user.email}</span>
                    </div>
                </div>
                <ul className={styles.accountActions} role="none">
                    {pendingItems.map(({ label, Icon }, index) => (
                        <li key={label} role="none">
                            <button
                                ref={(element) => {
                                    itemRefs.current[index] = element;
                                }}
                                type="button"
                                role="menuitem"
                                aria-disabled="true"
                                tabIndex={activeIndex === index ? 0 : -1}
                                className={styles.accountPending}
                                onClick={(event) => event.preventDefault()}
                            >
                                <Icon size={16} aria-hidden="true" />
                                <span>{label}</span>
                                <span className={styles.accountPendingTag}>Soon</span>
                            </button>
                        </li>
                    ))}
                    <li role="none">
                        <a
                            ref={(element) => {
                                itemRefs.current[notificationsIndex] = element;
                            }}
                            role="menuitem"
                            tabIndex={activeIndex === notificationsIndex ? 0 : -1}
                            className={styles.accountAction}
                            href="#notifications"
                            onClick={() => setOpen(false)}
                        >
                            <Bell size={16} aria-hidden="true" />
                            Notifications
                        </a>
                    </li>
                </ul>
                <div className={styles.accountSession} role="none">
                    <button
                        ref={(element) => {
                            itemRefs.current[signOutIndex] = element;
                        }}
                        type="button"
                        role="menuitem"
                        tabIndex={activeIndex === signOutIndex ? 0 : -1}
                        className={styles.accountAction}
                        onClick={signOut}
                    >
                        <LogOut size={16} aria-hidden="true" />
                        Sign out
                    </button>
                </div>
            </div>
        </div>
    );
}
