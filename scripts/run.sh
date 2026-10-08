#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "==> Running payment-service locally..."
exec "${PROJECT_ROOT}/mvnw" spring-boot:run -Dspring-boot.run.profiles=local

