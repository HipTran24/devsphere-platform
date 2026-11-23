#!/usr/bin/env bash
# Issue the first Let's Encrypt certificate for the DevSphere edge proxy.
#
#   infra/nginx/init-letsencrypt.sh [--staging] [--force]
#
# Flow: create a short-lived self-signed certificate so nginx can boot -> start
# nginx -> request the real certificate over the HTTP-01 webroot challenge ->
# reload nginx. Renewal: `docker compose -f compose.prod.yaml --profile tools run --rm certbot renew`.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE=(docker compose -f "${ROOT_DIR}/compose.prod.yaml" --env-file "${ROOT_DIR}/.env")
CERT_NAME="devsphere"
STAGING=0
FORCE=0

for arg in "$@"; do
  case "${arg}" in
    --staging) STAGING=1 ;;
    --force) FORCE=1 ;;
    -h|--help)
      sed -n '2,10p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      echo "Unknown argument: ${arg}" >&2
      exit 64
      ;;
  esac
done

# Read only the two non-secret values we need; never `source` the whole .env file.
read_env() {
  local key="$1" file="${ROOT_DIR}/.env" value=""
  if [[ -f "${file}" ]]; then
    value="$(grep -E "^${key}=" "${file}" | tail -n 1 | cut -d= -f2- || true)"
  fi
  printf '%s' "${value}"
}

DOMAIN="${NGINX_SERVER_NAME:-$(read_env NGINX_SERVER_NAME)}"
EMAIL="${LETSENCRYPT_EMAIL:-$(read_env LETSENCRYPT_EMAIL)}"

if [[ -z "${DOMAIN}" || "${DOMAIN}" == "devsphere.example.com" ]]; then
  echo "Set NGINX_SERVER_NAME to your public DNS name in .env first." >&2
  exit 1
fi
if [[ -z "${EMAIL}" || "${EMAIL}" == "ops@example.com" ]]; then
  echo "Set LETSENCRYPT_EMAIL to a monitored address in .env first." >&2
  exit 1
fi
if ! [[ "${DOMAIN}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; then
  echo "NGINX_SERVER_NAME contains unexpected characters." >&2
  exit 1
fi

cert_exists() {
  "${COMPOSE[@]}" --profile tools run --rm --no-deps --entrypoint sh certbot \
    -c "test -f /etc/letsencrypt/live/${CERT_NAME}/fullchain.pem && ! grep -q 'CN *= *localhost' /etc/letsencrypt/live/${CERT_NAME}/.dummy 2>/dev/null"
}

if [[ "${FORCE}" -eq 0 ]] && cert_exists >/dev/null 2>&1; then
  echo "A certificate for ${CERT_NAME} already exists. Use --force to replace it."
  exit 0
fi

echo "==> Creating temporary self-signed certificate for ${DOMAIN}"
"${COMPOSE[@]}" --profile tools run --rm --no-deps --entrypoint sh certbot -c "
  mkdir -p /etc/letsencrypt/live/${CERT_NAME} &&
  openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout /etc/letsencrypt/live/${CERT_NAME}/privkey.pem \
    -out /etc/letsencrypt/live/${CERT_NAME}/fullchain.pem \
    -subj '/CN=localhost' &&
  echo 'CN = localhost' > /etc/letsencrypt/live/${CERT_NAME}/.dummy"

echo "==> Starting nginx"
"${COMPOSE[@]}" up -d --no-deps nginx

echo "==> Removing temporary certificate"
"${COMPOSE[@]}" --profile tools run --rm --no-deps --entrypoint sh certbot -c "
  rm -rf /etc/letsencrypt/live/${CERT_NAME} \
         /etc/letsencrypt/archive/${CERT_NAME} \
         /etc/letsencrypt/renewal/${CERT_NAME}.conf"

STAGING_ARGS=()
if [[ "${STAGING}" -eq 1 ]]; then
  STAGING_ARGS=(--staging)
  echo "==> Using the Let's Encrypt staging environment"
fi

echo "==> Requesting Let's Encrypt certificate for ${DOMAIN}"
"${COMPOSE[@]}" --profile tools run --rm --no-deps certbot certonly \
  --webroot --webroot-path /var/www/certbot \
  --cert-name "${CERT_NAME}" \
  --domain "${DOMAIN}" \
  --email "${EMAIL}" \
  --rsa-key-size 4096 \
  --agree-tos --no-eff-email --non-interactive \
  ${STAGING_ARGS[@]+"${STAGING_ARGS[@]}"}

echo "==> Reloading nginx"
"${COMPOSE[@]}" exec nginx nginx -s reload
echo "Done. HTTPS is now served with a certificate for ${DOMAIN}."
