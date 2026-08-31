#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# Linux treats ':' as the classpath separator. Build shared modules first,
# then build services outside the reactor when this project path contains ':'.
if [[ "$ROOT_DIR" == *:* ]]; then
  ./mvnw -pl common/common-core,common/common-web -am clean install "$@"
  for pom in service/*/pom.xml; do
    ./mvnw -f "$pom" clean package "$@"
  done
else
  ./mvnw clean package "$@"
fi
