#!/usr/bin/env bash
# restart.sh — Clean restart of the MCP Gateway stack.
#
# Removes orphaned inner containers, tears down the stack with volumes,
# and brings everything back up fresh. Safe for demos.
#
# Usage:
#   ./scripts/restart.sh          # restart + seed
#   ./scripts/restart.sh --no-seed  # restart only, no seeding

set -euo pipefail
cd "$(dirname "$0")/../deployments"

NO_SEED=false
for arg in "$@"; do
  case "$arg" in
    --no-seed) NO_SEED=true ;;
  esac
done

echo "=== Stopping orphaned and dynamically-wired MCP containers ==="
# Remove inner containers spawned by supergateway (labeled)
orphans=$(docker ps -aq --filter "label=gateway.parent" 2>/dev/null || true)
if [ -n "$orphans" ]; then
  echo "$orphans" | xargs docker rm -f
  echo "  Removed $(echo "$orphans" | wc -l | tr -d ' ') labeled orphan(s)"
fi
# Remove any containers on gateway_backend-net that aren't core compose services.
# Dynamically wired services (via Dashboard Discover) attach to this network
# but aren't in docker-compose.yml, so 'docker compose down' can't remove them.
if docker network inspect gateway_backend-net >/dev/null 2>&1; then
  core_services="gateway-aigw-run-1 gateway-duckduckgo-mcp-1 gateway-github-mcp-1 gateway-mock-calendar-mcp-1 gateway-dashboard-1 gateway-envoy-gateway-1"
  for cid in $(docker network inspect gateway_backend-net --format '{{range .Containers}}{{.Name}} {{end}}' 2>/dev/null); do
    if ! echo " $core_services " | grep -q " $cid "; then
      echo "  Removing dynamically-wired container: $cid"
      docker rm -f "$cid" 2>/dev/null || true
    fi
  done
fi

echo ""
echo "=== Tearing down stack (volumes included) ==="
docker compose down -v --remove-orphans 2>&1

echo ""
echo "=== Starting fresh stack ==="
docker compose up -d 2>&1

echo ""
echo "=== Waiting for services to be healthy ==="
# Wait for the slowest services: keycloak and npl-engine
for svc in keycloak npl-engine; do
  printf "  Waiting for %s..." "$svc"
  for i in $(seq 1 60); do
    status=$(docker compose ps --format "{{.Status}}" "$svc" 2>/dev/null || echo "")
    if echo "$status" | grep -q "(healthy)"; then
      echo " ready"
      break
    fi
    if [ "$i" -eq 60 ]; then
      echo " TIMEOUT (may still be starting)"
    fi
    sleep 2
  done
done

echo ""
echo "=== Service status ==="
docker compose ps --format "table {{.Name}}\t{{.Status}}"

if [ "$NO_SEED" = false ]; then
  echo ""
  echo "=== Running seed script ==="
  cd ../scripts
  ./seed.sh
fi

echo ""
echo "=== Done! Dashboard at http://localhost:8888 ==="
