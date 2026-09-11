#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_root="$repo_root/backend/target/nginx-test"
container_name="joyhub-nginx-test-$$"
nginx_image="nginx:1.27-alpine"

cleanup() {
  docker rm --force "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$fixture_root/conf" "$fixture_root/certs/live/joy-hub.ru" "$fixture_root/www"
cp -R "$repo_root/frontend/dist/." "$fixture_root/www/"

cat > "$fixture_root/conf/nginx.conf" <<'EOF'
events {}
http {
    include /etc/nginx/mime.types;
    include /etc/nginx/sites-enabled/*.conf;
}
EOF

cat > "$fixture_root/certs/options-ssl-nginx.conf" <<'EOF'
ssl_protocols TLSv1.2 TLSv1.3;
ssl_session_cache shared:SSL:1m;
EOF

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj "/CN=joy-hub.ru" \
  -keyout "$fixture_root/certs/live/joy-hub.ru/privkey.pem" \
  -out "$fixture_root/certs/live/joy-hub.ru/fullchain.pem" >/dev/null 2>&1
openssl dhparam -dsaparam -out "$fixture_root/certs/ssl-dhparams.pem" 2048 >/dev/null 2>&1

common_mounts=(
  --volume "$fixture_root/conf/nginx.conf:/etc/nginx/nginx.conf:ro"
  --volume "$fixture_root/www:/var/www/joy-hub:ro"
)
tls_mounts=(
  --volume "$repo_root/deploy/nginx/snippets:/etc/nginx/snippets:ro"
  --volume "$fixture_root/certs:/etc/letsencrypt:ro"
)

docker run --rm "${common_mounts[@]}" \
  --volume "$repo_root/deploy/nginx/joy-hub-http-bootstrap.conf:/etc/nginx/sites-enabled/joy-hub.conf:ro" \
  "$nginx_image" nginx -t

docker run --rm "${common_mounts[@]}" "${tls_mounts[@]}" \
  --volume "$repo_root/deploy/nginx/joy-hub.conf:/etc/nginx/sites-enabled/joy-hub.conf:ro" \
  "$nginx_image" nginx -t

docker run --detach --name "$container_name" --publish 127.0.0.1:18443:443 \
  "${common_mounts[@]}" "${tls_mounts[@]}" \
  --volume "$repo_root/deploy/nginx/joy-hub.conf:/etc/nginx/sites-enabled/joy-hub.conf:ro" \
  "$nginx_image" >/dev/null

assert_security_headers() {
  local path="$1"
  local expected_status="$2"
  local headers_file="$fixture_root/headers.txt"
  local status

  status="$(curl --silent --show-error --insecure --max-time 12 \
    --resolve joy-hub.ru:18443:127.0.0.1 \
    --dump-header "$headers_file" --output /dev/null --write-out '%{http_code}' \
    "https://joy-hub.ru:18443$path")"
  [[ "$status" == "$expected_status" ]]
  grep -qi '^strict-transport-security:' "$headers_file"
  grep -qi '^x-content-type-options:' "$headers_file"
  grep -qi '^referrer-policy:' "$headers_file"
  grep -qi '^permissions-policy:' "$headers_file"
  grep -qi '^content-security-policy:' "$headers_file"
}

for attempt in $(seq 1 20); do
  if curl --silent --insecure --max-time 2 --resolve joy-hub.ru:18443:127.0.0.1 \
    https://joy-hub.ru:18443/ >/dev/null; then
    break
  fi
  sleep 0.25
done

asset_name="$(find "$fixture_root/www/assets" -maxdepth 1 -type f -printf '%f\n' | head -n 1)"
assert_security_headers "/" "200"
assert_security_headers "/index.html" "200"
assert_security_headers "/assets/$asset_name" "200"
assert_security_headers "/api/v1/health" "503"

if grep -q "unsafe-inline" "$repo_root/deploy/nginx/snippets/joy-hub-security-headers.conf"; then
  echo "CSP unexpectedly contains unsafe-inline" >&2
  exit 1
fi

echo "Nginx bootstrap/TLS configs and security headers: OK"
