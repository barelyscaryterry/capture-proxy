#!/bin/bash
set -euxo pipefail

for i in $(seq 1 10); do
  if curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
    echo "Service healthy"
    exit 0
  fi
  echo "Attempt $i: not healthy yet, retrying..."
  sleep 3
done

echo "Service failed health check after retries"
exit 1
