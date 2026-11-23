import { Layers, X } from "lucide-react";
import { useId, useRef, useState } from "react";
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
    const dialogRef = useRef(null);
    const nameRef = useRef(null);
    const slugRef = useRef(null);
    const errorId = `${id}-error`;
    const [name, setName] = useState("");
    const [customSlug, setCustomSlug] = useState(null);
    const [error, setError] = useState(null);
    const suggestedSlug = makeSlug(name);
    const slugInputValue = customSlug ?? suggestedSlug;
    const slug = customSlug || suggestedSlug;

    function openDialog() {
        dialogRef.current.showModal();
        nameRef.current.focus();
    }

    function resetForm() {
        setName("");
        setCustomSlug(null);
        setError(null);
        dialogRef.current.querySelector("form").reset();
    }

    function showError(field, message, inputRef) {
        setError({ field, message });
        inputRef.current.focus();
    }

    function submit(event) {
        event.preventDefault();
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
        dialogRef.current.close();
        onCreate({ name: trimmedName, slug, description });
    }

    const nameError = error?.field === "name";
    const urlError = error?.field === "slug";

    return (
        <>
            <button type="button" className={className} aria-haspopup="dialog" onClick={openDialog}>
                Create workspace
            </button>
            <dialog ref={dialogRef} className={styles.dialog} aria-labelledby={`${id}-title`} onClose={resetForm}>
                <div className={styles.header}>
                    <h2 id={`${id}-title`}>
                        <Layers size={20} aria-hidden="true" />
                        Create workspace
                    </h2>
                    <button type="button" className="devsphere-button" aria-label="Close create workspace" onClick={() => dialogRef.current.close()}>
                        <X size={20} aria-hidden="true" />
                    </button>
                </div>

                <form className={styles.form} onSubmit={submit} noValidate>
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
                            placeholder="What will this workspace be used for?"
                        />
                    </div>

                    <p id={errorId} className={styles.error} aria-live="polite" aria-atomic="true" hidden={!error}>
                        {error?.message}
                    </p>
                    <p className={styles.owner}>You’ll be the owner of this workspace.</p>
                    <div className={styles.actions}>
                        <button type="button" className="devsphere-button" onClick={() => dialogRef.current.close()}>Cancel</button>
                        <button type="submit" className="devsphere-button devsphere-button-primary">Create</button>
                    </div>
                </form>
            </dialog>
        </>
    );
}
