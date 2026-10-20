# CollabDocs documentation index

CollabDocs is a web document editor with accounts, rich text, real-time collaboration, sharing, review, and export. This directory is the implementation baseline. Docker Compose already starts PostgreSQL, Redis, MinIO, Mailpit, Flyway, Nginx, a Vite shell, and Java services that answer health checks. Product features are introduced by the roadmap, starting with permissions and durable content updates.

## Proposed baseline

1. **Architecture.** Stateless Java services for the API, the WebSocket gateway, and background jobs. PostgreSQL stores metadata and an append-only Yjs update log. Redis fans updates out and holds ephemeral presence. A small Node compactor is the only process that runs Yjs, and it writes snapshots. Nginx is the edge. The browser holds a Yjs document bound to a ProseMirror editor.
2. **Stack.** Java 21 and Spring Boot 3.4, Maven, PostgreSQL 16, Redis 7, MinIO, React 18, TypeScript, Vite, Tiptap, Yjs. Every runtime is a container. The host needs Docker, not a local JDK, Node, or database.
3. **MVP.** Version 1 is a single-user editor: registration and email verification, personal workspace, folders, document CRUD, rich-text formatting from the Must list, and durable autosave through the same update log that collaboration will use later.
4. **Hardest problems.** Converging concurrent edits without last-write-wins. Defining "saved" as a database commit. Enforcing permissions on sockets after revoke. Restoring a version by starting a new epoch so old updates cannot append. Keeping suggestion marks consistent with the CRDT. Compacting Yjs without a JVM port.
5. **Roadmap.** V1 single-user editor. V2 collaboration and sharing. V3 comments, suggestions, and versions. V4 tables, images, import, and export. V5 scale, backups, and operations.
6. **Major risks.** Suggestion acceptance inside the editor. DOCX fidelity. Snapshot storage growth. A false recovery-point claim if WAL archiving is skipped. Workspace admins reading documents if that product question stays at its default.
7. **Start here.** Bring up Compose and Flyway, then implement `PermissionService` and `ContentUpdateService` before building editor features. The first coding prompt is in the [agent guide](09-roadmap/AGENT_IMPLEMENTATION_GUIDE.md).

## Reading order

### Product and requirements

| Document | Purpose | Read after |
| --- | --- | --- |
| [PRD](01-product/PRD.md) | Users, scope, journeys, MVP, open questions | Start |
| [SRS](01-product/SRS.md) | Numbered requirements, rules, errors | PRD |

### Architecture

| Document | Purpose | Read after |
| --- | --- | --- |
| [HLD](02-architecture/HLD.md) | Containers, sync flow, failure behavior | SRS |
| [ADRs](02-architecture/ADRS.md) | Why this stack and these boundaries | HLD |
| [LLD](02-architecture/LLD.md) | Services, transactions, state machines | HLD |

### Contracts

| Document | Purpose | Read after |
| --- | --- | --- |
| [Database design](03-data/DATABASE_DESIGN.md) | Tables, retention, Redis keys | LLD |
| [API specification](04-api/API_SPECIFICATION.md) | HTTP behavior | SRS |
| [OpenAPI](04-api/openapi.yaml) | Machine-readable HTTP contract | API specification |
| [Collaboration protocol](04-api/COLLABORATION_PROTOCOL.md) | WebSocket frames and Yjs mapping | HLD |

### Quality and operations

| Document | Purpose | Read after |
| --- | --- | --- |
| [Security](05-security/SECURITY.md) | Threats and controls | HLD |
| [Frontend](06-frontend/FRONTEND_DESIGN.md) | Routes, editor states, tokens | API and protocol |
| [NFR and capacity](07-quality/NFR_AND_CAPACITY.md) | Targets and sizing math | HLD |
| [Testing](07-quality/TESTING_STRATEGY.md) | Suites and collaboration cases | SRS |
| [DevOps](08-devops/DEVOPS.md) | Compose, backups, deploy | HLD |
| [Observability](08-devops/OBSERVABILITY.md) | Metrics, alerts, runbooks | DevOps |

### Execution

| Document | Purpose | Read after |
| --- | --- | --- |
| [Roadmap](09-roadmap/ROADMAP.md) | Version tasks and exit criteria | SRS and HLD |
| [Agent guide](09-roadmap/AGENT_IMPLEMENTATION_GUIDE.md) | Parallel work and task prompt | Roadmap |
| [Traceability](10-traceability/TRACEABILITY_MATRIX.md) | Requirement to test to milestone | SRS |

Agents implementing a task should read the SRS section for their requirement ids, the LLD service they are touching, and either the OpenAPI file or the collaboration protocol. They should not rely on this index alone.

## Infrastructure files

| File | Purpose |
| --- | --- |
| [docker-compose.yml](../docker-compose.yml) | Service graph |
| [docker-compose.dev.yml](../docker-compose.dev.yml) | Bind mounts, Mailpit ports, Vite |
| [`.env.example`](../.env.example) | Variable names without secrets |
| [db/migration/V1__init.sql](../db/migration/V1__init.sql) | Initial schema |
| [db/flyway.conf](../db/flyway.conf) | Flyway settings |
| [docker/nginx/nginx.conf](../docker/nginx/nginx.conf) | Routing and WebSocket upgrade |
| [.github/workflows/ci.yml](../.github/workflows/ci.yml) | Migration and image checks |

## Consistency rules

- Service names: `web`, `api`, `collab`, `worker`, `compactor`, `postgres`, `redis`, `minio`, `nginx`, `mailpit`, `migrate`.
- "Saved" means a committed row in `collab_updates` and an acknowledgement.
- Roles: viewer, commenter, editor, owner.
- Document bytes are Yjs updates. There is no canonical JSON body column.
- Public HTTP lives under `/api/v1`. The socket subprotocol is `collabdocs.v1`.

## Open questions that still need a human

OQ-1 account deletion grace period. OQ-2 404 versus 403. OQ-3 workspace admin implicit document access. OQ-4 who may accept suggestions. OQ-5 whether links may grant editor. Defaults are in the PRD. Do not treat the defaults as customer-approved policy.
