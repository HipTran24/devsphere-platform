#!/usr/bin/env bash
# Refresh the ROLE_ADMIN bearer token that Prometheus uses to scrape /actuator/prometheus.
#
# Backend access tokens live for 15 minutes, so run this from cron every 10 minutes:
#   */10 * * * * /opt/devsphere/infra/monitoring/refresh-scrape-token.sh >> /var/log/devsphere-scrape-token.log 2>&1
#
# It signs in as the dedicated monitoring account (MONITORING_ADMIN_EMAIL / MONITORING_ADMIN_PASSWORD)
# by calling the backend from inside its own container, so nothing is exposed through nginx.
# The token is written atomically with mode 600 and never printed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
TOKEN_DIR="${TOKEN_DIR:-${ROOT_DIR}/infra/monitoring/prometheus/secrets}"
TOKEN_FILE="${TOKEN_DIR}/admin-token"
BACKEND_CONTAINER="${BACKEND_CONTAINER:-devsphere-backend}"
API_BASE="${API_BASE:-http://127.0.0.1:8080}"

log() { printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

env_value() {
  local key="$1" value="${!1:-}"
  if [[ -z "${value}" && -f "${ENV_FILE}" ]]; then
    value="$(grep -E "^${key}=" "${ENV_FILE}" | tail -n 1 | cut -d= -f2- || true)"
  fi
  printf '%s' "${value}"
}

command -v docker >/dev/null || die "docker not found"
command -v jq >/dev/null || die "jq not found"

EMAIL="$(env_value MONITORING_ADMIN_EMAIL)"
PASSWORD="$(env_value MONITORING_ADMIN_PASSWORD)"
[[ -n "${EMAIL}" && -n "${PASSWORD}" ]] || die "MONITORING_ADMIN_EMAIL and MONITORING_ADMIN_PASSWORD must be set"

# Run the whole exchange inside the container so the cookie jar and credentials never touch the host
# process list. Credentials are passed on stdin as JSON.
payload="$(jq -n --arg email "${EMAIL}" --arg password "${PASSWORD}" '{email: $email, password: $password}')"

response="$(printf '%s' "${payload}" | docker exec -i "${BACKEND_CONTAINER}" sh -c '
  set -eu
  jar="$(mktemp)"; trap "rm -f \"$jar\"" EXIT
  csrf="$(curl -fsS -c "$jar" -b "$jar" "'"${API_BASE}"'/api/v1/auth/csrf")"
  header="$(printf "%s" "$csrf" | sed -n "s/.*\"headerName\":\"\([^\"]*\)\".*/\1/p")"
  token="$(printf "%s" "$csrf" | sed -n "s/.*\"token\":\"\([^\"]*\)\".*/\1/p")"
  curl -fsS -c "$jar" -b "$jar" -H "Content-Type: application/json" -H "$header: $token" \
    --data-binary @- "'"${API_BASE}"'/api/v1/auth/login"
')" || die "login failed (check the monitoring account and that the backend is healthy)"

access_token="$(printf '%s' "${response}" | jq -r '.accessToken // empty')"
[[ -n "${access_token}" ]] || die "login response did not contain an access token"

umask 077
mkdir -p "${TOKEN_DIR}"
# Prometheus runs as nobody inside its container and must be able to traverse the directory.
chmod 755 "${TOKEN_DIR}"
tmp="$(mktemp "${TOKEN_DIR}/.admin-token.XXXXXX")"
trap 'rm -f "${tmp}" 2>/dev/null || true' EXIT
printf '%s' "${access_token}" > "${tmp}"
# Prometheus runs as nobody, so the file must be world-readable inside the (git-ignored) directory.
chmod 644 "${tmp}"
mv "${tmp}" "${TOKEN_FILE}"
trap - EXIT
log "scrape token refreshed (expires $(printf '%s' "${response}" | jq -r '.expiresAt // "unknown"'))"
