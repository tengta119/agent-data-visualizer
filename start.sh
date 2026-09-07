#!/usr/bin/env bash
set -euo pipefail

shutdown() {
  if [[ -n "${FRONTEND_PID:-}" ]]; then
    kill "${FRONTEND_PID}" 2>/dev/null || true
  fi
  if [[ -n "${BACKEND_PID:-}" ]]; then
    kill "${BACKEND_PID}" 2>/dev/null || true
  fi
}

trap shutdown INT TERM

cd /app/frontend
npm start -- -H 0.0.0.0 -p 3000 &
FRONTEND_PID=$!

JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m}"
java ${JAVA_OPTS} -jar /app/backend/app.jar &
BACKEND_PID=$!

wait -n "${FRONTEND_PID}" "${BACKEND_PID}"
EXIT_CODE=$?
shutdown
exit "${EXIT_CODE}"
