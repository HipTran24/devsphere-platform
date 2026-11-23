"use client";

import { useEffect, useRef } from "react";

export const FOCUSABLE_SELECTOR = [
    "a[href]",
    "area[href]",
    "button:not([disabled])",
    "input:not([disabled]):not([type='hidden'])",
    "select:not([disabled])",
    "textarea:not([disabled])",
    "summary",
    "[tabindex]:not([tabindex='-1'])",
    "[contenteditable='true']",
].join(", ");

export function getFocusableElements(container) {
    if (!container || typeof container.querySelectorAll !== "function") {
        return [];
    }

    const candidates = Array.from(container.querySelectorAll(FOCUSABLE_SELECTOR));

    return candidates.filter((element) => {
        if (!element || element.disabled) return false;
        if (typeof element.hasAttribute === "function" && element.hasAttribute("hidden")) return false;
        if (typeof element.getAttribute === "function") {
            if (element.getAttribute("aria-hidden") === "true") return false;
            if (element.getAttribute("tabindex") === "-1") return false;
        }
        if (typeof element.closest === "function" && element.closest("[hidden], [inert]")) {
            return false;
        }
        if (
            typeof window !== "undefined" &&
            typeof window.getComputedStyle === "function" &&
            typeof Element !== "undefined" &&
            element instanceof Element
        ) {
            const style = window.getComputedStyle(element);
            if (style.display === "none" || style.visibility === "hidden") return false;
        }
        return true;
    });
}

export function getNextTrapFocusIndex(currentIndex, total, shiftKey = false) {
    if (typeof total !== "number" || total <= 0) return -1;

    if (shiftKey) {
        if (currentIndex <= 0 || currentIndex >= total) return total - 1;
        return currentIndex - 1;
    }

    if (currentIndex < 0 || currentIndex >= total - 1) return 0;
    return currentIndex + 1;
}

export default function useFocusTrap({
    active,
    containerRef,
    initialFocusRef,
    onEscape,
    restoreFocus = true,
}) {
    const onEscapeRef = useRef(onEscape);
    useEffect(() => {
        onEscapeRef.current = onEscape;
    }, [onEscape]);

    useEffect(() => {
        if (!active) return;

        const container = containerRef?.current;
        if (!container) return;

        const previousActive = typeof document !== "undefined" ? document.activeElement : null;

        const initialTarget = initialFocusRef?.current || getFocusableElements(container)[0] || container;
        if (initialTarget && typeof initialTarget.focus === "function") {
            initialTarget.focus();
        }

        function handleKeyDown(event) {
            if (event.key === "Escape") {
                event.preventDefault();
                event.stopPropagation();
                onEscapeRef.current?.();
                return;
            }

            if (event.key !== "Tab") return;

            const focusables = getFocusableElements(container);
            if (focusables.length === 0) {
                event.preventDefault();
                if (typeof container.focus === "function") container.focus();
                return;
            }

            const currentIndex = focusables.indexOf(document.activeElement);
            const nextIndex = getNextTrapFocusIndex(currentIndex, focusables.length, event.shiftKey);
            event.preventDefault();
            focusables[nextIndex]?.focus();
        }

        document.addEventListener("keydown", handleKeyDown);

        return () => {
            document.removeEventListener("keydown", handleKeyDown);
            if (
                restoreFocus &&
                previousActive &&
                typeof previousActive.focus === "function" &&
                previousActive.isConnected !== false
            ) {
                previousActive.focus();
            }
        };
    }, [active, containerRef, initialFocusRef, restoreFocus]);
}
