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

# Acquire non-blocking lock
exec 200>"${LOCK_FILE}"
if ! flock -n 200; then
  printf '[%s] Another backup or restore job is already running; exiting.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  exit 0
fi

printf '[%s] Starting automated cron backup run...\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
"${SCRIPT_DIR}/backup-postgres.sh"

if [[ "${DO_VERIFY}" -eq 1 ]]; then
  printf '[%s] Running scheduled restore verification drill...\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  "${SCRIPT_DIR}/restore-verify.sh"
fi

printf '[%s] Automated cron backup run completed successfully.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
