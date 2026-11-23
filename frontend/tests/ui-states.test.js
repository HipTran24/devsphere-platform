import test from "node:test";
import assert from "node:assert/strict";
import {
    formatUiError,
    isRetryableWorkspaceStatus,
    resolveViewState,
} from "../src/components/ui/uiStateHelpers.js";
import {
    getFocusableElements,
    getNextTrapFocusIndex,
} from "../src/hooks/useFocusTrap.js";
import {
    isKeyboardListKey,
    nextKeyboardIndex,
} from "../src/hooks/useKeyboardList.js";

test("formatUiError handles null, string, RFC 7807, HTTP status, and digest objects", () => {
    assert.deepEqual(formatUiError(null), {
        title: "Something went wrong",
        message: "We couldn’t complete this request. Please try again.",
        code: undefined,
    });

    assert.deepEqual(formatUiError("   ", "Custom fallback."), {
        title: "Something went wrong",
        message: "Custom fallback.",
        code: undefined,
    });

    assert.deepEqual(formatUiError("Gateway unreachable."), {
        title: "Something went wrong",
        message: "Gateway unreachable.",
        code: undefined,
    });

    assert.deepEqual(
        formatUiError({
            status: 503,
            detail: "Database maintenance in progress.",
        }),
        {
            title: "Service unavailable",
            message: "Database maintenance in progress.",
            code: "HTTP_503",
        }
    );

    assert.deepEqual(
        formatUiError({
            status: 403,
            title: "Workspace Restricted",
            detail: "Administrator permissions required.",
            code: "ACCESS_DENIED",
        }),
        {
            title: "Workspace Restricted",
            message: "Administrator permissions required.",
            code: "ACCESS_DENIED",
        }
    );

    assert.deepEqual(
        formatUiError({
            message: "Rendered server crash",
            digest: "DIGEST-9981",
        }),
        {
            title: "Something went wrong",
            message: "Rendered server crash",
            code: "DIGEST-9981",
        }
    );
});

test("resolveViewState applies loading > error > empty > ready priority", () => {
    assert.equal(resolveViewState({ loading: true, error: "Boom", isEmpty: true }), "loading");
    assert.equal(resolveViewState({ loading: false, error: { message: "Fail" }, isEmpty: true }), "error");
    assert.equal(resolveViewState({ loading: false, error: null, isEmpty: true }), "empty");
    assert.equal(resolveViewState({ loading: false, error: null, isEmpty: false }), "ready");
    assert.equal(resolveViewState(), "ready");
});

test("isRetryableWorkspaceStatus classifies 5xx/408/429 as retryable and 404/401 as non-retryable", () => {
    for (const status of [500, 502, 503, 504, 408, 429]) {
        assert.equal(isRetryableWorkspaceStatus(status), true, `expected ${status} retryable`);
    }
    for (const status of [200, 204, 400, 401, 403, 404, NaN, undefined, null, "503"]) {
        assert.equal(isRetryableWorkspaceStatus(status), false, `expected ${String(status)} non-retryable`);
    }
});

test("nextKeyboardIndex navigates vertical, horizontal, Home/End, and wrap boundaries", () => {
    assert.equal(nextKeyboardIndex(0, "ArrowDown", 4), 1);
    assert.equal(nextKeyboardIndex(3, "ArrowDown", 4), 0);
    assert.equal(nextKeyboardIndex(3, "ArrowDown", 4, { wrap: false }), 3);

    assert.equal(nextKeyboardIndex(2, "ArrowUp", 4), 1);
    assert.equal(nextKeyboardIndex(0, "ArrowUp", 4), 3);
    assert.equal(nextKeyboardIndex(0, "ArrowUp", 4, { wrap: false }), 0);

    assert.equal(nextKeyboardIndex(-1, "ArrowDown", 4), 0);
    assert.equal(nextKeyboardIndex(-1, "ArrowUp", 4), 3);
    assert.equal(nextKeyboardIndex(2, "Home", 4), 0);
    assert.equal(nextKeyboardIndex(0, "End", 4), 3);
    assert.equal(nextKeyboardIndex(1, "Enter", 4), 1);
    assert.equal(nextKeyboardIndex(0, "ArrowDown", 0), -1);

    assert.equal(nextKeyboardIndex(0, "ArrowRight", 3, { orientation: "horizontal" }), 1);
    assert.equal(nextKeyboardIndex(0, "ArrowLeft", 3, { orientation: "horizontal" }), 2);
    assert.equal(nextKeyboardIndex(1, "ArrowDown", 3, { orientation: "horizontal" }), 1);

    assert.equal(isKeyboardListKey("ArrowDown", "vertical"), true);
    assert.equal(isKeyboardListKey("ArrowRight", "vertical"), false);
    assert.equal(isKeyboardListKey("ArrowRight", "horizontal"), true);
    assert.equal(isKeyboardListKey("Home", "vertical"), true);
    assert.equal(isKeyboardListKey("Tab", "both"), false);
});

test("focus trap helpers filter focusable elements and wrap Tab/Shift+Tab indices", () => {
    const makeNode = ({ disabled = false, hidden = false, ariaHidden = null, tabindex = null, inHiddenParent = false } = {}) => ({
        disabled,
        hasAttribute: (name) => name === "hidden" && hidden,
        getAttribute: (name) => {
            if (name === "aria-hidden") return ariaHidden;
            if (name === "tabindex") return tabindex;
            return null;
        },
        closest: () => (inHiddenParent ? {} : null),
    });

    const visibleButton = makeNode();
    const disabledButton = makeNode({ disabled: true });
    const hiddenButton = makeNode({ hidden: true });
    const ariaHiddenNode = makeNode({ ariaHidden: "true" });
    const negativeTabNode = makeNode({ tabindex: "-1" });
    const inertChild = makeNode({ inHiddenParent: true });
    const visibleInput = makeNode({ tabindex: "0" });

    const container = {
        querySelectorAll: () => [
            visibleButton,
            disabledButton,
            hiddenButton,
            ariaHiddenNode,
            negativeTabNode,
            inertChild,
            visibleInput,
        ],
    };

    assert.deepEqual(getFocusableElements(null), []);
    assert.deepEqual(getFocusableElements(container), [visibleButton, visibleInput]);

    assert.equal(getNextTrapFocusIndex(0, 3, false), 1);
    assert.equal(getNextTrapFocusIndex(2, 3, false), 0);
    assert.equal(getNextTrapFocusIndex(-1, 3, false), 0);
    assert.equal(getNextTrapFocusIndex(0, 3, true), 2);
    assert.equal(getNextTrapFocusIndex(2, 3, true), 1);
    assert.equal(getNextTrapFocusIndex(-1, 3, true), 2);
    assert.equal(getNextTrapFocusIndex(0, 0, false), -1);
});
