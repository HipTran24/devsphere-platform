const STATUS_TITLES = {
    400: "Invalid request",
    401: "Sign in required",
    403: "Access denied",
    404: "Not found",
    408: "Request timed out",
    409: "Conflict detected",
    429: "Too many requests",
    500: "Server error",
    502: "Bad gateway",
    503: "Service unavailable",
    504: "Gateway timeout",
};

const DEFAULT_ERROR_TITLE = "Something went wrong";
const DEFAULT_ERROR_MESSAGE = "We couldn’t complete this request. Please try again.";

export function formatUiError(error, fallbackMessage = DEFAULT_ERROR_MESSAGE) {
    const safeFallback = typeof fallbackMessage === "string" && fallbackMessage.trim()
        ? fallbackMessage.trim()
        : DEFAULT_ERROR_MESSAGE;

    if (!error) {
        return {
            title: DEFAULT_ERROR_TITLE,
            message: safeFallback,
            code: undefined,
        };
    }

    if (typeof error === "string") {
        const trimmed = error.trim();
        return {
            title: DEFAULT_ERROR_TITLE,
            message: trimmed || safeFallback,
            code: undefined,
        };
    }

    const numericStatus = typeof error.status === "number" && Number.isFinite(error.status)
        ? error.status
        : undefined;

    const rawCode = error.code ?? error.digest ?? (numericStatus ? `HTTP_${numericStatus}` : undefined);
    const code = rawCode !== undefined && rawCode !== null && String(rawCode).trim()
        ? String(rawCode).trim()
        : undefined;

    const rawTitle = typeof error.title === "string" && error.title.trim()
        ? error.title.trim()
        : numericStatus && STATUS_TITLES[numericStatus]
            ? STATUS_TITLES[numericStatus]
            : DEFAULT_ERROR_TITLE;

    const candidates = [error.detail, error.message];
    const resolvedMessage = candidates.find((value) => typeof value === "string" && value.trim())?.trim()
        || safeFallback;

    return {
        title: rawTitle,
        message: resolvedMessage,
        code,
    };
}

export function resolveViewState({ loading = false, error = null, isEmpty = false } = {}) {
    if (loading) return "loading";
    if (error) return "error";
    if (isEmpty) return "empty";
    return "ready";
}

export function isRetryableWorkspaceStatus(status) {
    if (typeof status !== "number" || !Number.isFinite(status)) return false;
    return status >= 500 || status === 408 || status === 429;
}
