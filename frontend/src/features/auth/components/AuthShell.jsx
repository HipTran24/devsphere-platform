"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import ArrowIcon from "./ArrowIcon";
import RegisterForm from "./RegisterForm";
import LoginForm from "./LoginForm";
import { useAuth } from "./AuthProvider";
import styles from "../styles/auth.module.css";

function WelcomeContent({ isLogin }) {
    return (
        <>
            <h2 className={styles.welcome}>
                {isLogin ? "WELCOME TO" : "WELCOME"}
                <span>{isLogin ? "DEVSPHERE!" : "BACK!"}</span>
            </h2>
            <p className={styles.description}>
                {isLogin
                    ? "Sign In to keep your projects moving and your team connected. New to DevSphere?"
                    : "Already have an account? Sign In to plan, build, and deliver with your team."}
            </p>
            <Link className={styles.switchButton} href={isLogin ? "/register" : "/login"}>
                <span>{isLogin ? "Sign Up" : "Sign In"}</span><ArrowIcon />
            </Link>
        </>
    );
}

export default function AuthShell({ mode = "register", children }) {
    const [activeMode, setActiveMode] = useState(mode);
    const [transitioning, setTransitioning] = useState(false);
    const navigationTimer = useRef(null);
    const router = useRouter();
    const auth = useAuth();
    const isLogin = activeMode === "login";

    useEffect(() => () => clearTimeout(navigationTimer.current), []);
    useEffect(() => {
        if (!children && auth.ready && auth.session) router.replace('/workspaces');
    }, [auth.ready, auth.session, children, router]);

    // Cho panel trượt xong trước khi chuyển sang đường dẫn Next.js tương ứng.
    function handleNavigation(event) {
        if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        const link = event.target.closest("a");
        if (!link || link.target === "_blank") return;
        const destination = link.getAttribute("href");
        if (destination !== "/login" && destination !== "/register") return;
        const nextMode = destination === "/login" ? "login" : "register";
        if (nextMode === activeMode) return;
        event.preventDefault();
        event.stopPropagation();
        if (transitioning) return;

        setActiveMode(nextMode);
        setTransitioning(true);
        const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        navigationTimer.current = setTimeout(() => {
            router.push(destination, { scroll: false });
        }, reduceMotion ? 0 : 700);
    }

    return (
        <main className={styles.page} onClickCapture={children ? undefined : handleNavigation}>
            <div className={`${styles.card} ${isLogin ? styles.loginCard : ""}`}>
                <aside className={styles.introPanel}>
                    <span className={`${styles.borderBeam} ${transitioning ? styles.borderBeamPaused : ""}`} aria-hidden="true" />
                    <div className={styles.brand}>
                        <span className={styles.logoFrame} aria-hidden="true" />
                        <span>DEVSPHERE</span>
                    </div>
                    <div className={styles.introContent}>
                        <div className={`${styles.welcomeView} ${styles.registerWelcome} ${isLogin ? styles.inactiveView : ""}`} inert={isLogin} aria-hidden={isLogin}>
                            <WelcomeContent isLogin={false} />
                        </div>
                        <div className={`${styles.welcomeView} ${styles.loginWelcome} ${!isLogin ? styles.inactiveView : ""}`} inert={!isLogin} aria-hidden={!isLogin}>
                            <WelcomeContent isLogin />
                        </div>
                    </div>
                    <p className={styles.manifest}>
                        <span>PLAN</span><i>/</i><span>BUILD</span><i>/</i><span>VERIFY</span><i>/</i><span>OPTIMIZE</span>
                    </p>
                </aside>
                <section className={styles.formPanel} inert={transitioning} aria-busy={transitioning}>
                    <div className={styles.formStage}>
                        {children || <>
                        <div className={`${styles.formView} ${styles.registerView} ${isLogin ? styles.inactiveView : ""}`} inert={isLogin} aria-hidden={isLogin}>
                            <RegisterForm />
                        </div>
                        <div className={`${styles.formView} ${styles.loginView} ${!isLogin ? styles.inactiveView : ""}`} inert={!isLogin} aria-hidden={!isLogin}>
                            <LoginForm />
                        </div>
                        </>}
                    </div>
                </section>
            </div>
        </main>
    );
}
