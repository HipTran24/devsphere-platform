"use client";

import { useEffect, useId, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import AuthField from './AuthField';
import RecoveryBadge from './RecoveryBadge';
import { useAuth } from './AuthProvider';
import useAuthForm from '../useAuthForm';
import { registrationErrorMessage, validateEmail, validateNewPassword } from '../validation';
import { getAuthFlow, saveAuthFlow, clearAuthFlow } from '../demo-store';
import { requestPasswordReset, resendCode, verifyEmail, verifyResetCode, resetPassword, AuthError } from '../api';
import formStyles from '../styles/AuthForm.module.css';
import styles from '../styles/Recovery.module.css';

function RecoveryHeading({ kind, title, children }) {
    return <><RecoveryBadge kind={kind} />
        <h1 className={`${formStyles.title} ${styles.title}`}>{title}</h1>
        <p className={styles.description}>{children}</p></>;
}
function LoginLink({ compact = false }) {
    return <p className={`${styles.footer} ${compact ? styles.compactFooter : ''}`}>Remembered it? <Link href="/login">Log in</Link></p>;
}
export function ForgotPasswordView() {
    const router = useRouter();
    const { errorRef, ...form } = useAuthForm();
    const errorMessageId = `forgot-password-error-${useId()}`;
    const message = form.fieldErrors.email || form.formError;
    return <div className={`${formStyles.form} ${styles.view}`}>
        <RecoveryHeading kind="lock" title="Forgot password?">Enter your email to receive a reset code.</RecoveryHeading>
        <form className={`${styles.forgotFields} ${formStyles.registrationErrors}`} noValidate aria-busy={form.isSubmitting}
            onSubmit={event => form.submit(event, data => {
                const emailError = !data.email.trim() ? 'Enter your email.' : validateEmail(data.email) ? 'Enter a valid email.' : '';
                return emailError ? { email: emailError } : {};
            }, async data => {
                const challenge = await requestPasswordReset({ email: data.email.trim() });
                saveAuthFlow({ ...challenge, stage: 'code' });
                router.push('/check-email');
            })}>
            <AuthField id="email" label="Email" kind="email" autoComplete="email" error={form.fieldErrors.email} errorMessageId={errorMessageId} disabled={form.isSubmitting} />
            <p id={errorMessageId} ref={errorRef} className={formStyles.registrationMessage} aria-live="polite" aria-atomic="true" tabIndex={-1} hidden={!message}>{message}</p>
            <button className={formStyles.primary} type="submit" disabled={form.isSubmitting}>{form.isSubmitting ? 'Sending…' : 'Send Code'}</button>
        </form><LoginLink compact />
    </div>;
}
export function CheckEmailView() {
    const [digits, setDigits] = useState(Array(6).fill(''));
    const [flow, setFlow] = useState(null);
    const [now, setNow] = useState(0);
    const [pending, setPending] = useState(false);
    const [message, setMessage] = useState('');
    const [success, setSuccess] = useState(false);
    const [invalid, setInvalid] = useState(false);
    const inputs = useRef([]);
    const busy = useRef(false);
    const completeTimer = useRef(null);
    const router = useRouter();
    const auth = useAuth();
    const errorId = `otp-message-${useId()}`;
    useEffect(() => {
        setFlow(getAuthFlow());
        setNow(Date.now());
        const timer = setInterval(() => setNow(Date.now()), 1000);
        return () => { clearInterval(timer); clearTimeout(completeTimer.current); };
    }, []);
    const seconds = flow && now ? Math.max(0, Math.ceil((flow.resendAt - now) / 1000)) : 0;
    async function check(next) {
        if (busy.current || success || !next.every(Boolean)) return;
        busy.current = true;
        setPending(true);
        setMessage('');
        setInvalid(false);
        try {
            if (!flow || flow.stage !== 'code') throw new AuthError('FLOW_MISSING', 'Request a code first.');
            if (flow.purpose === 'VERIFY_EMAIL') {
                await verifyEmail({ challengeId: flow.id, code: next.join('') });
                clearAuthFlow();
                setSuccess(true);
                setMessage('Email verified. Returning to Sign In…');
                auth.setNotice('Email verified. Sign in to continue.');
                completeTimer.current = setTimeout(() => router.replace('/login'), 1200);
            } else {
                const grant = await verifyResetCode({ challengeId: flow.id, code: next.join('') });
                saveAuthFlow({ purpose: 'RESET_PASSWORD', stage: 'password', resetGrantId: grant.id, expiresAt: grant.expiresAt });
                router.replace('/create-new-password');
            }
        } catch (error) {
            setMessage(error.message);
            setInvalid(true);
            requestAnimationFrame(() => inputs.current[0]?.focus());
        } finally { busy.current = false; setPending(false); }
    }
    function updateDigit(index, raw) {
        if (busy.current || success) return;
        const normalized = raw.replace(/\D/g, '');
        const next = [...digits];
        if (normalized.length > 1) {
            for (let offset=0; offset<Math.min(normalized.length, 6-index); offset++) next[index+offset] = normalized[offset];
        } else next[index] = normalized;
        setDigits(next);
        if (normalized && index < 5) inputs.current[Math.min(index + normalized.length, 5)]?.focus();
        void check(next);
    }
    return <div className={`${formStyles.form} ${styles.view}`}>
        <RecoveryHeading kind="mail" title="Check your email">Enter the 6-digit code we sent to your email</RecoveryHeading>
        <div className={styles.otp} role="group" aria-label="6-digit verification code" aria-busy={pending} onPaste={event => {
            if (busy.current || success) return;
            const pasted = event.clipboardData.getData('text').replace(/\D/g, '').slice(0,6);
            if (!pasted) return;
            event.preventDefault();
            const next = Array.from({length:6}, (_, index) => pasted[index] || '');
            setDigits(next);
            inputs.current[Math.min(pasted.length,5)]?.focus();
            void check(next);
        }}>
            {digits.map((digit,index) => <input key={index} ref={element => { inputs.current[index] = element; }}
                className={styles.digit} type="text" inputMode="numeric" maxLength={index === 0 ? 6 : 1}
                autoComplete={index===0 ? 'one-time-code' : 'off'} aria-label={`Digit ${index+1}`}
                aria-invalid={invalid || undefined} aria-describedby={invalid ? errorId : undefined} disabled={pending || success}
                value={digit} onChange={event => updateDigit(index,event.target.value)} onKeyDown={event => {
                    if (event.key === 'Backspace' && !digit && index>0) inputs.current[index-1]?.focus();
                    if (event.key === 'ArrowLeft' && index>0) inputs.current[index-1]?.focus();
                    if (event.key === 'ArrowRight' && index<5) inputs.current[index+1]?.focus();
                }} />)}
        </div>
        <p id={errorId} className={`${formStyles.registrationMessage} ${success ? formStyles.successMessage : ''}`} role="status" hidden={!message}>{message}</p>
        <p className={styles.resend}>Didn’t get it? <button type="button" disabled={pending || success || seconds>0} onClick={async () => {
            if (busy.current) return;
            if (!flow || flow.stage!=='code') { router.replace('/forgot-password'); return; }
            busy.current = true;
            setPending(true);
            setMessage('');
            try {
                const challenge = await resendCode(flow.id);
                const updated = {...challenge,stage:'code'};
                setFlow(updated); saveAuthFlow(updated); setNow(Date.now());
                setDigits(Array(6).fill('')); setInvalid(false);
                requestAnimationFrame(() => inputs.current[0]?.focus());
            } catch(error) { setMessage(error.message); }
            finally { busy.current=false; setPending(false); }
        }}>{seconds>0 ? `Resend in ${seconds}s` : pending ? 'Please wait…' : 'Resend code'}</button></p>
    </div>;
}
export function CreateNewPasswordView() {
    const router = useRouter();
    const auth = useAuth();
    const { errorRef, ...form } = useAuthForm();
    const errorMessageId = `new-password-error-${useId()}`;
    const message = registrationErrorMessage(form.fieldErrors) || form.formError;
    return <div className={`${formStyles.form} ${styles.view}`}>
        <RecoveryHeading kind="key" title="Create new password">Choose a new, secure password.</RecoveryHeading>
        <form className={`${styles.fields} ${styles.resetFields} ${formStyles.registrationErrors}`} noValidate aria-busy={form.isSubmitting}
            onSubmit={event => form.submit(event, validateNewPassword, async (data,element) => {
                const flow=getAuthFlow();
                if (!flow || flow.stage!=='password') throw new AuthError('RESET_REQUIRED','Verify your code first.');
                await resetPassword({resetGrantId:flow.resetGrantId,password:data.password});
                element.reset(); clearAuthFlow(); auth.logout();
                auth.setNotice('Password reset complete. Sign in again.');
                router.replace('/login');
            })}>
            <div><div className={formStyles.fieldGroup}>
                <AuthField id="password" label="New password" kind="password" autoComplete="new-password" error={form.fieldErrors.password} errorMessageId={errorMessageId} disabled={form.isSubmitting} />
                <AuthField id="confirmPassword" label="Confirm password" kind="confirm" autoComplete="new-password" error={form.fieldErrors.confirmPassword} errorMessageId={errorMessageId} disabled={form.isSubmitting} />
            </div><p id={errorMessageId} ref={errorRef} className={formStyles.registrationMessage} aria-live="polite" aria-atomic="true" tabIndex={-1} hidden={!message}>{message}</p></div>
            <button className={formStyles.primary} type="submit" disabled={form.isSubmitting}>{form.isSubmitting ? 'Resetting…' : 'Reset password'}</button>
        </form><LoginLink compact />
    </div>;
}
