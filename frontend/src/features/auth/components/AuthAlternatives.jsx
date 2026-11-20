"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { useAuth } from "./AuthProvider";
import styles from "../styles/AuthForm.module.css";

export default function AuthAlternatives({ mode = "register", disabled = false, onError, getRemember }) {
    const isLogin = mode === "login";
    const auth = useAuth();
    const router = useRouter();
    const lock = useRef(false);
    const [pending, setPending] = useState(false);

    return (
        <>
            <div className={styles.divider}><span>or</span></div>
            <button className={styles.google} type="button" disabled={disabled || pending} aria-busy={pending} onClick={async () => {
                if (lock.current) return;
                lock.current = true;
                setPending(true);
                try {
                    await auth.signInGoogle(getRemember?.() || false);
                    router.replace('/workspaces');
                } catch (error) { onError?.(error); }
                finally { lock.current = false; setPending(false); }
            }}>
                <img src="/google-g.svg" width="15" height="15" alt="" />
                {pending ? 'Continuing…' : 'Continue with Google'}
            </button>
            <p className={styles.footer}>
                {isLogin ? "Don’t have an account?" : "Already have an account?"}{" "}
                <Link href={isLogin ? "/register" : "/login"}>{isLogin ? "Sign Up" : "Sign In"}</Link>
            </p>
        </>
    );
}
