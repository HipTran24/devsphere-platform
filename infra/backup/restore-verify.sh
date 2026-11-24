#!/usr/bin/env bash
# Production PostgreSQL automated restore and integrity verification script.
# Decrypts an AES-256 encrypted dump, verifies SHA-256 integrity, creates an isolated
# temporary database, performs full restore, verifies 36 schema tables and Flyway status,
# measures Recovery Time Objective (RTO), and cleans up all temporary resources.
#
# Usage:
#   ./restore-verify.sh [--file <path>] [--keep-temp-db]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/backup-common.sh"

BACKUP_FILE=""
KEEP_TEMP_DB=0
TARGET_DIR="${BACKUP_DIR:-/tmp/devsphere-backup}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      BACKUP_FILE="$2"
      shift 2
      ;;
    --keep-temp-db)
      KEEP_TEMP_DB=1
      shift
      ;;
    -h|--help)
      cat <<EOF
Usage: $0 [options]
  --file <path>       Path to .dump.enc backup file (default: latest in BACKUP_DIR)
  --keep-temp-db      Do not drop the temporary verification database on exit
  -h, --help          Show this help message
EOF
      exit 0
      ;;
    *)
      die "Unknown option: $1"
      ;;
  esac
done

require_encryption_key
load_db_settings

if [[ -z "${BACKUP_FILE}" ]]; then
  BACKUP_FILE="$(find "${TARGET_DIR}" -maxdepth 1 -name "devsphere_backup_*.dump.enc" | sort -r | head -n 1 || true)"
  [[ -n "${BACKUP_FILE}" ]] || die "No backup file found in ${TARGET_DIR}"
fi

[[ -f "${BACKUP_FILE}" ]] || die "Backup file does not exist: ${BACKUP_FILE}"

# Verify SHA-256 checksum
SHA_FILE="${BACKUP_FILE}.sha256"
if [[ -f "${SHA_FILE}" ]]; then
  log "Verifying SHA-256 checksum against ${SHA_FILE}..."
  EXPECTED_HASH="$(awk '{print $1}' "${SHA_FILE}")"
  ACTUAL_HASH="$(sha256_of "${BACKUP_FILE}")"
  if [[ "${EXPECTED_HASH}" != "${ACTUAL_HASH}" ]]; then
    die "SHA-256 checksum mismatch! Expected ${EXPECTED_HASH}, got ${ACTUAL_HASH}"
  fi
  log "Checksum OK: ${ACTUAL_HASH}"
else
  log "WARNING: Checksum file ${SHA_FILE} not found; proceeding with decryption."
fi

# Set up isolated scratch directory and temporary database
TEMP_RESTORE_DIR="$(mktemp -d "/tmp/devsphere-restore-XXXXXX")"
chmod 700 "${TEMP_RESTORE_DIR}"
DECRYPTED_DUMP="${TEMP_RESTORE_DIR}/dump.custom"

VERIFY_TS="$(date +%s)"
VERIFY_DB="devsphere_verify_${VERIFY_TS}_${RANDOM}"

cleanup() {
  log "Cleaning up restore verification resources..."
  rm -rf "${TEMP_RESTORE_DIR}"
  if [[ "${KEEP_TEMP_DB}" -eq 0 ]]; then
    log "Dropping temporary database ${VERIFY_DB}..."
    if [[ "${PG_MODE}" == "local" ]]; then
      PGPASSWORD="${PGPASSWORD}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${DB_USER}" -d postgres -c "DROP DATABASE IF EXISTS \"${VERIFY_DB}\" WITH (FORCE);" >/dev/null 2>&1 || true
    else
      docker exec -i -e PGPASSWORD "${PG_CONTAINER}" psql -U "${DB_USER}" -d postgres -c "DROP DATABASE IF EXISTS \"${VERIFY_DB}\" WITH (FORCE);" >/dev/null 2>&1 || true
    fi
  else
    log "Preserved temporary database: ${VERIFY_DB}"
  fi
}
trap cleanup EXIT

log "Starting restore drill test: source=${BACKUP_FILE}, target_db=${VERIFY_DB}"
START_SECONDS="$(date +%s)"

# Decrypt
log "Decrypting backup file..."
openssl enc -d "${ENC_ARGS[@]}" -in "${BACKUP_FILE}" -out "${DECRYPTED_DUMP}"
DUMP_SIZE="$(wc -c < "${DECRYPTED_DUMP}" | tr -d ' ')"
log "Decrypted dump size: ${DUMP_SIZE} bytes"
[[ "${DUMP_SIZE}" -gt 1024 ]] || die "Decrypted dump file is invalid or empty"

# Create temporary database
log "Creating temporary database ${VERIFY_DB}..."
if [[ "${PG_MODE}" == "local" ]]; then
  PGPASSWORD="${PGPASSWORD}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${DB_USER}" -d postgres -c "CREATE DATABASE \"${VERIFY_DB}\" OWNER \"${DB_USER}\";"
else
  docker exec -i -e PGPASSWORD "${PG_CONTAINER}" psql -U "${DB_USER}" -d postgres -c "CREATE DATABASE \"${VERIFY_DB}\" OWNER \"${DB_USER}\";"
fi

# Restore into temporary database
log "Restoring database dump into ${VERIFY_DB}..."
if [[ "${PG_MODE}" == "local" ]]; then
  PGPASSWORD="${PGPASSWORD}" pg_restore -h "${PGHOST}" -p "${PGPORT}" -U "${DB_USER}" -d "${VERIFY_DB}" --no-owner --no-privileges "${DECRYPTED_DUMP}" || true
else
  docker exec -i -e PGPASSWORD "${PG_CONTAINER}" pg_restore -U "${DB_USER}" -d "${VERIFY_DB}" --no-owner --no-privileges < "${DECRYPTED_DUMP}" || true
fi

# Integrity Checks
log "Running schema and data integrity checks on ${VERIFY_DB}..."

TABLE_COUNT_QUERY="SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
if [[ "${PG_MODE}" == "local" ]]; then
  TABLE_COUNT="$(PGPASSWORD="${PGPASSWORD}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${DB_USER}" -d "${VERIFY_DB}" -t -A -c "${TABLE_COUNT_QUERY}")"
else
  TABLE_COUNT="$(docker exec -i -e PGPASSWORD "${PG_CONTAINER}" psql -U "${DB_USER}" -d "${VERIFY_DB}" -t -A -c "${TABLE_COUNT_QUERY}")"
fi
log "Restored base tables in public schema: ${TABLE_COUNT}"

FLYWAY_QUERY="SELECT version || ':' || success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
if [[ "${PG_MODE}" == "local" ]]; then
  FLYWAY_STATUS="$(PGPASSWORD="${PGPASSWORD}" psql -h "${PGHOST}" -p "${PGPORT}" -U "${DB_USER}" -d "${VERIFY_DB}" -t -A -c "${FLYWAY_QUERY}")"
else
  FLYWAY_STATUS="$(docker exec -i -e PGPASSWORD "${PG_CONTAINER}" psql -U "${DB_USER}" -d "${VERIFY_DB}" -t -A -c "${FLYWAY_QUERY}")"
fi
log "Latest Flyway migration status: ${FLYWAY_STATUS}"

END_SECONDS="$(date +%s)"
RTO_SECONDS="$((END_SECONDS - START_SECONDS))"

# Validations
if [[ "${TABLE_COUNT}" -lt 36 ]]; then
  die "Restore verification FAILED: Expected at least 36 tables, found ${TABLE_COUNT}"
fi

if [[ "${FLYWAY_STATUS}" != *"10:t"* && "${FLYWAY_STATUS}" != *"10:true"* ]]; then
  die "Restore verification FAILED: Flyway schema history is not at version 10 success (found ${FLYWAY_STATUS})"
fi

log "=========================================================="
log "RESTORE VERIFICATION DRILL: PASS"
log "  Database:             ${VERIFY_DB}"
log "  Base tables verified: ${TABLE_COUNT} (invariant: >= 36)"
log "  Flyway version:       ${FLYWAY_STATUS}"
log "  Recovery Time (RTO):  ${RTO_SECONDS} seconds"
log "=========================================================="
