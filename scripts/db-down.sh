#!/usr/bin/env bash
set -euo pipefail

echo "==> Stopping local PostgreSQL container..."
docker compose down

