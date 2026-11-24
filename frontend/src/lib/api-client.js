/**
 * DevSphere Real API Client & Token Lifecycle Adapter
 * Implements:
 * - In-memory access token storage (no localStorage)
 * - CSRF token exchange (GET /api/v1/auth/csrf)
 */

export class ApiClientError extends Error {
  constructor(message, { status = 500, code = 'API_ERROR', detail = '', errors = null } = {}) {
    super(message || detail || 'An unexpected API error occurred');
    this.name = 'ApiClientError';
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.errors = errors;
  }
}

// In-memory token state
let inMemoryAccessToken = null;
let tokenExpiresAt = null;
let csrfData = null; // { headerName, parameterName, token }
const authFailureListeners = new Set();

export function setAccessToken(token, expiresAt) {
  inMemoryAccessToken = token;
  tokenExpiresAt = expiresAt ? new Date(expiresAt).getTime() : null;
}

export function getAccessToken() {
  return inMemoryAccessToken;
}

export function getTokenExpiresAt() {
  return tokenExpiresAt;
}

export function clearAccessToken() {
  inMemoryAccessToken = null;
  tokenExpiresAt = null;
}

export function clearCsrfCache() {
  csrfData = null;
}

export function onAuthFailure(listener) {
  authFailureListeners.add(listener);
  return () => authFailureListeners.delete(listener);
}

export function notifyAuthFailure() {
  clearAccessToken();
  for (const listener of authFailureListeners) {
    try {
      listener();
    } catch {
      // ignore errors in listeners
    }
  }
}

// Environment flags
export function isMockAuthEnabled() {
  if (typeof process === 'undefined') return false;
  if (process.env.NEXT_PUBLIC_AUTH_MOCK === 'true') return true;
  if (process.env.NEXT_PUBLIC_AUTH_MOCK === 'false') return false;
  return process.env.NODE_ENV === 'development';
}

export function getBaseUrl() {
  if (typeof window !== 'undefined') {
    return '';
  }
  return (process.env.DEVSPHERE_BACKEND_URL || 'http://localhost:8080').replace(/\/$/, '');
}

/**
 * Fetch and cache CSRF token from Spring Security endpoint
 */
export async function fetchCsrfToken(force = false) {
  if (!force && csrfData?.token) {
    return csrfData;
  }
  const url = `${getBaseUrl()}/api/v1/auth/csrf`;
  try {
    const res = await fetch(url, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'include',
    });
    if (res.ok) {
      const data = await res.json();
      csrfData = {
        headerName: data.headerName || 'X-CSRF-TOKEN',
        parameterName: data.parameterName || '_csrf',
        token: data.token,
      };
      return csrfData;
    }
  } catch {
    // Return placeholder if unreachable
  }
  return { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: '' };
}
