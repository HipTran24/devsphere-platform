"use client";

import { Layers, X } from "lucide-react";
import { useId, useRef, useState } from "react";
import ErrorState from "../../../components/ui/ErrorState";
import { formatUiError } from "../../../components/ui/uiStateHelpers";
import styles from "../styles/CreateWorkspaceDialog.module.css";

const nameLimit = 160;
const slugLimit = 80;
const slugPattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

function makeSlug(name) {
    return name.toLowerCase()
        .normalize("NFD")
        .replace(/\p{M}/gu, "")
        .replaceAll("đ", "d")
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+/, "")
        .slice(0, slugLimit)
        .replace(/-+$/, "");
}

export default function CreateWorkspaceDialog({ className, workspaces, onCreate }) {
    const id = useId();
    const triggerRef = useRef(null);
    const dialogRef = useRef(null);
    const nameRef = useRef(null);
    const slugRef = useRef(null);
    const errorId = `${id}-error`;
    const [name, setName] = useState("");
    const [customSlug, setCustomSlug] = useState(null);
    const [error, setError] = useState(null);
    const [submitError, setSubmitError] = useState(null);
    const [submitting, setSubmitting] = useState(false);
    const suggestedSlug = makeSlug(name);
    const slugInputValue = customSlug ?? suggestedSlug;
    const slug = customSlug || suggestedSlug;

    function openDialog() {
        setSubmitError(null);
        dialogRef.current.showModal();
        nameRef.current.focus();
    }

    function closeDialog() {
        if (submitting) return;
        dialogRef.current?.close();
    }

    function handleDialogClose() {
        setName("");
        setCustomSlug(null);
        setError(null);
        setSubmitError(null);
        setSubmitting(false);
        dialogRef.current?.querySelector("form")?.reset();
        triggerRef.current?.focus();
    }

    function showError(field, message, inputRef) {
        setError({ field, message });
        inputRef.current.focus();
    }

    async function submit(event) {
        event.preventDefault();
        if (submitting) return;
        setSubmitError(null);
        const trimmedName = name.trim();

        if (!trimmedName) {
            showError("name", "Enter a workspace name.", nameRef);
            return;
        }

        if (!slug) {
            showError("slug", "Enter a workspace URL.", slugRef);
            return;
        }

        if (!slugPattern.test(slug)) {
            showError("slug", "Use lowercase letters, numbers, and hyphens between words.", slugRef);
            return;
        }

        if (workspaces.some((workspace) => (workspace.slug ?? makeSlug(workspace.name)) === slug)) {
            showError("slug", "This workspace URL is already in use.", slugRef);
            return;
        }

        const description = new FormData(event.currentTarget).get("description").trim();
        setSubmitting(true);
        try {
            await onCreate({ name: trimmedName, slug, description });
            dialogRef.current?.close();
        } catch (err) {
            setSubmitError(formatUiError(err, "Unable to create workspace. Please try again."));
        } finally {
            setSubmitting(false);
        }
    }

    const nameError = error?.field === "name";
    const urlError = error?.field === "slug";

    return (
        <>
            <button
                ref={triggerRef}
                type="button"
                className={className}
                aria-haspopup="dialog"
                onClick={openDialog}
            >
                Create workspace
            </button>
            <dialog
                ref={dialogRef}
                className={styles.dialog}
                aria-labelledby={`${id}-title`}
                onCancel={(event) => {
                    if (submitting) event.preventDefault();
                }}
                onClose={handleDialogClose}
            >
                <div className={styles.header}>
                    <h2 id={`${id}-title`}>
                        <Layers size={20} aria-hidden="true" />
                        Create workspace
                    </h2>
                    <button
                        type="button"
                        className="devsphere-button"
                        aria-label="Close create workspace"
                        disabled={submitting}
                        onClick={closeDialog}
                    >
                        <X size={20} aria-hidden="true" />
                    </button>
                </div>

                {submitError && (
                    <div className={styles.submitAlert}>
                        <ErrorState
                            compact
                            headingLevel="h3"
                            title={submitError.title}
                            message={submitError.message}
                            code={submitError.code}
                        />
                    </div>
                )}

                <form className={styles.form} onSubmit={submit} noValidate aria-busy={submitting}>
                    <div className={styles.field}>
                        <label htmlFor={`${id}-name`}>Workspace name</label>
                        <input
                            id={`${id}-name`}
                            ref={nameRef}
                            className={styles.input}
                            value={name}
                            maxLength={nameLimit}
                            placeholder="e.g. My Team"
                            required
                            disabled={submitting}
                            aria-invalid={nameError}
                            aria-describedby={nameError ? errorId : undefined}
                            onChange={(event) => {
                                setName(event.target.value);
                                if (nameError || !customSlug) setError(null);
                            }}
                        />
                    </div>

                    <div className={styles.field}>
                        <label htmlFor={`${id}-slug`}>Workspace URL</label>
                        <div className={styles.url} data-empty={!slugInputValue}>
                            <span id={`${id}-prefix`}>/w/</span>
                            <input
                                id={`${id}-slug`}
                                ref={slugRef}
                                value={slugInputValue}
                                maxLength={slugLimit}
                                placeholder={suggestedSlug || "your-workspace"}
                                spellCheck={false}
                                disabled={submitting}
                                aria-invalid={urlError}
                                aria-describedby={urlError ? `${id}-prefix ${errorId}` : `${id}-prefix`}
                                onChange={(event) => {
                                    setCustomSlug(event.target.value);
                                    if (urlError) setError(null);
                                }}
                                onBlur={(event) => {
                                    if (!event.currentTarget.value) setCustomSlug(null);
                                }}
                            />
                        </div>
                    </div>

                    <div className={styles.field}>
                        <label htmlFor={`${id}-description`}>Description</label>
                        <textarea
                            id={`${id}-description`}
                            name="description"
                            className={styles.input}
                            disabled={submitting}
                            placeholder="What will this workspace be used for?"
                        />
                    </div>

                    <p id={errorId} className={styles.error} aria-live="polite" aria-atomic="true" hidden={!error}>
                        {error?.message}
                    </p>
                    <p className={styles.owner}>You’ll be the owner of this workspace.</p>
                    <div className={styles.actions}>
                        <button type="button" className="devsphere-button" disabled={submitting} onClick={closeDialog}>
                            Cancel
                        </button>
                        <button type="submit" className="devsphere-button devsphere-button-primary" disabled={submitting}>
                            {submitting ? "Creating…" : "Create"}
                        </button>
                    </div>
                </form>
            </dialog>
        </>
    );
}
