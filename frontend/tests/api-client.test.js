import test from 'node:test';
import assert from 'node:assert/strict';
import {
  setAccessToken,
  getAccessToken,
  getTokenExpiresAt,
  clearAccessToken,
  clearCsrfCache,
  onAuthFailure,
  fetchCsrfToken,
  apiFetch,
  login,
  refreshSession,
  logout,
  fetchCurrentUser,
  fetchWorkspaces,
  getWorkspace,
  createWorkspace,
  updateWorkspace,
  fetchProjects,
  getProject,
  createProject,
  activateProject,
  fetchRequirements,
  createRequirement,
  fetchWorkItems,
  fetchBacklog,
  createWorkItem,
  updateWorkItem,
  deleteWorkItem,
  transitionWorkItemStatus,
  blockWorkItem,
  acceptWorkItem,
  rejectWorkItem,
  fetchDependencies,
  createDependency,
  deleteDependency,
  fetchSprints,
  getSprint,
  createSprint,
  startSprint,
  closeSprint,
  getSprintProgress,
  addSprintItems,
  removeSprintItems,
  fetchQualityGate,
  fetchPlanProposals,
  getPlanProposal,
  createPlanProposal,
  solvePlanProposal,
  previewPlanProposal,
  approvePlanProposal,
  confirmDeadline,
  applyPlanProposal,
  ApiClientError,
} from '../src/lib/api-client.js';

test('api-client token lifecycle operates strictly in-memory without persistent storage leaks', () => {
  clearAccessToken();
  assert.equal(getAccessToken(), null);
  assert.equal(getTokenExpiresAt(), null);

  const testToken = 'jwt-access-token-xyz';
  const expires = new Date(Date.now() + 900000).toISOString();
  setAccessToken(testToken, expires);

  assert.equal(getAccessToken(), testToken);
  assert.ok(getTokenExpiresAt() > Date.now());

  clearAccessToken();
  assert.equal(getAccessToken(), null);
  assert.equal(getTokenExpiresAt(), null);
});

test('CSRF exchange fetches once, caches, and supports forced invalidation', async () => {
  const originalFetch = globalThis.fetch;
  let csrfCalls = 0;

  globalThis.fetch = async (url) => {
    if (url.includes('/api/v1/auth/csrf')) {
      csrfCalls += 1;
      return new Response(
        JSON.stringify({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'mock-csrf-token-123' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    }
    return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
  };

  try {
    clearCsrfCache();
    const first = await fetchCsrfToken();
    assert.equal(first.token, 'mock-csrf-token-123');
    assert.equal(csrfCalls, 1);

    // Second call should return cached token without invoking fetch again
    const second = await fetchCsrfToken();
    assert.equal(second.token, 'mock-csrf-token-123');
    assert.equal(csrfCalls, 1);

    // Force refresh triggers another fetch
    const refreshed = await fetchCsrfToken(true);
    assert.equal(refreshed.token, 'mock-csrf-token-123');
    assert.equal(csrfCalls, 2);
  } finally {
    globalThis.fetch = originalFetch;
    clearCsrfCache();
  }
});

test('apiFetch attaches Authorization and CSRF headers for state-changing requests', async () => {
  const originalFetch = globalThis.fetch;
  const recordedRequests = [];

  globalThis.fetch = async (url, options) => {
    if (url.includes('/api/v1/auth/csrf')) {
      return new Response(
        JSON.stringify({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-active-999' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    }

    recordedRequests.push({ url, options });
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  try {
    clearCsrfCache();
    setAccessToken('bearer-token-123');

    // GET request (no CSRF required, but Bearer attached)
    await apiFetch('/api/v1/workspaces');
    assert.equal(recordedRequests.length, 1);
    assert.equal(recordedRequests[0].options.headers.get('Authorization'), 'Bearer bearer-token-123');

    // POST request (both Bearer and CSRF attached, body stringified)
    await apiFetch('/api/v1/workspaces', {
      method: 'POST',
      body: { name: 'DevSphere Test', slug: 'devsphere-test' },
    });
    assert.equal(recordedRequests.length, 2);
    const postReq = recordedRequests[1];
    assert.equal(postReq.options.headers.get('Authorization'), 'Bearer bearer-token-123');
    assert.equal(postReq.options.headers.get('X-CSRF-TOKEN'), 'csrf-active-999');
    assert.equal(postReq.options.headers.get('Content-Type'), 'application/json');
    assert.equal(postReq.options.body, JSON.stringify({ name: 'DevSphere Test', slug: 'devsphere-test' }));
  } finally {
    globalThis.fetch = originalFetch;
    clearAccessToken();
    clearCsrfCache();
  }
});

test('silent refresh interceptor transparently retries 401 requests with new access token', async () => {
  const originalFetch = globalThis.fetch;
  let attempts = 0;
  let refreshCalls = 0;

  globalThis.fetch = async (url, options) => {
    if (url.includes('/api/v1/auth/csrf')) {
      return new Response(
        JSON.stringify({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-token' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    }
    if (url.includes('/api/v1/auth/refresh')) {
      refreshCalls += 1;
      return new Response(
        JSON.stringify({ accessToken: 'new-refreshed-jwt', expiresAt: new Date(Date.now() + 900000).toISOString() }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    }

    if (url.includes('/api/v1/workspaces')) {
      attempts += 1;
      const auth = options.headers?.get('Authorization');
      if (attempts === 1) {
        // First attempt fails with 401
        return new Response(
          JSON.stringify({ code: 'TOKEN_EXPIRED', status: 401, detail: 'Token has expired' }),
          { status: 401, headers: { 'Content-Type': 'application/json' } }
        );
      }
      // Second attempt succeeds if new token is presented
      assert.equal(auth, 'Bearer new-refreshed-jwt');
      return new Response(
        JSON.stringify({ items: [{ id: 'ws-1', name: 'DevSphere Core' }] }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    }

    return new Response('{}', { status: 200 });
  };

  try {
    clearCsrfCache();
    setAccessToken('stale-jwt-token');

    const result = await apiFetch('/api/v1/workspaces');
    assert.equal(attempts, 2);
    assert.equal(refreshCalls, 1);
    assert.equal(getAccessToken(), 'new-refreshed-jwt');
    assert.deepEqual(result, { items: [{ id: 'ws-1', name: 'DevSphere Core' }] });
  } finally {
    globalThis.fetch = originalFetch;
    clearAccessToken();
    clearCsrfCache();
  }
});

test('failed refresh triggers onAuthFailure notification and rejects with 401', async () => {
  const originalFetch = globalThis.fetch;
  let authFailedNotified = false;

  globalThis.fetch = async (url) => {
    if (url.includes('/api/v1/auth/csrf')) {
      return new Response(
        JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    }
    if (url.includes('/api/v1/auth/refresh')) {
      return new Response(
        JSON.stringify({ code: 'INVALID_SESSION', status: 401, detail: 'Session expired' }),
        { status: 401, headers: { 'Content-Type': 'application/json' } }
      );
    }
    return new Response(
      JSON.stringify({ code: 'UNAUTHORIZED', status: 401, detail: 'Invalid credentials' }),
      { status: 401, headers: { 'Content-Type': 'application/json' } }
    );
  };

  const unsubscribe = onAuthFailure(() => {
    authFailedNotified = true;
  });

  try {
    clearCsrfCache();
    setAccessToken('expired-token');

    await assert.rejects(
      async () => {
        await apiFetch('/api/v1/workspaces');
      },
      (err) => {
        assert.ok(err instanceof ApiClientError);
        assert.equal(err.status, 401);
        return true;
      }
    );

    assert.equal(authFailedNotified, true);
    assert.equal(getAccessToken(), null);
  } finally {
    unsubscribe();
    globalThis.fetch = originalFetch;
    clearAccessToken();
    clearCsrfCache();
  }
});

test('RFC 7807 problem details are translated into structured ApiClientError', async () => {
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async () => {
    return new Response(
      JSON.stringify({
        title: 'Conflict',
        status: 409,
        detail: 'Workspace slug is already in use',
        code: 'SLUG_CONFLICT',
        errors: [{ field: 'slug', reason: 'already exists' }],
      }),
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } }
    );
  };

  try {
    await assert.rejects(
      async () => {
        await apiFetch('/api/v1/workspaces', { method: 'POST', body: { slug: 'devsphere' } });
      },
      (err) => {
        assert.ok(err instanceof ApiClientError);
        assert.equal(err.status, 409);
        assert.equal(err.code, 'SLUG_CONFLICT');
        assert.equal(err.detail, 'Workspace slug is already in use');
        assert.equal(err.errors?.[0]?.field, 'slug');
        return true;
      }
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('auth methods (login, refreshSession, logout, fetchCurrentUser) format and execute correctly', async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];

  globalThis.fetch = async (url, options) => {
    calls.push({ url, method: options?.method || 'GET', headers: options?.headers });
    if (url.includes('/api/v1/auth/csrf')) {
      return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-1' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    if (url.includes('/api/v1/auth/login')) {
      return new Response(JSON.stringify({ accessToken: 'login-token', expiresAt: '2026-11-24T18:00:00Z' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    if (url.includes('/api/v1/auth/refresh')) {
      return new Response(JSON.stringify({ accessToken: 'refresh-token', expiresAt: '2026-11-24T18:15:00Z' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    if (url.includes('/api/v1/auth/me')) {
      return new Response(JSON.stringify({ userId: 'u-1', displayName: 'Sarah PM', systemRole: 'USER' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    if (url.includes('/api/v1/auth/logout')) {
      return new Response(null, { status: 204 });
    }
    return new Response('{}', { status: 200 });
  };

  try {
    clearCsrfCache();
    clearAccessToken();

    // 1. login
    const loginRes = await login({ email: 'pm@devsphere.io', password: 'DevSpherePm2026!' });
    assert.equal(loginRes.accessToken, 'login-token');
    assert.equal(getAccessToken(), 'login-token');

    // 2. fetchCurrentUser
    const me = await fetchCurrentUser();
    assert.equal(me.displayName, 'Sarah PM');

    // 3. refreshSession
    const refreshed = await refreshSession();
    assert.equal(refreshed.accessToken, 'refresh-token');
    assert.equal(getAccessToken(), 'refresh-token');

    // 4. logout
    await logout();
    assert.equal(getAccessToken(), null);
  } finally {
    globalThis.fetch = originalFetch;
    clearAccessToken();
    clearCsrfCache();
  }
});

test('domain API methods invoke correct routes and HTTP methods', async () => {
  const originalFetch = globalThis.fetch;
  const routesCalled = [];

  globalThis.fetch = async (url, options) => {
    routesCalled.push({ url, method: (options?.method || 'GET').toUpperCase() });
    return new Response(JSON.stringify({ id: 'dummy-res', ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  try {
    clearCsrfCache();
    const wId = 'w-101';
    const pId = 'p-202';
    const wiId = 'wi-303';
    const sId = 's-404';
    const propId = 'prop-505';

    // Workspaces & Projects
    await fetchWorkspaces({ page: 1, size: 10 });
    await getWorkspace(wId);
    await createWorkspace({ name: 'DevSphere Core', slug: 'devsphere-core' });
    await updateWorkspace(wId, { name: 'Updated' });
    await fetchProjects(wId);
    await getProject(wId, pId);
    await createProject(wId, { projectKey: 'DSP', name: 'DevSphere Platform' });
    await activateProject(wId, pId);

    // Requirements
    await fetchRequirements(wId, pId, { status: 'APPROVED' });
    await createRequirement(wId, pId, { title: 'Auth' });

    // WorkItems & Backlog
    await fetchWorkItems(wId, pId, { status: 'IN_PROGRESS', type: 'TASK' });
    await fetchBacklog(wId, pId);
    await createWorkItem(wId, pId, { title: 'Implement proxy' });
    await updateWorkItem(wId, pId, wiId, { title: 'New title' });
    await deleteWorkItem(wId, pId, wiId);
    await transitionWorkItemStatus(wId, pId, wiId, { targetStatus: 'DONE' });
    await blockWorkItem(wId, pId, wiId, { blocked: true, blockedReason: 'Blocked on API' });
    await acceptWorkItem(wId, pId, wiId);
    await rejectWorkItem(wId, pId, wiId, { reason: 'Test failed' });

    // Dependencies
    await fetchDependencies(wId, pId);
    await createDependency(wId, pId, { predecessorId: 'a', successorId: 'b' });
    await deleteDependency(wId, pId, 'dep-1');

    // Sprints
    await fetchSprints(wId, pId, { status: 'ACTIVE' });
    await getSprint(wId, pId, sId);
    await createSprint(wId, pId, { name: 'Sprint 1' });
    await startSprint(wId, pId, sId);
    await closeSprint(wId, pId, sId);
    await getSprintProgress(wId, pId, sId);
    await addSprintItems(wId, pId, sId, ['item-1']);
    await removeSprintItems(wId, pId, sId, ['item-1']);

    // Quality Gate
    await fetchQualityGate(wId, pId, wiId);

    // Plan Proposals
    await fetchPlanProposals(wId, pId);
    await getPlanProposal(wId, pId, propId);
    await createPlanProposal(wId, pId, { kind: 'AUTO_REPLAN' });
    await solvePlanProposal(wId, pId, propId, { maxTimeSeconds: 10 });
    await previewPlanProposal(wId, pId, propId);
    await approvePlanProposal(wId, pId, propId, { notes: 'Approved' });
    await confirmDeadline(wId, pId, propId, { rationale: 'Accept delay' });
    await applyPlanProposal(wId, pId, propId);

    // Verify all routes were called
    assert.ok(routesCalled.some(r => r.url.includes('/api/v1/workspaces?page=1&size=10')));
    assert.ok(routesCalled.some(r => r.url.includes(`/api/v1/workspaces/${wId}`) && r.method === 'GET'));
    assert.ok(routesCalled.some(r => r.url.includes('/api/v1/workspaces') && r.method === 'POST'));
    assert.ok(routesCalled.some(r => r.url.includes(`/api/v1/workspaces/${wId}/projects/${pId}/backlog`)));
    assert.ok(routesCalled.some(r => r.url.includes(`/work-items/${wiId}/accept`) && r.method === 'POST'));
    assert.ok(routesCalled.some(r => r.url.includes(`/work-items/${wiId}/blocked`) && r.method === 'PATCH'));
    assert.ok(routesCalled.some(r => r.url.includes(`/work-items/${wiId}/quality-gate`)));
    assert.ok(routesCalled.some(r => r.url.includes(`/plan-proposals/${propId}/solve`) && r.method === 'POST'));
    assert.ok(routesCalled.some(r => r.url.includes(`/plan-proposals/${propId}/preview`)));
  } finally {
    globalThis.fetch = originalFetch;
  }
});
