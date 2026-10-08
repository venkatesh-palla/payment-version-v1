#!/usr/bin/env bash
set -euo pipefail

SUB="${1:-customer-1}"
SCOPES="${2:-payments:create payments:read}"
PORT="${PORT:-8080}"

echo "==> Minting dev JWT for subject '${SUB}' with scopes '${SCOPES}'..."
curl -s -X POST "http://localhost:${PORT}/dev/token?sub=${SUB}&scopes=${SCOPES}" | jq . || curl -s -X POST "http://localhost:${PORT}/dev/token?sub=${SUB}&scopes=${SCOPES}"
echo ""

