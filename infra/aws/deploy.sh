#!/usr/bin/env bash
# Build and roll out the DevSphere production stack on the EC2 host.
#
#   infra/aws/deploy.sh [--pull] [--from-secrets-manager] [--skip-build]
#
#   --pull                   git pull --ff-only before building
#   --from-secrets-manager   render .env from the secret named by SECRETS_MANAGER_SECRET_ID
#                            (JSON object of KEY: value pairs; written with mode 600)
#   --skip-build             reuse existing images
#
# Secrets are never printed. Only variable NAMES are reported when validation fails.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"
COMPOSE=(docker compose -f "${ROOT_DIR}/compose.prod.yaml" --env-file "${ENV_FILE}")
PULL=0
FROM_SM=0
BUILD=1
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-300}"

for arg in "$@"; do
  case "${arg}" in
    --pull) PULL=1 ;;
    --from-secrets-manager) FROM_SM=1 ;;
    --skip-build) BUILD=0 ;;
    -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 64 ;;
  esac
done

log() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

cd "${ROOT_DIR}"

if [[ "${PULL}" -eq 1 ]]; then
  log "Fetching latest code (fast-forward only)"
  git pull --ff-only
fi

if [[ "${FROM_SM}" -eq 1 ]]; then
  SECRET_ID="${SECRETS_MANAGER_SECRET_ID:-}"
  [[ -n "${SECRET_ID}" ]] || die "SECRETS_MANAGER_SECRET_ID must be exported for --from-secrets-manager"
  command -v aws >/dev/null || die "aws CLI not installed"
  command -v jq >/dev/null || die "jq not installed"
  log "Rendering .env from AWS Secrets Manager (${SECRET_ID})"
  umask 077
  TMP_ENV="$(mktemp "${ROOT_DIR}/.env.XXXXXX")"
  trap 'rm -f "${TMP_ENV}"' EXIT
  aws secretsmanager get-secret-value --secret-id "${SECRET_ID}" --query SecretString --output text \
    | jq -r 'to_entries[] | "\(.key)=\(.value)"' > "${TMP_ENV}"
  mv "${TMP_ENV}" "${ENV_FILE}"
  trap - EXIT
fi

[[ -f "${ENV_FILE}" ]] || die ".env not found at ${ENV_FILE}"
PERMS="$(stat -c '%a' "${ENV_FILE}" 2>/dev/null || stat -f '%Lp' "${ENV_FILE}")"
if [[ "${PERMS}" != "600" && "${PERMS}" != "400" ]]; then
  die ".env must be chmod 600 (current: ${PERMS})"
fi

log "Checking required variables"
REQUIRED=(MIGRATION_PASSWORD DATABASE_PASSWORD REDIS_PASSWORD AUTH_SESSION_SIGNING_KEY
          AWS_REGION S3_BUCKET NGINX_SERVER_NAME GRAFANA_ADMIN_PASSWORD BACKUP_ENCRYPTION_KEY)
MISSING=()
for key in "${REQUIRED[@]}"; do
  value="$(grep -E "^${key}=" "${ENV_FILE}" | tail -n 1 | cut -d= -f2- || true)"
  if [[ -z "${value}" || "${value}" == replace-with-* ]]; then
    MISSING+=("${key}")
  fi
done
if [[ "${#MISSING[@]}" -gt 0 ]]; then
  die "Unset or placeholder variables in .env: ${MISSING[*]}"
fi

log "Validating compose file"
"${COMPOSE[@]}" config --quiet

if [[ "${BUILD}" -eq 1 ]]; then
  log "Building images"
  "${COMPOSE[@]}" build --pull
fi

log "Starting data stores"
"${COMPOSE[@]}" up -d postgres redis

log "Re-applying least-privilege runtime role"
"${COMPOSE[@]}" --profile tools run --rm database-setup

log "Rolling out application and edge services"
"${COMPOSE[@]}" up -d --remove-orphans

log "Waiting for backend readiness (timeout ${HEALTH_TIMEOUT_SECONDS}s)"
deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
until [[ "$(docker inspect --format '{{.State.Health.Status}}' devsphere-backend 2>/dev/null || echo starting)" == "healthy" ]]; do
  if (( SECONDS >= deadline )); then
    "${COMPOSE[@]}" logs --tail 80 backend >&2 || true
    die "backend did not become healthy in time"
  fi
  sleep 5
done

log "Smoke testing the edge proxy"
curl -fsS --max-time 10 http://127.0.0.1/healthz >/dev/null || die "nginx /healthz failed"
status="$(curl -ks -o /dev/null -w '%{http_code}' --max-time 10 https://127.0.0.1/actuator/health || true)"
[[ "${status}" == "404" ]] || die "actuator must not be reachable through nginx (got HTTP ${status})"

log "Deployment finished"
"${COMPOSE[@]}" ps
