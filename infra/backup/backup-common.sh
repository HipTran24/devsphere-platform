#!/usr/bin/env bash
# Shared helpers for the backup and restore scripts. Source this file; do not execute it.
# Secrets are read into variables only. They are never echoed, logged or placed on a command line.
set -euo pipefail

BACKUP_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${BACKUP_COMMON_DIR}/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env}"

log() { printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; exit 1; }

# env_default KEY [DEFAULT]: print $KEY from the environment, else from .env (without sourcing it), else DEFAULT.
env_default() {
  local key="$1" default="${2:-}" value="${!1:-}"
  if [[ -z "${value}" && -f "${ENV_FILE}" ]]; then
    value="$(grep -E "^${key}=" "${ENV_FILE}" | tail -n 1 | cut -d= -f2- || true)"
    value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
  fi
  printf '%s' "${value:-${default}}"
}

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

require_encryption_key() {
  [[ -n "${BACKUP_ENCRYPTION_KEY:-}" ]] || die "BACKUP_ENCRYPTION_KEY is not set"
  [[ "${#BACKUP_ENCRYPTION_KEY}" -ge 32 ]] || die "BACKUP_ENCRYPTION_KEY must be at least 32 characters"
  export BACKUP_ENCRYPTION_KEY
}

# Database connection settings. The runtime role cannot dump or create databases, so the owner
# (migration) account is used. Values come from the environment or .env.
load_db_settings() {
  DB_NAME="$(env_default DATABASE_NAME devsphere)"
  DB_USER="$(env_default MIGRATION_USER devsphere)"
  PGPASSWORD="$(env_default MIGRATION_PASSWORD)"
  [[ -n "${PGPASSWORD}" ]] || die "MIGRATION_PASSWORD is not set in the environment or .env"
  export PGPASSWORD
  local url port
  url="$(env_default DATABASE_URL)"
  port="$(printf '%s' "${url}" | sed -nE 's#^jdbc:postgresql://[^:/]+:([0-9]+)/.*#\1#p')"
  PGHOST="${PGHOST:-127.0.0.1}"
  PGPORT="${PGPORT:-${port:-$(env_default DATABASE_PORT 54329)}}"
  PG_CONTAINER="${PG_CONTAINER:-Devphere-DB}"
  if command -v pg_dump >/dev/null 2>&1 && command -v pg_restore >/dev/null 2>&1 && command -v psql >/dev/null 2>&1 \
     && [[ "${PG_FORCE_DOCKER:-0}" != "1" ]]; then
    PG_MODE="local"
  else
    require_cmd docker
    docker inspect "${PG_CONTAINER}" >/dev/null 2>&1 || die "No local PostgreSQL client tools and container ${PG_CONTAINER} not found"
    PG_MODE="docker"
  fi
}

# pg_run TOOL ARGS... : run a PostgreSQL client tool locally or inside the database container.
# In docker mode stdin is forwarded and PGPASSWORD is passed by name only.
pg_run() {
  local tool="$1"; shift
  if [[ "${PG_MODE}" == "local" ]]; then
    PGHOST="${PGHOST}" PGPORT="${PGPORT}" "${tool}" "$@"
  else
    docker exec -i -e PGPASSWORD "${PG_CONTAINER}" "${tool}" "$@"
  fi
}

ENC_ARGS=(-aes-256-cbc -pbkdf2 -iter 600000 -salt -pass env:BACKUP_ENCRYPTION_KEY)
