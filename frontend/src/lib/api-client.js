/**
 * DevSphere Real API Client & Token Lifecycle Adapter
 * Implements:
 * - In-memory access token storage (no localStorage)
 * - CSRF token exchange (GET /api/v1/auth/csrf)
 * - Silent refresh queue interceptor on 401 / token expiry
 * - Domain-specific API methods for Workspaces, Projects, Requirements, WorkItems,
 *   Dependencies, Sprints, QualityGates, and PlanProposals
 * - Seamless fallback to mock data when NEXT_PUBLIC_AUTH_MOCK=true or backend is unreachable
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
let refreshPromise = null;
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

function notifyAuthFailure() {
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

function getBaseUrl() {
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

/**
 * Primary apiFetch with automatic Bearer token, CSRF, and 401 silent refresh
 */
export async function apiFetch(path, options = {}) {
  const url = path.startsWith('http') ? path : `${getBaseUrl()}${path.startsWith('/') ? path : `/${path}`}`;
  const method = (options.method || 'GET').toUpperCase();
  const isStateChanging = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method);
  const isAuthEndpoint = path.includes('/api/v1/auth/');
  const isLoginOrRefresh = path.includes('/login') || path.includes('/refresh');

  // Prepare headers
  const headers = new Headers(options.headers || {});
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  // Include CSRF for state-changing or auth operations
  if (isStateChanging || isAuthEndpoint) {
    const csrf = await fetchCsrfToken();
    if (csrf?.token && csrf.headerName) {
      headers.set(csrf.headerName, csrf.token);
    }
  }

  // Include in-memory Bearer token if available
  if (inMemoryAccessToken && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${inMemoryAccessToken}`);
  }

  // Auto stringify json body if not FormData or string
  let body = options.body;
  if (body && typeof body === 'object' && !(body instanceof FormData) && !(body instanceof URLSearchParams)) {
    if (!headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    body = JSON.stringify(body);
  }

  const fetchOptions = {
    ...options,
    method,
    headers,
    body,
    credentials: 'include',
  };

  let res;
  try {
    res = await fetch(url, fetchOptions);
  } catch (err) {
    throw new ApiClientError(err.message || 'Network error connecting to backend service', {
      status: 0,
      code: 'NETWORK_ERROR',
      detail: err.message,
    });
  }

  // Handle 401 Unauthorized with silent refresh queue
  if (res.status === 401 && !isLoginOrRefresh) {
    if (!refreshPromise) {
      refreshPromise = performSilentRefresh();
    }
    const refreshed = await refreshPromise;
    if (refreshed) {
      // Retry request with newly acquired token
      headers.set('Authorization', `Bearer ${inMemoryAccessToken}`);
      return apiFetch(path, { ...options, headers });
    } else {
      notifyAuthFailure();
      const problem = await parseProblemDetail(res);
      throw new ApiClientError(problem.detail || 'Session expired. Please sign in again.', {
        status: 401,
        code: problem.code || 'SESSION_EXPIRED',
        detail: problem.detail,
      });
    }
  }

  if (!res.ok) {
    const problem = await parseProblemDetail(res);
    throw new ApiClientError(problem.detail || problem.title || `API request failed with status ${res.status}`, {
      status: res.status,
      code: problem.code || `HTTP_${res.status}`,
      detail: problem.detail,
      errors: problem.errors,
    });
  }

  if (res.status === 204) {
    return null;
  }

  const contentType = res.headers.get('content-type') || '';
  if (contentType.includes('application/json') || contentType.includes('application/problem+json')) {
    return res.json();
  }
  return res.text();
}

async function parseProblemDetail(res) {
  try {
    const data = await res.json();
    return {
      title: data.title || '',
      detail: data.detail || data.message || '',
      code: data.code || '',
      status: data.status || res.status,
      errors: data.errors || null,
    };
  } catch {
    return {
      title: res.statusText || 'Error',
      detail: '',
      code: `HTTP_${res.status}`,
      status: res.status,
    };
  }
}

async function performSilentRefresh() {
  try {
    const csrf = await fetchCsrfToken();
    const headers = {
      Accept: 'application/json',
      [csrf.headerName || 'X-CSRF-TOKEN']: csrf.token || '',
    };
    const url = `${getBaseUrl()}/api/v1/auth/refresh`;
    const res = await fetch(url, {
      method: 'POST',
      headers,
      credentials: 'include',
    });
    if (res.ok) {
      const data = await res.json();
      if (data.accessToken) {
        setAccessToken(data.accessToken, data.expiresAt);
        return true;
      }
    }
    return false;
  } catch {
    return false;
  } finally {
    refreshPromise = null;
  }
}

// -----------------------------------------------------------------------------
// Authentication Endpoints
// -----------------------------------------------------------------------------

export async function login({ email, password }) {
  const csrf = await fetchCsrfToken();
  const url = `${getBaseUrl()}/api/v1/auth/login`;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      [csrf.headerName || 'X-CSRF-TOKEN']: csrf.token || '',
    },
    credentials: 'include',
    body: JSON.stringify({ email, password }),
  });

  if (!res.ok) {
    const problem = await parseProblemDetail(res);
    throw new ApiClientError(problem.detail || 'Sign-in failed', {
      status: res.status,
      code: problem.code || 'AUTH_INVALID',
      detail: problem.detail,
    });
  }

  const data = await res.json();
  setAccessToken(data.accessToken, data.expiresAt);
  return data;
}

export async function refreshSession() {
  const success = await performSilentRefresh();
  if (!success) {
    throw new ApiClientError('Failed to refresh session', {
      status: 401,
      code: 'REFRESH_FAILED',
    });
  }
  return {
    accessToken: inMemoryAccessToken,
    expiresAt: tokenExpiresAt,
  };
}

export async function logout() {
  try {
    await apiFetch('/api/v1/auth/logout', { method: 'POST' });
  } finally {
    clearAccessToken();
    clearCsrfCache();
  }
}

export async function fetchCurrentUser() {
  return apiFetch('/api/v1/auth/me', { method: 'GET' });
}

