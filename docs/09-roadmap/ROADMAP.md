# CollabDocs Implementation Roadmap

| Field | Value |
| --- | --- |
| Audience | Engineering leads and AI coding agents |
| Depends on | [PRD](../01-product/PRD.md), [HLD](../02-architecture/HLD.md), [Agent guide](AGENT_IMPLEMENTATION_GUIDE.md) |

Versions are dependency order, not calendar dates. A version exits when its acceptance list is true. Tasks are small enough for one agent session. File ownership is in the agent guide.

## V1 — Functional single-user editor

### Objectives

A verified user can create and edit a document in the browser, refresh, and see committed text. The stack runs in Docker.

### Prerequisites

Documentation in this repository. Docker on the host.

### Features

`FR-AUTH-001` through `FR-AUTH-012` except avatar may land as the last V1 task. `FR-DOC-001` through `FR-DOC-011`, `FR-DOC-014`. `FR-ORG-001` through `FR-ORG-003`. `FR-EDIT` Must list. `FR-COLLAB-001` through `FR-COLLAB-005` via HTTP. `FR-VER-008` save states. Health checks.

### Database

Apply `V1__init.sql`. No further tables are required for V1 if the initial migration is used as written. Agents must not drop collaboration tables to "simplify" V1.

### API

Auth, me, workspaces list, folders, documents, content-updates, content-state, search, star, trash. Share routes return `404` until V2 if not implemented, or are implemented as owner-only grants created at document create. Required in V1: the owner grant row exists. Share management UI is V2.

### Tasks

1. **Compose baseline.** Confirm `docker compose` starts the existing health shells, migrate, postgres, redis, minio, and mailpit. Exit: `GET /health/ready` on `api` returns 200 inside the network. Do not replace the health endpoints.
2. **Flyway.** `migrate` applies `V1__init.sql` cleanly.
3. **Common and persistence.** UUID v7 helper, error types, `PermissionService` with tests, `ContentUpdateService` with Testcontainers.
4. **Auth.** Register, verify, login, refresh, logout, forgot, reset. Mailpit receives mail.
5. **Document metadata.** Create with empty snapshot resource, list, rename, folder CRUD, trash, restore, star, recent, title search.
6. **Web shell.** Vite app, login, register, list, empty states, API client with refresh.
7. **Editor.** Tiptap schema for V1 marks and blocks, local Y.Doc, debounced POST of updates, save status, reload through content-state.
8. **Tests.** Suites named for V1 in the testing strategy, plus Playwright smoke.

Frontend and backend tasks 6 and 4 can run in parallel after task 3 defines error JSON and after OpenAPI is treated as frozen for the endpoints they touch.

### Acceptance

- Smoke test in the testing strategy passes.
- An acked update survives API container restart.
- A second user cannot read the document.
- No host install of Java or Node was required to run the test.

### Risks

Empty snapshot fixture drifts from the schema. The compactor test that decodes `empty-doc-v1.bin` is part of task 7.

### Exit

All V1 acceptance items and the V1 release row in the testing strategy.

## V2 — Real-time collaboration

### Objectives

Two browsers converge. Sharing enforces roles. Restart and revoke behave as specified.

### Prerequisites

V1 exit. `ContentUpdateService` is the only insert path.

### Features

`FR-COLLAB-006` through `FR-COLLAB-018`, `FR-SHARE-001` through `FR-SHARE-009`, `FR-SHARE-011` through `FR-SHARE-016`. IndexedDB offline queue.

### Tasks

1. Gateway WebSocket auth, welcome, ack, using `ContentUpdateService`.
2. Redis fan-out and a two-replica test.
3. Client protocol module and presence UI.
4. Reconnect, duplicate update id, backoff.
5. Share API and dialog. Link redeem. ACL pub/sub and socket close.
6. IndexedDB queue and epoch mismatch handling.
7. Collaboration test cases TC-COLLAB-001 through 011 and 013.

### Acceptance

- TC-COLLAB-001, 004, 005, 007, 008, 009, and 010 pass.
- A viewer cannot write.
- Revocation closes the socket.

### Risks

Applying welcome on top of a dirty Y.Doc. The client procedure in the protocol document is mandatory. Add a test that fails if the client resets a same-epoch doc.

### Exit

Acceptance plus the protocol tests. Offline across restore is TC-COLLAB-012's client half and may be finished in V3 if the restore API is not ready. The queue itself exits in V2.

## V3 — Review and versioning

### Objectives

Comments, suggestions, named versions, restore, notifications, and audit.

### Prerequisites

V2 exit. Epoch field already exists.

### Tasks

1. Comment tables are already in V1 SQL. Implement services and UI. Anchor rebinding.
2. Suggestion registration, marks in the schema, accept and reject transaction.
3. Compactor service writing automatic snapshots and word counts.
4. Named versions and restore epoch. Client `document.restored`.
5. Compare endpoint using compactor projections.
6. Notifications and mail for mentions and shares.
7. Audit writes on the share and restore paths.
8. Tests for suggestions, comments, and TC-COLLAB-012.

### Acceptance

- Accept and reject are atomic with the content update.
- Restore bumps epoch and both clients move.
- A comment survives an insert beside its anchor.
- Audit row exists for revoke and restore.

### Risks

Suggestion marks are the hardest editor work. Do not start export in parallel with the first suggestion implementation in the same files.

### Exit

Testing strategy V3 row.

## V4 — Advanced document features

### Objectives

Tables, images, templates, import, export, outline, and deeper accessibility.

### Prerequisites

V3 snapshots, because export projects snapshots.

### Tasks

1. Table extensions and schema tests.
2. Attachment API and image nodes.
3. System templates as object-storage snapshots.
4. Compactor project and encode endpoints.
5. DOCX import and the fidelity fixtures.
6. HTML import with the SSRF sink test.
7. PDF, DOCX, HTML, and text export jobs.
8. Outline pane from headings.
9. Header, footer, and page number settings on the document and in PDF export.
10. Dark theme tokens and tablet layout.
11. Axe suite.

Footnotes stay after V4 unless the milestone is otherwise done and a schema migration is reviewed. The PRD lists them as Could.

### Acceptance

- Fidelity matrix rows marked yes pass the fixture suite.
- Remote image import does not call the network.
- Export download checks permission.
- Axe serious violations are zero on the named pages.

### Risks

POI fidelity. ADR-011 allows a LibreOffice spike only after the fixture suite fails.

### Exit

Testing strategy V4 row.

## V5 — Production readiness and scale

### Objectives

Operate the medium profile safely.

### Prerequisites

V4 exit.

### Tasks

1. Two-replica staging deploy and TC-COLLAB-010 in that environment.
2. k6 scripts and recorded results against the NFR targets.
3. Backup, WAL archiving, and a timed restore drill.
4. Application database role split.
5. Alert rules and dashboards.
6. CI image build and staging deploy workflow.
7. Optional ClamAV on import.
8. Dependency scanning gate.
9. Load-test note that replaces capacity assumptions with measurements.

### Acceptance

- Restore drill meets the medium RTO target or the gap is written down with a new target approved in the NFR document.
- Ack p95 meets `NFR-PERF-005` on the small profile and is measured on staging.
- A revoked session cannot open a socket.
- Rollback of an application image is rehearsed.

### Risks

WAL archiving is easy to skip and then the RPO story is false. It is an exit criterion.

### Exit

Testing strategy V5 row and an updated capacity section that cites the load-test artifacts.

## Cross-version rule

Agents do not pull a later version's UI into an earlier milestone if it requires unfinished server behavior, except feature flags that default off. Schema is added in the version that first writes the table. The initial migration already contains later tables so V1 does not need a second design. Later changes are new Flyway versions.
