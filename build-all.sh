#!/usr/bin/env bash
# Convenience script: builds every service. Each service is an independent Spring Boot project
# (own pom.xml, own mvnw, no shared libraries), so this is only a loop; CI builds each one alone.
#
#   ./build-all.sh                # compile + unit tests + package
#   ./build-all.sh -DskipTests
#   ./build-all.sh -Pintegration  # also run Testcontainers integration tests (needs Docker)
set -euo pipefail
cd "$(dirname "$0")"

for dir in services/*/; do
  s=$(basename "$dir")
  echo "==> $s"
  (cd "$dir" && ./mvnw -q -B package "$@")
done

echo "All services built."
