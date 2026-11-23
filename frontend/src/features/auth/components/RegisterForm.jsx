"use client";

import { useId } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import AuthField from "./AuthField";
import AuthAlternatives from "./AuthAlternatives";
import ArrowIcon from "./ArrowIcon";
import { saveAuthFlow } from "../demo-store";
import { registerAccount } from "../api";
import { registrationErrorMessage, validateRegistration } from "../validation";
import useAuthForm from "../useAuthForm";
import styles from "../styles/AuthForm.module.css";

function validateForm(data) {
    return validateRegistration({ ...data, displayName: data.fullName });
}

export default function RegisterForm() {
    const router = useRouter();
    const { errorRef, ...form } = useAuthForm();
    const errorMessageId = `registration-error-${useId()}`;
    const message = registrationErrorMessage(form.fieldErrors) || form.formError;

    return (
        <div className={styles.form}>
            <h1 className={styles.title}>Create Account</h1>
            <form className={`${styles.registerFields} ${styles.registrationErrors}`} noValidate aria-busy={form.isSubmitting}
                onSubmit={event => form.submit(event, validateForm, async (data, element) => {
                    const result = await registerAccount({ displayName: data.fullName.trim(), email: data.email.trim(), password: data.password });
                    element.reset();
                    saveAuthFlow({ ...result, stage: 'code' });
                    router.push('/check-email');
                })}>
                <div className={styles.fieldGroup}>
                    <AuthField id="fullName" label="Full name" autoComplete="name" maxLength={120} error={form.fieldErrors.fullName} errorMessageId={errorMessageId} disabled={form.isSubmitting} />
                    <AuthField id="email" label="Email" kind="email" autoComplete="email" error={form.fieldErrors.email} errorMessageId={errorMessageId} disabled={form.isSubmitting} />
                    <AuthField id="password" label="Password" kind="password" autoComplete="new-password" minLength={12} error={form.fieldErrors.password} errorMessageId={errorMessageId} disabled={form.isSubmitting} />
                    <AuthField id="confirmPassword" label="Confirm password" kind="confirm" autoComplete="new-password" error={form.fieldErrors.confirmPassword} errorMessageId={errorMessageId} disabled={form.isSubmitting} />
                </div>
                <p id={errorMessageId} ref={errorRef} className={styles.registrationMessage} aria-live="polite" aria-atomic="true" tabIndex={-1} hidden={!message}>{message}</p>
                <label className={styles.checkboxLabel}>
                    <input type="checkbox" name="terms" required disabled={form.isSubmitting}
                        aria-invalid={form.fieldErrors.terms ? true : undefined} aria-describedby={form.fieldErrors.terms ? errorMessageId : undefined} />
                    <span>I agree to DevSphere’s <Link className={styles.termsLink} href="/terms" target="_blank" rel="noopener noreferrer">Terms of Service</Link></span>
                </label>
                <button className={styles.primary} type="submit" disabled={form.isSubmitting}>
                    <span aria-live="polite">{form.isSubmitting ? "Creating account…" : "Create Account"}</span><ArrowIcon />
                </button>
            </form>
            <AuthAlternatives disabled={form.isSubmitting} onError={form.reportError} />
        </div>
    );
}
