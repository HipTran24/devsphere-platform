"use client";

import { useId, useRef } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import AuthField from "./AuthField";
import AuthAlternatives from "./AuthAlternatives";
import ArrowIcon from "./ArrowIcon";
import { useAuth } from "./AuthProvider";
import { saveAuthFlow } from "../demo-store";
import { loginErrorMessage, validateLogin } from "../validation";
import useAuthForm from "../useAuthForm";
import styles from "../styles/AuthForm.module.css";

export default function LoginForm() {
    const auth = useAuth();
    const elementRef = useRef(null);
    const router = useRouter();
    const { errorRef, ...form } = useAuthForm();
    const errorMessageId = `login-error-${useId()}`;
    const error = loginErrorMessage(form.fieldErrors) || form.formError;
    const message = error || auth.notice;

    return (
        <div className={styles.form}>
            <h1 className={styles.title}>Sign In</h1>
            <form ref={elementRef} className={`${styles.loginFields} ${styles.registrationErrors}`} noValidate aria-busy={form.isSubmitting}
                onSubmit={event => form.submit(event, validateLogin, async (data, element) => {
                    auth.setNotice('');
                    let result;
                    try {
                        result = await auth.signIn({ email: data.email.trim(), password: data.password }, data.rememberMe === 'on');
                    } catch (error) {
                        if (error.code === "AUTH_INVALID") error.fields = { email: error.message, password: error.message };
                        throw error;
                    }
                    element.reset();
                    if (result.verificationRequired) {
                        saveAuthFlow({ ...result.challenge, stage: 'code' });
                        router.push('/check-email');
                    } else router.replace('/workspaces');
                })}>
                <div className={styles.fieldGroup}>
                    <AuthField id="email" label="Email" kind="email" autoComplete="email" error={form.fieldErrors.email} errorMessageId={errorMessageId} disabled={form.isSubmitting} />
                    <AuthField id="password" label="Password" kind="password" autoComplete="current-password" error={form.fieldErrors.password} errorMessageId={errorMessageId} disabled={form.isSubmitting} />
                </div>
                <p id={errorMessageId} ref={errorRef} className={`${styles.registrationMessage} ${!error && auth.notice && !auth.notice.includes('expired') ? styles.successMessage : ''}`} aria-live="polite" aria-atomic="true" tabIndex={-1} hidden={!message}>{message}</p>
                <div className={styles.options}>
                    <label className={styles.checkboxLabel}><input type="checkbox" name="rememberMe" />Remember me</label>
                    <Link className={styles.textButton} href="/forgot-password">Forgot password?</Link>
                </div>
                <button className={styles.primary} type="submit" disabled={form.isSubmitting}>
                    <span aria-live="polite">{form.isSubmitting ? "Signing in…" : "Sign In"}</span><ArrowIcon />
                </button>
            </form>
            <AuthAlternatives mode="login" disabled={form.isSubmitting} onError={form.reportError} getRemember={() => elementRef.current?.elements.rememberMe.checked || false} />
        </div>
    );
}
