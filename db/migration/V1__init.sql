-- CollabDocs initial schema. Applied by Flyway.
-- Illustrative of the V1-V5 data model so later milestones alter it with new versions
-- rather than inventing a second schema. Empty-document bytes are loaded by the application.

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
    id uuid PRIMARY KEY,
    email citext NOT NULL UNIQUE,
    password_hash text,
    display_name text NOT NULL,
    avatar_object_key text,
    status text NOT NULL CHECK (status IN ('pending_verification', 'active', 'disabled', 'pending_deletion', 'deleted')),
    email_verified_at timestamptz,
    purge_after timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE email_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    purpose text NOT NULL CHECK (purpose IN ('verify_email', 'reset_password')),
    token_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    family_id uuid NOT NULL,
    token_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    replaced_by uuid,
    user_agent text,
    ip inet,
    last_used_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX refresh_sessions_user_idx ON refresh_sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE workspaces (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    owner_id uuid NOT NULL REFERENCES users (id),
    link_sharing text NOT NULL DEFAULT 'enabled_commenter_max'
        CHECK (link_sharing IN ('disabled', 'enabled_commenter_max', 'allow_editor_links')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workspace_memberships (
    workspace_id uuid NOT NULL REFERENCES workspaces (id),
    user_id uuid NOT NULL REFERENCES users (id),
    role text NOT NULL CHECK (role IN ('owner', 'admin', 'member', 'guest')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE TABLE folders (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces (id),
    parent_id uuid REFERENCES folders (id),
    name text NOT NULL,
    owner_id uuid NOT NULL REFERENCES users (id),
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'trashed')),
    trash_batch_id uuid,
    trashed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX folders_parent_idx ON folders (workspace_id, parent_id) WHERE status = 'active';

CREATE TABLE documents (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces (id),
    folder_id uuid REFERENCES folders (id),
    owner_id uuid NOT NULL REFERENCES users (id),
    title text NOT NULL,
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'trashed', 'purged')),
    epoch integer NOT NULL DEFAULT 1 CHECK (epoch >= 1),
    version integer NOT NULL DEFAULT 1,
    schema_version integer NOT NULL DEFAULT 1,
    word_count integer NOT NULL DEFAULT 0,
    trash_batch_id uuid,
    trashed_at timestamptz,
    purge_after timestamptz,
    compaction_status text NOT NULL DEFAULT 'ok' CHECK (compaction_status IN ('ok', 'failed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_edited_by uuid REFERENCES users (id),
    last_edited_at timestamptz
);

CREATE INDEX documents_list_idx ON documents (workspace_id, folder_id, status, updated_at DESC);
CREATE INDEX documents_title_trgm_idx ON documents USING gin (title gin_trgm_ops);

CREATE TABLE document_epochs (
    document_id uuid NOT NULL REFERENCES documents (id),
    epoch integer NOT NULL,
    last_seq bigint NOT NULL DEFAULT 0,
    snapshot_through_seq bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, epoch)
);

CREATE TABLE document_snapshots (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    epoch integer NOT NULL,
    through_seq bigint NOT NULL,
    schema_version integer NOT NULL,
    kind text NOT NULL CHECK (kind IN ('base', 'automatic', 'named', 'import', 'restore')),
    label text,
    byte_size integer NOT NULL CHECK (byte_size >= 0),
    state bytea,
    state_object_key text,
    created_by uuid REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (
        (state IS NOT NULL AND state_object_key IS NULL)
        OR (state IS NULL AND state_object_key IS NOT NULL)
    ),
    FOREIGN KEY (document_id, epoch) REFERENCES document_epochs (document_id, epoch)
);

CREATE INDEX document_snapshots_lookup_idx ON document_snapshots (document_id, epoch, through_seq DESC);

CREATE TABLE document_versions (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    snapshot_id uuid NOT NULL REFERENCES document_snapshots (id),
    kind text NOT NULL CHECK (kind IN ('automatic', 'named')),
    label text,
    created_by uuid REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX document_versions_list_idx ON document_versions (document_id, created_at DESC);

CREATE TABLE collab_updates (
    id bigserial PRIMARY KEY,
    document_id uuid NOT NULL,
    epoch integer NOT NULL,
    seq bigint NOT NULL CHECK (seq > 0),
    client_id uuid NOT NULL,
    update_id uuid NOT NULL,
    actor_user_id uuid NOT NULL REFERENCES users (id),
    intent text NOT NULL CHECK (intent IN ('edit', 'suggest')),
    payload bytea NOT NULL,
    byte_size integer NOT NULL CHECK (byte_size BETWEEN 1 AND 262144),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, client_id, update_id),
    UNIQUE (document_id, epoch, seq),
    FOREIGN KEY (document_id, epoch) REFERENCES document_epochs (document_id, epoch)
);

CREATE INDEX collab_updates_tail_idx ON collab_updates (document_id, epoch, seq);

CREATE TABLE document_permissions (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    principal_type text NOT NULL CHECK (principal_type IN ('user', 'email_invite')),
    user_id uuid REFERENCES users (id),
    email citext,
    role text NOT NULL CHECK (role IN ('owner', 'editor', 'commenter', 'viewer')),
    granted_by uuid REFERENCES users (id),
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    CHECK (
        (principal_type = 'user' AND user_id IS NOT NULL AND email IS NULL)
        OR (principal_type = 'email_invite' AND email IS NOT NULL AND user_id IS NULL)
    )
);

CREATE UNIQUE INDEX document_permissions_active_user_idx
    ON document_permissions (document_id, user_id)
    WHERE revoked_at IS NULL AND principal_type = 'user';

CREATE UNIQUE INDEX document_permissions_active_invite_idx
    ON document_permissions (document_id, email)
    WHERE revoked_at IS NULL AND principal_type = 'email_invite';

CREATE INDEX document_permissions_user_idx ON document_permissions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE share_links (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    token_hash text NOT NULL UNIQUE,
    role text NOT NULL CHECK (role IN ('viewer', 'commenter', 'editor')),
    expires_at timestamptz,
    created_by uuid NOT NULL REFERENCES users (id),
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE access_requests (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    requester_id uuid NOT NULL REFERENCES users (id),
    status text NOT NULL CHECK (status IN ('open', 'approved', 'denied')),
    decided_by uuid REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz
);

CREATE UNIQUE INDEX access_requests_one_open_idx
    ON access_requests (document_id, requester_id)
    WHERE status = 'open';

CREATE TABLE comment_threads (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    anchor_start bytea NOT NULL,
    anchor_end bytea NOT NULL,
    quoted_text text NOT NULL,
    status text NOT NULL CHECK (status IN ('open', 'resolved')),
    anchor_state text NOT NULL DEFAULT 'bound' CHECK (anchor_state IN ('bound', 'detached')),
    created_by uuid NOT NULL REFERENCES users (id),
    resolved_by uuid REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);

CREATE INDEX comment_threads_doc_idx ON comment_threads (document_id, status);

CREATE TABLE comments (
    id uuid PRIMARY KEY,
    thread_id uuid NOT NULL REFERENCES comment_threads (id),
    author_id uuid NOT NULL REFERENCES users (id),
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    edited_at timestamptz,
    deleted_at timestamptz
);

CREATE INDEX comments_thread_idx ON comments (thread_id, created_at);

CREATE TABLE suggestions (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    author_id uuid NOT NULL REFERENCES users (id),
    type text NOT NULL CHECK (type IN ('insert', 'delete', 'format')),
    status text NOT NULL CHECK (status IN ('pending', 'accepted', 'rejected')),
    payload jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by uuid REFERENCES users (id)
);

CREATE INDEX suggestions_doc_idx ON suggestions (document_id, status);

CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    type text NOT NULL,
    payload jsonb NOT NULL,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX notifications_user_idx ON notifications (user_id, created_at DESC);

CREATE TABLE audit_events (
    id bigserial PRIMARY KEY,
    workspace_id uuid,
    actor_id uuid,
    action text NOT NULL,
    target_type text NOT NULL,
    target_id uuid,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_events_target_idx ON audit_events (target_type, target_id, created_at DESC);

CREATE TABLE export_jobs (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    requested_by uuid NOT NULL REFERENCES users (id),
    format text NOT NULL CHECK (format IN ('pdf', 'docx', 'html', 'txt')),
    include_suggestions boolean NOT NULL DEFAULT false,
    status text NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    attempts integer NOT NULL DEFAULT 0,
    idempotency_key text,
    object_key text,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz
);

CREATE UNIQUE INDEX export_jobs_idempotency_idx
    ON export_jobs (requested_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE import_jobs (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces (id),
    requested_by uuid NOT NULL REFERENCES users (id),
    folder_id uuid REFERENCES folders (id),
    source_format text NOT NULL CHECK (source_format IN ('docx', 'html')),
    source_object_key text NOT NULL,
    document_id uuid REFERENCES documents (id),
    status text NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    report jsonb,
    error_code text,
    attempts integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE attachments (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    object_key text NOT NULL,
    mime_type text NOT NULL,
    byte_size integer NOT NULL CHECK (byte_size > 0),
    width integer,
    height integer,
    alt_text text,
    decorative boolean NOT NULL DEFAULT false,
    created_by uuid NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE document_stars (
    user_id uuid NOT NULL REFERENCES users (id),
    document_id uuid NOT NULL REFERENCES documents (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, document_id)
);

CREATE TABLE recent_documents (
    user_id uuid NOT NULL REFERENCES users (id),
    document_id uuid NOT NULL REFERENCES documents (id),
    opened_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, document_id)
);

CREATE TABLE templates (
    id uuid PRIMARY KEY,
    workspace_id uuid REFERENCES workspaces (id),
    name text NOT NULL,
    description text,
    snapshot_object_key text,
    category text NOT NULL DEFAULT 'system',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE snapshot_requests (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id),
    requested_by uuid NOT NULL REFERENCES users (id),
    kind text NOT NULL CHECK (kind IN ('named', 'preview')),
    label text,
    status text NOT NULL CHECK (status IN ('pending', 'done', 'failed')),
    snapshot_id uuid REFERENCES document_snapshots (id),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Application role used by services. Flyway runs as the migration owner.
-- Passwords are supplied outside this script.

REVOKE ALL ON SCHEMA public FROM PUBLIC;
