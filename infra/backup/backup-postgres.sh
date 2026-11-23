#!/usr/bin/env bash
# Production PostgreSQL automated backup script.
# Dumps PostgreSQL, encrypts with AES-256-CBC (PBKDF2 600,000 iterations),
# computes SHA-256 checksum, optionally ships to AWS S3, and prunes old backups.
#
# Usage:
#   ./backup-postgres.sh [--local-only] [--keep <count>] [--dir <path>]
#
# Environment variables:
#   BACKUP_DIR              Target directory for backups (default: /tmp/devsphere-backup)
#   BACKUP_KEEP             Number of local backups to retain (default: 7)
#   BACKUP_ENCRYPTION_KEY   Min 32-char passphrase for OpenSSL AES-256 encryption
#   BACKUP_S3_BUCKET        Optional AWS S3 bucket for offsite backups
#   BACKUP_S3_PREFIX        Optional S3 key prefix (default: backups/postgres)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/backup-common.sh"

LOCAL_ONLY=0
KEEP_COUNT="${BACKUP_KEEP:-7}"
TARGET_DIR="${BACKUP_DIR:-/tmp/devsphere-backup}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --local-only)
      LOCAL_ONLY=1
      shift
      ;;
    --keep)
      KEEP_COUNT="$2"
      shift 2
      ;;
    --dir)
      TARGET_DIR="$2"
      shift 2
      ;;
    -h|--help)
      cat <<EOF
Usage: $0 [options]
  --local-only      Save backup locally only; skip AWS S3 upload
  --keep <count>    Number of local backups to retain (default: 7)
  --dir <path>      Output backup directory (default: /tmp/devsphere-backup)
  -h, --help        Show this help message
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

mkdir -p "${TARGET_DIR}"
chmod 700 "${TARGET_DIR}"

TIMESTAMP="$(date -u +%Y%m%d_%H%M%SZ)"
BASE_NAME="devsphere_backup_${TIMESTAMP}"
ENC_FILE="${TARGET_DIR}/${BASE_NAME}.dump.enc"
SHA_FILE="${ENC_FILE}.sha256"
METADATA_FILE="${TARGET_DIR}/${BASE_NAME}.meta.json"

log "Starting DevSphere database backup: db=${DB_NAME}, mode=${PG_MODE}"

# Stream pg_dump directly into OpenSSL encryption to avoid unencrypted intermediate disk writes
if [[ "${PG_MODE}" == "local" ]]; then
  PGPASSWORD="${PGPASSWORD}" pg_dump -h "${PGHOST}" -p "${PGPORT}" -U "${DB_USER}" -d "${DB_NAME}" -Fc \
    | openssl enc "${ENC_ARGS[@]}" -out "${ENC_FILE}"
else
  docker exec -i -e PGPASSWORD "${PG_CONTAINER}" pg_dump -U "${DB_USER}" -d "${DB_NAME}" -Fc \
    | openssl enc "${ENC_ARGS[@]}" -out "${ENC_FILE}"
fi

chmod 600 "${ENC_FILE}"
ENC_SIZE="$(wc -c < "${ENC_FILE}" | tr -d ' ')"
[[ "${ENC_SIZE}" -gt 1024 ]] || die "Backup file is suspiciously small (${ENC_SIZE} bytes)"

# Generate SHA-256 checksum
CHECKSUM="$(sha256_of "${ENC_FILE}")"
printf '%s  %s\n' "${CHECKSUM}" "$(basename "${ENC_FILE}")" > "${SHA_FILE}"
chmod 600 "${SHA_FILE}"

# Write metadata record
cat <<EOF > "${METADATA_FILE}"
{
  "timestamp": "${TIMESTAMP}",
  "database": "${DB_NAME}",
  "file": "$(basename "${ENC_FILE}")",
  "bytes": ${ENC_SIZE},
  "sha256": "${CHECKSUM}",
  "encryption": "aes-256-cbc-pbkdf2-600k",
  "pg_mode": "${PG_MODE}"
}
EOF
chmod 600 "${METADATA_FILE}"

log "Backup completed successfully:"
log "  Encrypted file: ${ENC_FILE} (${ENC_SIZE} bytes)"
log "  SHA-256:        ${CHECKSUM}"
log "  Metadata:       ${METADATA_FILE}"

# Optional AWS S3 upload
S3_BUCKET="$(env_default BACKUP_S3_BUCKET)"
S3_PREFIX="$(env_default BACKUP_S3_PREFIX backups/postgres)"
if [[ "${LOCAL_ONLY}" -eq 0 && -n "${S3_BUCKET}" ]]; then
  if command -v aws >/dev/null 2>&1; then
    log "Uploading encrypted backup to s3://${S3_BUCKET}/${S3_PREFIX}/"
    aws s3 cp "${ENC_FILE}" "s3://${S3_BUCKET}/${S3_PREFIX}/$(basename "${ENC_FILE}")"
    aws s3 cp "${SHA_FILE}" "s3://${S3_BUCKET}/${S3_PREFIX}/$(basename "${SHA_FILE}")"
    aws s3 cp "${METADATA_FILE}" "s3://${S3_BUCKET}/${S3_PREFIX}/$(basename "${METADATA_FILE}")"
    log "AWS S3 upload completed."
  else
    log "WARNING: BACKUP_S3_BUCKET set but aws CLI is not installed; skipped S3 upload."
  fi
fi

# Local retention pruning
if [[ "${KEEP_COUNT}" -gt 0 ]]; then
  log "Pruning old local backups, keeping newest ${KEEP_COUNT}..."
  # Count existing .dump.enc files
  BACKUP_COUNT="$(find "${TARGET_DIR}" -maxdepth 1 -name "devsphere_backup_*.dump.enc" | wc -l | tr -d ' ')"
  if [[ "${BACKUP_COUNT}" -gt "${KEEP_COUNT}" ]]; then
    # List files sorted oldest first, delete beyond KEEP_COUNT
    find "${TARGET_DIR}" -maxdepth 1 -name "devsphere_backup_*.dump.enc" | sort | head -n "$((BACKUP_COUNT - KEEP_COUNT))" | while read -r old_file; do
      old_base="${old_file%.dump.enc}"
      log "Removing pruned backup: ${old_file}"
      rm -f "${old_file}" "${old_base}.dump.enc.sha256" "${old_base}.meta.json"
    done
  fi
fi

log "PostgreSQL backup process finished successfully."
