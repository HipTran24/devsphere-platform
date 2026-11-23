# DevSphere Performance & Load Testing Runbook (k6)

This directory contains automated performance tests for DevSphere using [Grafana k6](https://k6.io/).

## Test Profiles & Scenarios

The test suite (`devsphere-load-test.js`) executes concurrent simulated user flows across three core workloads:
1. **Liveness & Health Probes**: Continuous healthcheck verification against `/actuator/health`.
2. **Operations Admin & Jobs Monitoring**: Querying background jobs metrics, queue status, and audit summary.
3. **Workspace Operations**: Querying user workspaces.

### Workload Stages
- **00:00 - 00:30**: Ramp-up from 0 to 20 Virtual Users (VUs)
- **00:30 - 01:30**: Steady-state load with 50 concurrent VUs
- **01:30 - 02:00**: Spike to 100 concurrent VUs
- **02:00 - 03:00**: Sustained peak load at 100 VUs
- **03:00 - 03:30**: Graceful cool-down to 0 VUs

### Service Level Objectives (SLOs) & Thresholds
- **Availability**: Request error rate < 1% (`http_req_failed < 0.01`).
- **p95 Latency**: 95th percentile response time < 500 ms (`p(95) < 500`).
- **p99 Latency**: 99th percentile response time < 1,500 ms (`p(99) < 1500`).

---

## How to Run

### Method 1: Using Docker (Recommended)
```bash
docker run --rm -i \
  -e BASE_URL="http://host.docker.internal:8080" \
  -e ADMIN_TOKEN="your_jwt_or_bearer_token" \
  grafana/k6 run - < infra/loadtest/k6/devsphere-load-test.js
```

### Method 2: Standalone k6 CLI
```bash
# Install k6 (macOS: brew install k6 | Linux: apt-get install k6)
BASE_URL="http://localhost:8080" ADMIN_TOKEN="your_jwt_or_bearer_token" k6 run infra/loadtest/k6/devsphere-load-test.js
```

### Output Interpretation
A passing run will produce green checkmarks across all threshold rules:
```
✓ actuator health returns 200
✓ actuator status is UP
✓ job stats returns 200
✓ audit summary returns 200

devsphere_failed_requests......: 0.00%   ✓ < 1%
http_req_duration..............: avg=42ms min=4ms med=28ms max=412ms p(90)=85ms p(95)=124ms
```
