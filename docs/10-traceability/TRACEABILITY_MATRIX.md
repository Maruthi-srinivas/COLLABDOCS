# CollabDocs Traceability Matrix

| Field | Value |
| --- | --- |
| Audience | QA and anyone checking completeness |
| Depends on | [SRS](../01-product/SRS.md), [Roadmap](../09-roadmap/ROADMAP.md) |

Implementation status for every row is **not implemented**. This repository contains the engineering baseline and infrastructure files. Status changes to `implemented` only when the named test exists and passes.

## 1. How to read a row

Each requirement maps to a design home, a data home, an interface, a milestone, and a verification method. "API" paths are under `/api/v1` unless noted.

## 2. Accounts

| Requirement | Design | Data | Interface | Version | Verification |
| --- | --- | --- | --- | --- | --- |
| FR-AUTH-001 | AuthService.register | users, workspaces, workspace_memberships, email_tokens | POST /auth/register | V1 | API integration |
| FR-AUTH-002 | AuthService.verifyEmail | email_tokens, users | POST /auth/verify-email | V1 | API integration |
| FR-AUTH-003 | AuthService resend | email_tokens | POST /auth/verify-email/resend | V1 | Rate-limit test |
| FR-AUTH-004 | AuthService.login | refresh_sessions | POST /auth/login | V1 | API integration |
| FR-AUTH-005 | Refresh rotation | refresh_sessions | POST /auth/refresh | V1 | Reuse test |
| FR-AUTH-006 | Logout denylist | refresh_sessions, Redis denylist | POST /auth/logout, logout-all | V1 | Denylist test |
| FR-AUTH-007 | Password reset | email_tokens, refresh_sessions | POST /auth/password/forgot, reset | V1 | API integration |
| FR-AUTH-008 | Profile | users | GET, PATCH /me | V1 | API integration |
| FR-AUTH-009 | Avatar | users.avatar_object_key, object storage | POST, DELETE /me/avatar | V1 | Upload fixture |
| FR-AUTH-010 | Sessions | refresh_sessions | GET, DELETE /auth/sessions | V1 | API integration |
| FR-AUTH-011 | Deletion | users.status, worker purge | DELETE /me | V1 schedule, worker in V1 or V3 | Worker test |
| FR-AUTH-012 | Security filter | Redis denylist | All protected routes | V1 | Security suite |
| FR-AUTH-013 | OAuth | users.password_hash nullable | Not exposed yet | Later | Deferred |

## 3. Documents and organization

| Requirement | Design | Data | Interface | Version | Verification |
| --- | --- | --- | --- | --- | --- |
| FR-DOC-001 | DocumentService.create | documents, document_permissions, document_epochs, document_snapshots | POST /workspaces/{id}/documents | V1 | API integration |
| FR-DOC-002 | DocumentService read | documents | GET /documents/{id} | V1 | API integration |
| FR-DOC-003 | Rename | documents.version | PATCH /documents/{id} | V1 | Conflict test |
| FR-DOC-004 | Move | documents.folder_id | POST /documents/{id}/move | V1 | API integration |
| FR-DOC-005 | Duplicate | new document and copied snapshot | POST /documents/{id}/duplicate | V1 | Grant isolation test |
| FR-DOC-006 | Trash and purge | documents.status | DELETE, restore, purge | V1 | Lifecycle test |
| FR-DOC-007 | Star | document_stars | POST, DELETE /star | V1 | API integration |
| FR-DOC-008 | Recent | recent_documents | GET .../documents/recent | V1 | API integration |
| FR-DOC-009 | List | documents indexes | GET .../documents | V1 | Pagination test |
| FR-DOC-010 | Empty UI | none | Web lists | V1 | Component test |
| FR-DOC-011 | Load and error UI | none | Editor page | V1 | Component and e2e |
| FR-DOC-012 | Templates | templates | POST .../from-template | V4 | API integration |
| FR-DOC-013 | Single owner | documents.owner_id, grants | transfer-ownership | V2 | Transaction test |
| FR-DOC-014 | UUIDv7 | all public ids | generated in API | V1 | Unit test |
| FR-ORG-001 | FolderService | folders | POST .../folders | V1 | Depth test |
| FR-ORG-002 | Trash batch | folders.trash_batch_id | DELETE /folders/{id} | V1 | Subtree test |
| FR-ORG-003 | Title search | documents_title_trgm_idx | GET .../search | V1 | IDOR test |
| FR-ORG-010 | Body search | future text column | none | Later | Not scheduled |

## 4. Editor

| Requirement | Design | Data | Interface | Version | Verification |
| --- | --- | --- | --- | --- | --- |
| FR-EDIT-001 | schema.ts, compactor | snapshots | editor fragment `prosemirror` | V1 schema, V4 nodes | Schema unit tests |
| FR-EDIT-002 to FR-EDIT-018 | Toolbar commands | Yjs marks | local, then content update | V1 Must, V2 Should | Editor unit tests |
| FR-EDIT-019 | Block nodes | Yjs | editor | V1 rule and quote, V4 page break UI | Unit |
| FR-EDIT-020 | Tables | Yjs | editor | V4 | Unit and fidelity |
| FR-EDIT-021 | Images | attachments | POST attachments | V4 | Upload test |
| FR-EDIT-022 | Undo | local history | editor | V1 | Editor test |
| FR-EDIT-023 | Clipboard sanitizer | none | editor | V1 | Paste test |
| FR-EDIT-024 | Shortcuts | none | editor | V1 | Keyboard e2e |
| FR-EDIT-025 | Find | none | editor | V2 | Unit |
| FR-EDIT-026 | Word count | documents.word_count cache | editor and compactor | V3 cache, V1 local count | Unit |
| FR-EDIT-027 | Drag and menu | none | editor | V2 | Component |
| FR-EDIT-028 | Focus | none | editor | V1 | Component |
| FR-EDIT-029 | Refresh | content-state | GET /content-state | V1 | E2E smoke |
| FR-EDIT-030 | schemaVersion | snapshots.schema_version | welcome and content-state | V1 field, migrations when needed | Compactor test |
| FR-EDIT-032 | Read-only | role | editor props | V2 | Component |
| FR-EDIT-036 | Server rejects viewer edits | PermissionService | content-updates and socket | V2 | TC-COLLAB-008 |

## 5. Collaboration

| Requirement | Design | Data | Interface | Version | Verification |
| --- | --- | --- | --- | --- | --- |
| FR-COLLAB-001 | Y.Doc plus log | collab_updates, snapshots | protocol | V1 HTTP, V2 socket | TC-COLLAB-005 |
| FR-COLLAB-002 | y-prosemirror | local | editor | V1 | Unit |
| FR-COLLAB-003 | Transmit | update_id | POST content-updates and sync.update | V1 and V2 | Duplicate test |
| FR-COLLAB-004 | applyUpdate | none on server | client | V2 | TC-COLLAB-001 |
| FR-COLLAB-005 | ContentUpdateService | collab_updates | ack | V1 | Restart test |
| FR-COLLAB-006 | Welcome tail | snapshots, updates | welcome, GET content-state | V2 | TC-COLLAB-005 |
| FR-COLLAB-007 | Stateless gateway | PostgreSQL | reconnect | V2 | TC-COLLAB-009 |
| FR-COLLAB-008 | Compactor | document_epochs.snapshot_through_seq | internal compact | V3 | Compactor test |
| FR-COLLAB-009 | Redis fan-out | channels | sync.apply | V2 | TC-COLLAB-010 |
| FR-COLLAB-010 | Handshake | denylist | auth frame | V2 | Security test |
| FR-COLLAB-011 | Awareness | Redis presence | awareness | V2 | Protocol test |
| FR-COLLAB-012 | Presence list | Redis | UI | V2 | Component |
| FR-COLLAB-013 | Backoff and resend | unique update id | client queue | V2 | TC-COLLAB-006 |
| FR-COLLAB-014 | IndexedDB | browser | client | V2 | Offline test |
| FR-COLLAB-015 | Idempotency | unique key | ack duplicate | V1 | TC-COLLAB-004 |
| FR-COLLAB-016 | ACL event | permissions.revoked_at | close 4403 | V2 | TC-COLLAB-007 |
| FR-COLLAB-017 | Size cap | byte_size check | 413 | V2 | TC-COLLAB-013 |
| FR-COLLAB-018 | View intent | role | nack | V2 | TC-COLLAB-008 |

## 6. Sharing, comments, suggestions, versions

| Requirement | Design | Data | Interface | Version | Verification |
| --- | --- | --- | --- | --- | --- |
| FR-SHARE-001 to 003 | ShareService | document_permissions | permissions routes | V2 | API tests |
| FR-SHARE-004 to 007 | Links | share_links | share-links routes | V2 | Expiry test |
| FR-SHARE-008 | Transfer | documents.owner_id | transfer-ownership | V2 | Transaction test |
| FR-SHARE-009 | Access requests | access_requests | access-requests | V2 | API test |
| FR-SHARE-011 | Audit | audit_events | side effect | V3 | Row assertion |
| FR-SHARE-012 | Three enforcement points | PermissionService | API, collab, worker | V2 and V4 | Security tests |
| FR-SHARE-014 | Folder ACL | not modeled | none | Later | Not scheduled |
| FR-CMT-001 to 012 | CommentService | comment_threads, comments | thread routes | V3 | Anchor test |
| FR-SUG-001 to 010 | SuggestionService | suggestions, Yjs marks | suggestion routes | V3 | Atomicity and race tests |
| FR-VER-001 | Compactor policy | document_versions | automatic rows | V3 | Policy test |
| FR-VER-002 | Named snapshot | snapshot_requests | POST /versions | V3 | API test |
| FR-VER-003 | Retention | document_versions | GET /versions | V3 | Retention test |
| FR-VER-004 | Preview | worker render | GET preview | V3 | HTML sanitizer test |
| FR-VER-005 | Compare | compactor text | GET compare | V3 | Fixture test |
| FR-VER-006 and 007 | Restore epoch | document_epochs | POST restore, document.restored | V3 | TC-COLLAB-012 |
| FR-VER-008 | Save status | acks | UI | V1 | Component |
| FR-VER-009 | Crash | IndexedDB in V2 | client | V2 | Kill-page test |
| FR-VER-010 | Prune job | worker | scheduled | V3 | Retention test |

## 7. Transfer and notifications

| Requirement | Design | Data | Interface | Version | Verification |
| --- | --- | --- | --- | --- | --- |
| FR-EXP-001 to 004 | ExportProcessor | export_jobs, object storage | exports routes | V4 | Job test |
| FR-EXP-005 to 007 | ImportProcessor | import_jobs | imports routes | V4 | Fidelity and size tests |
| FR-EXP-008 | Download re-check | permissions | GET download | V4 | Revoke test |
| FR-EXP-009 | No fetch | import report | worker | V4 | SSRF sink test |
| FR-EXP-011 | Idempotency key | export_jobs unique index | POST exports | V4 | Duplicate key test |
| FR-NTF-001 and 002 | NotificationService | notifications | notification routes | V3 | API test |

## 8. Non-functional

| Requirement | Design | Version | Verification |
| --- | --- | --- | --- |
| NFR-A11Y-001 to 008 | FRONTEND_DESIGN.md | V1 start, V4 axe | Playwright axe and keyboard script |
| NFR-SEC-001 to 010 | SECURITY.md | V1 auth, V5 scan | Security tests and CI scan |
| NFR-PERF-001 to 006 | NFR_AND_CAPACITY.md | V5 measure, design from V1 | k6 |
| NFR-AVAIL-001 to 004 | DEVOPS backup and health | V5 | Restore drill |
| NFR-COMPAT-001 to 004 | Browser list, /api/v1, collabdocs.v1 | V1 | Smoke on one browser in CI, others on release |

## 9. Architecture elements

| Element | Requirements it carries | Milestone that creates it |
| --- | --- | --- |
| services/api | Auth, documents, shares, comments, jobs API | V1 onward |
| services/collab | FR-COLLAB socket path | V2 |
| services/worker | Export, import, purge, mail | V1 mail, V3 purge, V4 export |
| services/compactor | Snapshots, project, encode | V3 and V4 |
| services/web | All UI requirements | V1 onward |
| PostgreSQL | System of record | V1 |
| Redis | Fan-out, presence, limits | V1 limits, V2 fan-out |
| Object storage | Blobs | V1 avatars, V4 attachments |

## 10. Gaps

These ids are specified and intentionally not scheduled in V1–V5 exit criteria:

- `FR-AUTH-013` OAuth.
- `FR-ORG-010` body search.
- `FR-SHARE-014` folder inheritance.
- Footnotes.
- SAML and SCIM.
- Multi-region active-active.

Admin implicit access (`BR-SHARE-004`, OQ-3) is implemented only after the flag exists. The flag default is documented. It is not a silent behavior change.

## 11. Coverage check

Every `FR-*` id in the SRS appears in this matrix or in the gaps section. New requirements need a new row in the same pull request as the SRS change.
