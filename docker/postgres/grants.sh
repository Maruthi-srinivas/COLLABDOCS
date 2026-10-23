#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
GRANT CONNECT ON DATABASE collabdocs TO collabdocs_migrator, collabdocs_app;
GRANT USAGE, CREATE ON SCHEMA public TO collabdocs_migrator;
GRANT ALL ON ALL TABLES IN SCHEMA public TO collabdocs_migrator;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO collabdocs_migrator;
GRANT USAGE ON SCHEMA public TO collabdocs_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO collabdocs_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO collabdocs_app;
REVOKE UPDATE, DELETE ON TABLE audit_events FROM collabdocs_app;
GRANT SELECT, INSERT ON TABLE audit_events TO collabdocs_app;
ALTER DEFAULT PRIVILEGES FOR ROLE collabdocs_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO collabdocs_app;
ALTER DEFAULT PRIVILEGES FOR ROLE collabdocs_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO collabdocs_app;
SQL
