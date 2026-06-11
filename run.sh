#!/usr/bin/env bash
#
# Build (if needed) and run the Catalog Pub/Sub Monitoring Tool, automatically
# freeing the HTTP port if something is already listening on it.
#
# Usage:
#   ./run.sh                 # port 8080
#   PORT=8099 ./run.sh       # custom port
#
set -euo pipefail

PORT="${PORT:-8080}"
JAR="target/catalog-pubsub-gui-1.0.0.jar"
CONTEXT="/catalog-pubsub-gui"

free_port() {
  local pids
  pids="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true)"
  if [ -n "$pids" ]; then
    echo "Port $PORT is in use by PID(s): $pids — stopping them…"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    sleep 1
    pids="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true)"
    if [ -n "$pids" ]; then
      echo "Still up — force killing PID(s): $pids"
      # shellcheck disable=SC2086
      kill -9 $pids 2>/dev/null || true
      sleep 1
    fi
  fi
}

free_port

if [ ! -f "$JAR" ]; then
  echo "Jar not found at $JAR — building…"
  mvn -q clean package -DskipTests
fi

echo "Starting on port $PORT → http://localhost:$PORT$CONTEXT/"
exec env PORT="$PORT" java -jar "$JAR"
