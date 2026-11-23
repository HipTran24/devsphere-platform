# DevSphere Database Backup & Disaster Recovery Runbook

This directory contains the production-grade backup, encryption, verification, and disaster recovery tooling for the DevSphere PostgreSQL database.

## Architecture & Security Model

```
+-------------------+      pg_dump -Fc       +--------------------------+
|  DevSphere DB     | ---------------------> | OpenSSL AES-256-CBC      |
|  (PostgreSQL 16)  |   (streamed, no disk)  | PBKDF2 600k iter + salt  |
+-------------------+                        +--------------------------+
                                                          |
                                                          v
                                             +--------------------------+
                                             | *.dump.enc               |
                                             | *.dump.enc.sha256        |
                                             | *.meta.json              |
                                             +--------------------------+
                                                    |            |
                               (Local Retention: N) v            v (Optional)
                                    /tmp/devsphere-backup     AWS S3 Bucket
```

### Key Security Invariants
- **Zero Plaintext on Disk**: `pg_dump` streams directly through `openssl enc` pipe so plaintext dumps are never written to the filesystem.
- **Strong Encryption**: AES-256-CBC with PBKDF2 key derivation using 600,000 hash iterations (`BACKUP_ENCRYPTION_KEY` min 32 chars).
- **Integrity Verification**: SHA-256 checksums are calculated and stored in parallel (`.sha256`) and checked before any restore attempt.
- **Non-Root Execution**: Runs under the dedicated `devsphere` system user or container runtime.
- **Owner Credentials Only**: Uses `MIGRATION_USER` (schema owner) rather than runtime user to ensure full DDL dump and restore capability.

---

## Scripts Overview

| Script | Purpose |
| :--- | :--- |
| `backup-common.sh` | Shared utilities: environment resolution, docker/local database detection, cryptographic helpers. |
| `backup-postgres.sh` | Full encrypted backup pipeline, local retention rotation, and optional S3 shipping. |
| `restore-verify.sh` | Automated recovery drill: decrypts dump, creates temp DB, restores, validates schema invariants (36 tables + Flyway v10), measures RTO, and drops temp DB. |
| `cron-backup.sh` | Wrapper with file locking (`flock`) for automated execution via systemd timer or crontab. |

---

## Recovery Objectives (RTO & RPO)

- **Recovery Point Objective (RPO)**: **24 hours** with daily automated backups (or **< 1 hour** when paired with WAL archiving).
- **Recovery Time Objective (RTO)**: **< 5 minutes** for database dump decryption and full schema/data restoration (measured automatically during verify drills).

---

## Operating Instructions

### 1. Manual Backup
```bash
# Local-only backup (stored in /tmp/devsphere-backup by default)
./infra/backup/backup-postgres.sh --local-only

# Backup with S3 offsite upload and retention of last 14 backups
BACKUP_S3_BUCKET=my-devsphere-backups ./infra/backup/backup-postgres.sh --keep 14
```

### 2. Automated Restore Verification Drill
```bash
# Verify the newest backup in /tmp/devsphere-backup
./infra/backup/restore-verify.sh

# Verify a specific backup file
./infra/backup/restore-verify.sh --file /tmp/devsphere-backup/devsphere_backup_20261124_000000Z.dump.enc
```

### 3. Production Disaster Recovery Runbook
In the event of complete data loss:
1. Locate the latest verified backup from AWS S3 or the offsite storage:
   ```bash
   aws s3 cp s3://my-devsphere-backups/backups/postgres/devsphere_backup_YYYYMMDD_HHMMSSZ.dump.enc ./
   aws s3 cp s3://my-devsphere-backups/backups/postgres/devsphere_backup_YYYYMMDD_HHMMSSZ.dump.enc.sha256 ./
   ```
2. Verify checksum:
   ```bash
   sha256sum -c devsphere_backup_YYYYMMDD_HHMMSSZ.dump.enc.sha256
   ```
3. Decrypt the dump:
   ```bash
   openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass env:BACKUP_ENCRYPTION_KEY \
     -in devsphere_backup_YYYYMMDD_HHMMSSZ.dump.enc -out restore.dump
   ```
4. Stop application containers to prevent concurrent writes:
   ```bash
   docker compose -f compose.prod.yaml stop backend
   ```
5. Restore into the production database:
   ```bash
   pg_restore --clean --if-exists -U devsphere -d devsphere restore.dump
   ```
6. Securely wipe decrypted dump:
   ```bash
   rm -P restore.dump 2>/dev/null || shred -u restore.dump 2>/dev/null || rm -f restore.dump
   ```
7. Restart backend service and verify `/actuator/health`:
   ```bash
   docker compose -f compose.prod.yaml start backend
   curl -f http://127.0.0.1:8080/actuator/health
   ```
