#!/usr/bin/env bash
set -euo pipefail

echo "==> Starting local PostgreSQL container..."
docker compose up -d postgres

echo "==> Waiting for PostgreSQL to be healthy..."
until docker compose exec -T postgres pg_isready -U "${DATABASE_USERNAME:-payment_user}" -d "${DATABASE_NAME:-payment_db}" >/dev/null 2>&1; do
    sleep 1
done
echo "==> PostgreSQL is ready!"

