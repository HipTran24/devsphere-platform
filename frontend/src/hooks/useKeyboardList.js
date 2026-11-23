"use client";

import { useCallback, useState } from "react";

export function isKeyboardListKey(key, orientation = "vertical") {
    if (key === "Home" || key === "End") return true;
    if (orientation === "horizontal") {
        return key === "ArrowRight" || key === "ArrowLeft";
    }
    if (orientation === "both") {
        return (
            key === "ArrowDown" ||
            key === "ArrowUp" ||
            key === "ArrowRight" ||
            key === "ArrowLeft"
        );
    }
    return key === "ArrowDown" || key === "ArrowUp";
}

export function nextKeyboardIndex(currentIndex, key, count, options = {}) {
    const { wrap = true, orientation = "vertical" } = options;

    if (typeof count !== "number" || count <= 0) return -1;

    const isForward =
        (orientation !== "horizontal" && key === "ArrowDown") ||
        (orientation !== "vertical" && key === "ArrowRight");
    const isBackward =
        (orientation !== "horizontal" && key === "ArrowUp") ||
        (orientation !== "vertical" && key === "ArrowLeft");

    if (key === "Home") return 0;
    if (key === "End") return count - 1;

    if (isForward) {
        if (currentIndex < 0) return 0;
        if (currentIndex >= count - 1) return wrap ? 0 : count - 1;
        return currentIndex + 1;
    }

    if (isBackward) {
        if (currentIndex < 0) return count - 1;
        if (currentIndex === 0) return wrap ? count - 1 : 0;
        return currentIndex - 1;
    }

    return currentIndex;
}

export default function useKeyboardList({
    count,
    initialIndex = 0,
    wrap = true,
    orientation = "vertical",
    onNavigate,
} = {}) {
    const [activeIndex, setActiveIndex] = useState(initialIndex);

    const handleKeyDown = useCallback(
        (event, currentOverride) => {
            if (!isKeyboardListKey(event.key, orientation)) return -1;
            const current = typeof currentOverride === "number" ? currentOverride : activeIndex;
            const next = nextKeyboardIndex(current, event.key, count, { wrap, orientation });
            if (next < 0) return -1;
            event.preventDefault();
            setActiveIndex(next);
            onNavigate?.(next);
            return next;
        },
        [activeIndex, count, onNavigate, orientation, wrap]
    );

    return {
        activeIndex,
        setActiveIndex,
        handleKeyDown,
    };
}
