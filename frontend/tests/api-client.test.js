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
