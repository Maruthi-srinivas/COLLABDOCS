#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v migrator_password="$MIGRATOR_DB_PASSWORD" \
  -v app_password="$APP_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE collabdocs_migrator LOGIN PASSWORD %L', :'migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'collabdocs_migrator') \gexec
SELECT format('ALTER ROLE collabdocs_migrator PASSWORD %L', :'migrator_password') \gexec
SELECT format('CREATE ROLE collabdocs_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'collabdocs_app') \gexec
SELECT format('ALTER ROLE collabdocs_app PASSWORD %L', :'app_password') \gexec
GRANT USAGE, CREATE ON SCHEMA public TO collabdocs_migrator;
GRANT ALL ON ALL TABLES IN SCHEMA public TO collabdocs_migrator;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO collabdocs_migrator;
SQL
