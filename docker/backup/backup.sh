#!/bin/sh
set -eu
mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb -p local/collabdocs || true

dump_once() {
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  pg_dump -Fc -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -f /tmp/dump
  openssl enc -aes-256-cbc -pbkdf2 -pass env:BACKUP_KEY -in /tmp/dump -out /tmp/dump.enc
  mc cp /tmp/dump.enc "local/collabdocs/db/${stamp}.dump.enc"
  rm -f /tmp/dump /tmp/dump.enc
  mc find local/collabdocs/db --older-than 30d --exec "mc rm {}" || true
}

sync_wal() {
  if [ -d /wal ]; then
    mc mirror --overwrite /wal local/collabdocs/wal || true
  fi
}

if [ "${1:-}" = "once" ]; then
  dump_once
  sync_wal
  exit 0
fi

while true; do
  sync_wal
  sleep 5
done &
while true; do
  dump_once
  sleep 86400
done
