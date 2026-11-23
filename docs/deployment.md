# DevSphere Production Deployment & Runbook

This guide covers the deployment, configuration, and maintenance of DevSphere on AWS EC2 with Docker Compose and Nginx.

## 1. AWS EC2 Provisioning

### Recommended Hardware Spec
- **Instance Type**: `t3.large` or `t3.xlarge` (2-4 vCPU, 8-16 GB RAM).
- **Storage**: 50 GB gp3 EBS Volume.
- **Operating System**: Ubuntu 22.04 LTS or 24.04 LTS.

### Bootstrap Execution
Run the provided bootstrap script on the clean instance:
```bash
sudo ./infra/aws/ec2-bootstrap.sh
```
This configures Docker, Docker Compose plugin, UFW firewall (allowing 22, 80, 443), and creates the unprivileged `devsphere` system user.

---

## 2. Environment Configuration

Copy `.env.example` to `.env` and set mandatory variables:
```bash
cp .env.example .env
chmod 600 .env
```
Ensure the following variables are defined:
- `AUTH_SESSION_SIGNING_KEY`: 32-byte Base64 key.
- `AWS_REGION` and `S3_BUCKET`: Configured AWS S3 bucket for media assets.
- `DATABASE_PASSWORD` and `MIGRATION_PASSWORD`: Secure database passwords.
- `REDIS_PASSWORD`: Secure Redis authentication secret.
- `BACKUP_ENCRYPTION_KEY`: 32+ character passphrase for OpenSSL AES-256 backup encryption.

---

## 3. Deploying the Compose Stack

Execute the deployment script:
```bash
./infra/aws/deploy.sh
```
The script validates required environment variables, runs `docker compose -f compose.prod.yaml config --quiet`, builds images, starts all 9 services, and executes smoke checks against `http://127.0.0.1:8080/actuator/health`.

### Services Managed by Compose:
1. `nginx`: Reverse proxy with TLS termination (ports 80, 443).
2. `backend`: Spring Boot 4.1.2 service (port 8080, management port 8081).
3. `frontend`: Next.js 16.4 web client (port 3000).
4. `postgres`: PostgreSQL 18.7 database.
5. `redis`: Redis 7 in-memory cache and session store.
6. `prometheus`: Metrics collection engine (port 9090 on loopback).
7. `grafana`: Operational dashboards (port 3000 on loopback).
8. `loki`: Log ingestion engine (port 3100 on loopback).
9. `alloy`: Container log forwarding agent.

---

## 4. SSL/TLS Certificate Setup

Obtain Let's Encrypt certificates using the automated initialization script:
```bash
./infra/nginx/init-letsencrypt.sh
```

---

## 5. Automated Database Backup & Disaster Recovery

### Crontab Schedule
```bash
# Add to /etc/crontab:
0 2 * * * devsphere /opt/devsphere/infra/backup/cron-backup.sh >> /var/log/devsphere/backup.log 2>&1
0 4 * * 0 devsphere /opt/devsphere/infra/backup/cron-backup.sh --verify >> /var/log/devsphere/backup-drill.log 2>&1
```

### Manual Disaster Recovery Drill
```bash
./infra/backup/restore-verify.sh
```
Logs output showing database integrity check, table counts (>= 36), Flyway version 10 verification, and Recovery Time Objective (RTO).
