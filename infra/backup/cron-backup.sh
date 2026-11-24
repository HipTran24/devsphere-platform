#!/usr/bin/env bash
# Production cron wrapper for automated DevSphere backups and periodic restore drills.
# Uses lock file to prevent overlapping execution.
#
# Recommended crontab:
#   # Daily backup at 02:00 UTC
#   0 2 * * * /opt/devsphere/infra/backup/cron-backup.sh >> /var/log/devsphere/backup.log 2>&1
#   # Weekly restore drill test on Sunday at 04:00 UTC
#   0 4 * * 0 /opt/devsphere/infra/backup/cron-backup.sh --verify >> /var/log/devsphere/backup-drill.log 2>&1

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCK_FILE="/tmp/devsphere-backup.lock"
DO_VERIFY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --verify)
      DO_VERIFY=1
      shift
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      exit 1
      ;;
  esac
done

# Acquire non-blocking lock (uses flock when available, atomic dir lock fallback otherwise)
if command -v flock >/dev/null 2>&1; then
  exec 200>"${LOCK_FILE}"
  if ! flock -n 200; then
    printf '[%s] Another backup or restore job is already running; exiting.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    exit 0
  fi
else
  LOCK_DIR="${LOCK_FILE}.lock.d"
  if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
    if [[ -d "${LOCK_DIR}" ]]; then
      lock_age=$(( $(date +%s) - $(stat -c %Y "${LOCK_DIR}" 2>/dev/null || stat -f %m "${LOCK_DIR}" 2>/dev/null || echo 0) ))
      if [[ "${lock_age}" -gt 7200 ]]; then
        printf '[%s] Removing stale backup lock dir (age: %ds)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${lock_age}"
        rm -rf "${LOCK_DIR}"
        mkdir "${LOCK_DIR}" 2>/dev/null || exit 0
      else
        printf '[%s] Another backup or restore job is already running; exiting.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        exit 0
      fi
    else
      exit 0
    fi
  fi
  trap 'rm -rf "${LOCK_DIR}" 2>/dev/null || true' EXIT
fi

printf '[%s] Starting automated cron backup run...\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
"${SCRIPT_DIR}/backup-postgres.sh"

if [[ "${DO_VERIFY}" -eq 1 ]]; then
  printf '[%s] Running scheduled restore verification drill...\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  "${SCRIPT_DIR}/restore-verify.sh"
fi

printf '[%s] Automated cron backup run completed successfully.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
