"use client";

import { useRef, useState } from "react";
import { focusFirstError } from "./validation";

export default function useAuthForm() {
    const busy = useRef(false);
    const errorRef = useRef(null);
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [fieldErrors, setFieldErrors] = useState({});
    const [formError, setFormError] = useState("");

    async function submit(event, validate, action) {
        event.preventDefault();
        if (busy.current) return;
        const form = event.currentTarget;
        const data = Object.fromEntries(new FormData(form));
        const errors = validate(data);
        setFieldErrors(errors);
        setFormError("");
        if (Object.keys(errors).length) {
            focusFirstError(form, errors);
            return;
        }
        busy.current = true;
        setIsSubmitting(true);
        try {
            await action(data, form);
        } catch (error) {
            if (error.fields) {
                setFieldErrors(error.fields);
                setFormError(error.message);
                requestAnimationFrame(() => errorRef.current?.focus());
            } else if (error.field) {
                setFieldErrors({ [error.field]: error.message });
                requestAnimationFrame(() => form.elements.namedItem(error.field)?.focus());
            } else {
                setFormError(error.message || "Something went wrong. Please try again.");
                requestAnimationFrame(() => errorRef.current?.focus());
            }
        } finally {
            busy.current = false;
            setIsSubmitting(false);
        }
    }

    function reportError(error) {
        setFormError(error.message || 'Please try again.');
        requestAnimationFrame(() => errorRef.current?.focus());
    }
    return { isSubmitting, fieldErrors, formError, errorRef, submit, reportError };
}
