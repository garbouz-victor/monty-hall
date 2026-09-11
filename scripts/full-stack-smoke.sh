#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/docker/docker-compose.smoke.yml"
compose=(docker compose --project-name joyhub-smoke --file "$compose_file")

cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup
npm --prefix "$repo_root/frontend" run build
"${compose[@]}" up --detach --build

for attempt in $(seq 1 180); do
  if curl --fail --silent http://127.0.0.1:18080/api/v1/health >/dev/null; then
    break
  fi
  if [[ "$attempt" -eq 180 ]]; then
    "${compose[@]}" logs backend postgres
    exit 1
  fi
  sleep 1
done

cd "$repo_root/frontend"
VITE_BACKEND_PROXY_TARGET=http://127.0.0.1:18080 npx playwright test --config=playwright.full.config.ts
