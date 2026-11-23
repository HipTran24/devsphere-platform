#!/usr/bin/env bash
# One-time host preparation for a DevSphere EC2 instance (Ubuntu 24.04 LTS).
#
#   sudo infra/aws/ec2-bootstrap.sh
#
# Installs Docker Engine + Compose v2 + AWS CLI v2, enables a host firewall and
# automatic security updates, creates the unprivileged `devsphere` deploy user
# and installs the systemd unit. It contains no secrets; credentials come from
# the EC2 instance profile (see iam-least-privilege-policy.json).
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root (sudo)." >&2
  exit 1
fi

APP_USER="${APP_USER:-devsphere}"
APP_DIR="${APP_DIR:-/opt/devsphere}"
SWAP_SIZE_GB="${SWAP_SIZE_GB:-2}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DEBIAN_FRONTEND=noninteractive

echo "==> Updating base packages"
apt-get update -y
apt-get upgrade -y
apt-get install -y --no-install-recommends \
  ca-certificates curl gnupg ufw fail2ban unattended-upgrades \
  unzip jq openssl cron git

echo "==> Installing Docker Engine and Compose plugin"
if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  # shellcheck disable=SC1091
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

echo "==> Installing AWS CLI v2"
if ! command -v aws >/dev/null 2>&1; then
  ARCH="$(uname -m)"
  TMP_DIR="$(mktemp -d)"
  curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${ARCH}.zip" -o "${TMP_DIR}/awscliv2.zip"
  unzip -q "${TMP_DIR}/awscliv2.zip" -d "${TMP_DIR}"
  "${TMP_DIR}/aws/install"
  rm -rf "${TMP_DIR}"
fi

echo "==> Creating deploy user and directories"
if ! id "${APP_USER}" >/dev/null 2>&1; then
  useradd --system --create-home --shell /bin/bash --groups docker "${APP_USER}"
fi
install -d -o "${APP_USER}" -g "${APP_USER}" -m 0750 "${APP_DIR}" "${APP_DIR}/backups"

echo "==> Configuring firewall (SSH, HTTP, HTTPS only)"
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "==> Hardening SSH"
install -d -m 0755 /etc/ssh/sshd_config.d
cat > /etc/ssh/sshd_config.d/99-devsphere.conf <<'SSHD'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
X11Forwarding no
MaxAuthTries 3
SSHD
systemctl reload ssh || systemctl reload sshd || true
systemctl enable --now fail2ban

echo "==> Enabling automatic security updates"
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "==> Kernel and Docker log tuning"
cat > /etc/sysctl.d/99-devsphere.conf <<'SYSCTL'
vm.swappiness = 10
vm.max_map_count = 262144
net.core.somaxconn = 1024
SYSCTL
sysctl --system >/dev/null
install -d -m 0755 /etc/docker
cat > /etc/docker/daemon.json <<'DOCKER'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "5" },
  "live-restore": true
}
DOCKER
systemctl restart docker

if ! swapon --show | grep -q .; then
  echo "==> Creating ${SWAP_SIZE_GB}G swap file"
  fallocate -l "${SWAP_SIZE_GB}G" /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "==> Installing systemd unit"
install -m 0644 "${SCRIPT_DIR}/devsphere.service" /etc/systemd/system/devsphere.service
systemctl daemon-reload
systemctl enable devsphere.service

cat <<NEXT

Bootstrap complete. Next steps (as ${APP_USER}):
  1. Clone the repository into ${APP_DIR} and create ${APP_DIR}/.env (chmod 600).
  2. Run infra/nginx/init-letsencrypt.sh to obtain the TLS certificate.
  3. Run infra/aws/deploy.sh to build and start the stack.
NEXT
