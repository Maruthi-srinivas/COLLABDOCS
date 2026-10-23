#!/bin/sh
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")/../.."
compose="docker compose --env-file .env -f docker-compose.yml -f docker-compose.staging.yml"
docker tag collabdocs-api:latest collabdocs-api:previous
API_IMAGE=collabdocs-api:previous $compose up -d --no-build --no-deps api api-b
ready=""
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if docker exec collabdocs-api-1 wget -qO- http://127.0.0.1:8080/health/ready | grep -q '"status":"up"'; then
    ready=previous
    break
  fi
  sleep 3
done
test "$ready" = previous
API_IMAGE=collabdocs-api:latest $compose up -d --no-build --no-deps api api-b
ready=""
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if docker exec collabdocs-api-1 wget -qO- http://127.0.0.1:8080/health/ready | grep -q '"status":"up"'; then
    ready=current
    break
  fi
  sleep 3
done
test "$ready" = current
echo "rollback rehearsal passed"
