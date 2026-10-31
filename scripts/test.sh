#!/usr/bin/env bash
# Runs every unit, integration and concurrency test, then checks that each required test class ran:
#   ./mvnw verify && scripts/check-tests.sh
# Selects a JDK 21 automatically (even when it is not on PATH) and, on Colima, points Testcontainers
# at its Docker socket. Docker Desktop and Linux need nothing extra. Extra arguments go to Maven.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/jdk.sh

if ! JAVA_HOME=$(find_jdk21); then
  echo "JDK 21 not found. Run: scripts/setup.sh --install" >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ -z "${DOCKER_HOST:-}" ] && [ "$(docker context show 2>/dev/null)" = colima ]; then
  export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
fi

echo "Using JDK: $JAVA_HOME"
./mvnw -B verify "$@"
scripts/check-tests.sh
