"use client";

import { useId, useState } from "react";
import styles from "../styles/AuthForm.module.css";

export default function AuthField({ id, label, kind = "text", autoComplete, minLength, hint, error, errorMessageId, disabled, onChange, inputMode, maxLength }) {
    const [visible, setVisible] = useState(false);
    const inputId = `${id}-${useId()}`;
    const hintId = `${inputId}-hint`;
    const errorId = `${inputId}-error`;
    const describedBy = [hint && hintId, error && (errorMessageId || errorId)].filter(Boolean).join(" ") || undefined;
    const isPassword = kind === "password" || kind === "confirm";

    return (
        <div className={styles.field}>
            <label className={styles.label} htmlFor={inputId}>{label}</label>
            <svg className={styles.fieldIcon} width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                {kind === "email" ? <><rect x="3" y="5" width="18" height="14" rx="1" /><path d="m3 6 9 7 9-7" /></> :
                    kind === "confirm" ? <><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Z" /><path d="M12 3v18c-4-2-8-5-8-9V6Z" fill="currentColor" /></> :
                        isPassword ? <><rect x="5" y="10" width="14" height="11" rx="1" /><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3" /></> :
                            <><circle cx="12" cy="7" r="3" /><path d="M5 21v-3a7 7 0 0 1 14 0v3Z" /></>}
            </svg>
            <input className={styles.input} id={inputId} name={id}
                type={isPassword ? (visible ? "text" : "password") : kind === "email" ? "email" : "text"}
                placeholder={label} autoComplete={autoComplete} minLength={minLength} required
                disabled={disabled} onChange={onChange} inputMode={inputMode} maxLength={maxLength}
                aria-invalid={error ? true : undefined} aria-describedby={describedBy} />
            {isPassword && (
                <button className={styles.visibility} type="button"
                    aria-label={`${visible ? "Hide" : "Show"} ${label.toLowerCase()}`}
                    disabled={disabled} aria-pressed={visible} onClick={() => setVisible(!visible)}>
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" aria-hidden="true">
                        <path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" />
                        {!visible && <path d="m3 3 18 18" />}
                    </svg>
                </button>
            )}
            {hint && <p id={hintId} className={styles.hint}>{hint}</p>}
            {error && !errorMessageId && <p id={errorId} className={styles.fieldError}>{error}</p>}
        </div>
    );
}
