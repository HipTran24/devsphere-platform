import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom Metrics
const failureRate = new Rate('devsphere_failed_requests');
const auditExportDuration = new Trend('devsphere_audit_export_duration');
const workspaceListDuration = new Trend('devsphere_workspace_list_duration');

export const options = {
  stages: [
    { duration: '30s', target: 20 },  // Ramp-up to 20 users
    { duration: '1m', target: 50 },   // Normal operational load: 50 concurrent users
    { duration: '30s', target: 100 }, // Peak spike load: 100 users
    { duration: '1m', target: 100 },  // Sustained peak load
    { duration: '30s', target: 0 },   // Graceful ramp-down
  ],
  thresholds: {
    // 99% of requests must complete below 1.5s; 95% below 500ms
    http_req_duration: ['p(95)<500', 'p(99)<1500'],
    // HTTP failure rate must stay below 1%
    devsphere_failed_requests: ['rate<0.01'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || 'test_admin_bearer_token';

const HEADERS_JSON = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
};

const HEADERS_AUTH = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
  Authorization: `Bearer ${ADMIN_TOKEN}`,
};

export default function () {
  // Scenario 1: Public Health & Liveness Probes
  group('Liveness & Actuator Probes', () => {
    const healthRes = http.get(`${BASE_URL}/actuator/health`);
    const isHealthOk = check(healthRes, {
      'actuator health returns 200': (r) => r.status === 200,
      'actuator status is UP': (r) => r.json('status') === 'UP',
    });
    failureRate.add(!isHealthOk);
  });

  sleep(0.5);

  // Scenario 2: Operations Admin & Monitoring Endpoints
  group('Operations Admin Overview & Job Stats', () => {
    const statsRes = http.get(`${BASE_URL}/api/v1/admin/operations/jobs/stats`, {
      headers: HEADERS_AUTH,
    });
    const isStatsOk = check(statsRes, {
      'job stats returns 200 or 401 (if unauth)': (r) => r.status === 200 || r.status === 401,
    });
    failureRate.add(!isStatsOk);

    const auditSummaryRes = http.get(`${BASE_URL}/api/v1/admin/operations/audit/summary`, {
      headers: HEADERS_AUTH,
    });
    const isAuditOk = check(auditSummaryRes, {
      'audit summary returns 200 or 401': (r) => r.status === 200 || r.status === 401,
    });
    failureRate.add(!isAuditOk);
    if (auditSummaryRes.status === 200) {
      auditExportDuration.add(auditSummaryRes.timings.duration);
    }
  });

  sleep(0.5);

  // Scenario 3: Workspaces Listing
  group('Workspace Queries', () => {
    const wsRes = http.get(`${BASE_URL}/api/v1/workspaces`, {
      headers: HEADERS_AUTH,
    });
    const isWsOk = check(wsRes, {
      'workspaces returns valid response': (r) => r.status === 200 || r.status === 401,
    });
    failureRate.add(!isWsOk);
    if (wsRes.status === 200) {
      workspaceListDuration.add(wsRes.timings.duration);
    }
  });

  sleep(1);
}
