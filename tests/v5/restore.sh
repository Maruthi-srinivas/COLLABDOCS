#!/bin/sh
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")/../.."
api="${API_URL:-http://localhost:8080}"
email="restore-$(date +%s)@example.com"
created=$(curl -sf -H "content-type: application/json" -H "X-CollabDocs-Request: 1" \
  -d "{\"email\":\"$email\",\"displayName\":\"Restore\",\"password\":\"correct-horse-1\"}" \
  "$api/api/v1/auth/register")
token=$(printf "%s" "$created" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
workspace=$(printf "%s" "$created" | tr ',' '\n' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | tail -n 1)
title="restore-marker-$email"
doc=$(curl -sf -H "content-type: application/json" -H "X-CollabDocs-Request: 1" -H "Authorization: Bearer $token" \
  -d "{\"title\":\"$title\"}" "$api/api/v1/workspaces/$workspace/documents")
docid=$(printf "%s" "$doc" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

docker exec collabdocs-backup-1 /backup.sh once
docker exec collabdocs-postgres-1 psql -U collabdocs -d collabdocs -c "INSERT INTO audit_events (action, target_type) VALUES ('restore.drill', 'document');"
docker exec collabdocs-postgres-1 psql -U collabdocs -d collabdocs -c "SELECT pg_switch_wal();"
found=""
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if docker exec collabdocs-backup-1 mc ls local/collabdocs/wal | grep -q .; then
    found=yes
    break
  fi
  sleep 2
done
test "$found" = yes

docker rm -f collabdocs-restore-pg collabdocs-restore-api >/dev/null 2>&1 || true
docker run -d --name collabdocs-restore-pg --network collabdocs \
  -e POSTGRES_PASSWORD=restore -e POSTGRES_USER=collabdocs -e POSTGRES_DB=collabdocs postgres:16-alpine >/dev/null
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if docker exec collabdocs-restore-pg pg_isready -U collabdocs >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
start=$(date +%s)
key=$(docker exec collabdocs-backup-1 mc ls local/collabdocs/db | awk '{print $NF}' | tail -n 1)
docker exec collabdocs-backup-1 sh -c "mc cp local/collabdocs/db/$key /tmp/restore.dump.enc && openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_KEY -in /tmp/restore.dump.enc -out /tmp/restore.dump"
mkdir -p .tmp
docker cp collabdocs-backup-1:/tmp/restore.dump .tmp/restore.dump
docker cp .tmp/restore.dump collabdocs-restore-pg:/tmp/restore.dump
rm -f .tmp/restore.dump
docker exec collabdocs-restore-pg pg_restore -U collabdocs -d collabdocs --no-owner --no-privileges /tmp/restore.dump
docker exec collabdocs-restore-pg psql -U collabdocs -d collabdocs -c "SELECT count(*) AS users FROM users;"
docker run -d --name collabdocs-restore-api --network collabdocs --env-file .env \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://collabdocs-restore-pg:5432/collabdocs \
  -e SPRING_DATASOURCE_USERNAME=collabdocs \
  -e SPRING_DATASOURCE_PASSWORD=restore \
  -e REDIS_URL=redis://redis:6379 \
  -e SERVER_PORT=8080 collabdocs-api:latest >/dev/null
opened=""
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
  if docker exec collabdocs-restore-api wget -qO- http://127.0.0.1:8080/health/ready | grep -q '"status":"up"'; then
    if docker run --rm --network collabdocs \
      -v "$PWD/tests/v5:/work" -w /work \
      -e EMAIL="$email" -e PASSWORD=correct-horse-1 -e DOCID="$docid" -e TITLE="$title" \
      node:22-alpine node restore-open.mjs; then
      opened=yes
      break
    fi
  fi
  sleep 2
done
end=$(date +%s)
elapsed=$((end - start))
if [ "$opened" != yes ]; then
  echo "restore open failed"
  docker logs collabdocs-restore-api --tail 50 || true
  docker rm -f collabdocs-restore-api collabdocs-restore-pg >/dev/null 2>&1 || true
  exit 1
fi
docker rm -f collabdocs-restore-api collabdocs-restore-pg >/dev/null 2>&1 || true
mkdir -p docs/07-quality/load
printf "restore elapsed seconds: %s\nmedium RTO target seconds: 1800\nresult: %s\n" "$elapsed" "$(if [ "$elapsed" -le 1800 ]; then echo met; else echo missed; fi)" > docs/07-quality/load/restore.txt
echo "restore drill passed in ${elapsed}s"
