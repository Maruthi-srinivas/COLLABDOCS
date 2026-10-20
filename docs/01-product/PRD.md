# CollabDocs Product Requirements Document

| Field | Value |
| --- | --- |
| Product | CollabDocs |
| Status | Proposed requirements for implementation |
| Audience | Product, engineering, QA, and AI coding agents |
| Scope | Web document editor with real-time collaboration |
| Related | [SRS](SRS.md), [HLD](../02-architecture/HLD.md), [Roadmap](../09-roadmap/ROADMAP.md) |

## 1. Purpose

This document states what CollabDocs must do for users. It separates product requirements from implementation choices. Technology selections live in the [architecture decision records](../02-architecture/ADRS.md).

## 2. Product vision

CollabDocs is a web application for creating, formatting, sharing, and collaboratively editing documents. Individuals keep personal files. Teams review, comment, and edit together with a familiar word-processor interaction model.

The product is an original application. It does not copy another vendor's name, logo, or visual identity.

## 3. Problem statement

People who write together need one document that stays consistent while several people type, format, comment, and restore earlier versions. Desktop file exchange loses concurrent edits. A shared document must show who is present, keep a durable copy after refresh or crash, and enforce who may view, comment, or edit.

## 4. Target users and personas

### 4.1 Individual author — Priya

Creates personal notes and essays. Needs fast editing, autosave, folders, search, and export. Often uses one laptop and occasionally a second browser.

### 4.2 Student collaborator — Omar

Writes group papers. Needs simultaneous editing, comments, and suggestion mode so peers can propose wording without overwriting accepted text.

### 4.3 Team reviewer — Elena

Reads and comments. Sometimes has commenter access only. Needs anchored comments, mentions, and a clear difference between accepted text and pending suggestions.

### 4.4 Workspace administrator — Sam

Creates a workspace, invites members, sets whether public links are allowed, and audits sharing. Needs membership roles and the ability to disable a member's access.

### 4.5 Viewer — guest via link

Opens a document through a link. Cannot edit unless the link role and workspace policy allow it. Must not see documents they were not granted.

## 5. User journeys

### 5.1 Create and edit

1. Priya registers, verifies email, and lands in a personal workspace.
2. She creates a blank document or a document from a template.
3. She types, formats, and inserts a table and an image.
4. The editor shows saving, then saved, after the server has durably stored the updates.
5. She closes the tab. Reopening shows the same content.

### 5.2 Collaborate

1. Priya shares the document with Omar as an editor.
2. Both connect. Each sees the other's cursor and a presence list.
3. They insert and delete in the same paragraph. Both converge on the same text.
4. Omar's network drops. He continues typing. On reconnect, missed updates merge and his queued updates upload.
5. Priya removes Omar's access. His session closes and further edits are rejected.

### 5.3 Review

1. Elena, a commenter, highlights a sentence and comments.
2. Omar switches to suggestion mode and proposes a replacement.
3. Priya accepts the suggestion. Accepted text changes. The suggestion record remains in history.
4. Priya names a version before a major rewrite and can restore it later.

### 5.4 Organize and export

1. Priya moves the document into a folder, stars it, and finds it through search.
2. She exports PDF and DOCX. She can download the file when the job succeeds.
3. She imports a DOCX into a new document and checks formatting that the importer preserves.

### 5.5 Account lifecycle

1. Priya resets a forgotten password through email.
2. She reviews sessions and revokes one device.
3. She deletes the account. Personal documents are deleted or transferred according to the deletion policy in section 12.

## 6. Product goals and success criteria

These are proposed targets until product analytics and benchmarks exist.

| ID | Goal | Proposed measure |
| --- | --- | --- |
| G-1 | A verified user can create and edit a document without data loss on refresh | 0 failed recovery cases in the V1 acceptance suite |
| G-2 | Two editors converge after concurrent inserts and deletes | 100% of scripted CRDT cases match |
| G-3 | A revoked user cannot read or write document content | 100% of permission tests deny access on REST and WebSocket |
| G-4 | Opening a typical document feels immediate | p95 document-open under 1.5 s for a 100 KB snapshot on a warm cache, same region |
| G-5 | Typing stays local-first | p95 local keystroke apply under 30 ms on a reference laptop profile |
| G-6 | Collaboration feels live | p95 update delivery under 200 ms between two clients in one region, after durable write |
| G-7 | Export completes for supported documents | p95 PDF export under 20 s for a 20-page document without large images |

## 7. Scope boundaries

### 7.1 In scope

Accounts, workspaces, folders, documents, rich text, real-time collaboration, sharing, comments, suggestions, version history, import and export, notifications, audit of security-sensitive actions, and Docker-based development and deployment.

### 7.2 Out of scope for all planned versions in this package

- Native desktop or mobile applications. Mobile browsers are supported with a reduced editing layout.
- Spreadsheets, slides, or site publishing.
- End-to-end encryption where the server cannot read document bytes. The server stores collaborative state so it can sync, snapshot, and export.
- Simultaneous editing inside a single table cell by two carets with cell-level rich text beyond what the editor schema supports. Tables are supported as structured nodes.
- Plugin marketplace, macros, and third-party add-ons.
- On-premise directory sync (SCIM, SAML) in V1–V4. OAuth is specified and scheduled after the core editor. Enterprise SSO is a later decision.
- Real-time voice, chat sidebars unrelated to comments, and tasks/assignments.
- Legal hold and eDiscovery beyond the audit log and retention rules defined here.

## 8. MoSCoW prioritization

| Priority | Capabilities |
| --- | --- |
| Must | Register, login, logout, email verification, password reset, sessions, personal workspace, document CRUD, folders, trash, star, recent, title search, rich text listed as Must in section 9, autosave, durable recovery after refresh, Docker dev environment |
| Should | Real-time multi-user editing, presence, cursors, sharing roles, link sharing under policy, comments, suggestions, version checkpoints, named versions, restore, notifications, PDF/DOCX/HTML/text export, DOCX/HTML import, images, tables |
| Could | Templates beyond a small built-in set, headers and footers, page numbers, table of contents, footnotes, outline, spell-check, OAuth, workspace admin policies, public groups, access requests, page setup, print layout |
| Won't (this program) | E2EE, native apps, slides/sheets, plugin platform, SAML/SCIM |

Must items are V1. Should items are V2–V4. Could items are V4 where noted in the roadmap, otherwise later. The roadmap is the delivery sequence: [ROADMAP.md](../09-roadmap/ROADMAP.md).

## 9. Feature inventory

Each feature lists purpose, stories, acceptance, and requirement pointers. Detailed stimuli and responses are in the [SRS](SRS.md). Permissions use the roles in section 10.

### 9.1 Accounts and identity

**Purpose.** A person has one account, can prove control of an email address, and can end sessions.

**Stories.**

- As a new user, I register with email, display name, and password so I can own documents.
- As a user, I verify my email before I create documents.
- As a user, I log in, refresh a session, and log out on this device or all devices.
- As a user, I reset my password through a time-limited email link.
- As a user, I edit my display name and avatar.
- As a user, I see active sessions and revoke one.
- As a user, I delete my account and understand what happens to documents I own.

**Acceptance.**

- Duplicate email registration is rejected.
- Unverified users can log in only to a verification-required state and cannot create documents.
- Refresh tokens rotate. Reuse of an old refresh token revokes that session family.
- Password reset invalidates existing refresh sessions.
- Avatar uploads accept PNG, JPEG, and WebP within the size limit and reject other types.
- Account deletion is confirmed by password and is irreversible after the grace window.

**SRS.** `FR-AUTH-001` through `FR-AUTH-012`.

**OAuth.** Optional. Specified so a later version can add Google and GitHub without changing the user primary key. Not required for MVP. `FR-AUTH-013`.

### 9.2 Document creation and management

**Purpose.** Users organize files in a workspace.

**Stories.**

- As an editor-capable member, I create a blank document or a document from a template.
- As an owner or editor, I rename, duplicate, move, star, trash, and restore a document.
- As an owner, I purge a trashed document.
- As a member, I browse recent and starred documents, sort, filter, and search by title.
- As any role with access, I open a document and see a loading state, then content, or a specific error if I lack access or the document is missing.

**Acceptance.**

- New documents have a unique id, an owner, a workspace, a default title "Untitled document", and an empty valid editor document.
- Duplicate copies content snapshot, not permission grants, except the duplicating user becomes owner.
- Trash is soft delete. Restore returns the document to its previous folder if that folder still exists, otherwise the workspace root.
- Purged documents and their collaborative bytes, comments, and attachments are deleted according to the retention rules.
- Search in V1 matches title. Full-text body search is a later enhancement (`FR-ORG-010`).

**SRS.** `FR-DOC-001` through `FR-DOC-014`, `FR-ORG-001` through `FR-ORG-009`.

### 9.3 Rich-text editor

**Purpose.** A document is more than plain text. Formatting and structure round-trip through collaboration, versions, and supported export formats as defined in the fidelity matrix.

**Must formatting.** Bold, italic, underline, strikethrough, headings (levels 1–3 in V1; 4–6 in V4), paragraphs, alignment, ordered and unordered lists, links, undo, redo, cut/copy/paste, select all, keyboard shortcuts, word and character count, clear formatting.

**Should formatting.** Font family from an allowed list, font size from an allowed list, text color, highlight, line spacing, paragraph spacing, indent, nested lists, checklists, blockquotes, horizontal rules, inline code, tables, images with alt text, find and replace.

**Could formatting.** Page breaks, captions, headers, footers, page numbers, footnotes, table of contents, outline pane.

**Acceptance.**

- Formatting commands apply to the current selection or the stored marks at the caret.
- Undo and redo follow the editor history for local changes and remain usable while remote updates arrive. Remote updates do not wipe the local undo stack of unrelated changes. Where the editor integration cannot preserve an undo step, the step is dropped and the document still converges.
- Paste from a browser clipboard accepts plain text and HTML mapped into the schema. Unsupported HTML is dropped, not executed.
- Find and replace operates on accepted text. Matches inside a pending deletion suggestion are visible in suggestion mode.
- Word count uses a Unicode-aware split on whitespace for accepted text. Pending insertions count only in suggestion mode. The exact algorithm is `FR-EDIT-030`.
- Browser refresh restores the last durably saved collaborative state. Unacknowledged local updates that exist only in memory can be lost. Updates written to IndexedDB and not yet acknowledged are retried after load (V2). V1 retries the in-flight batch if the page did not fully unload.

**SRS.** `FR-EDIT-001` through `FR-EDIT-036`.

### 9.4 Real-time collaboration

**Purpose.** Several people edit one document and converge without last-write-wins over the whole file.

**Stories.**

- As an editor, I see other editors' carets, selections, names, and colors.
- As an editor, I keep typing through a short disconnect and sync when the socket returns.
- As a viewer, I see live content and presence and cannot change content.
- As an owner, revoking an editor disconnects that editor and stops their updates.

**Acceptance.**

- Concurrent inserts at the same position both survive in a deterministic order defined by the CRDT.
- Concurrent formatting of overlapping ranges merges so each mark applies to the characters that still exist.
- A joining client receives the current compacted snapshot plus updates after that snapshot and reaches the same state as connected peers.
- The server acknowledges an update only after the update row is committed.
- Duplicate delivery of the same client update id is stored once and acknowledged with the original sequence.
- A collaboration server restart does not lose committed updates. Clients reconnect and sync.
- Documents larger than the configured maximum are rejected for new inserts that would exceed the cap. The cap is a product setting, default 2 MB of compacted Yjs state, overridable by deployment config.

**SRS.** `FR-COLLAB-001` through `FR-COLLAB-018`.

### 9.5 Sharing and permissions

**Purpose.** Access is explicit.

**Stories.**

- As an owner, I grant viewer, commenter, or editor to a user or to an email invite.
- As an owner, I create a link with a role, optional expiry, and I can revoke it.
- As an owner, I transfer ownership.
- As an administrator, I can turn off link sharing for the workspace.
- As a user without access, I can request access if the owner enabled requests. Default is enabled.

**Acceptance.**

- A user with no grant, no valid link, and no workspace-wide grant receives 404 on document metadata so existence is not leaked, except when they hold a previously valid link token and then receive 403 after revoke. Open question OQ-2 records the default.
- Editors cannot change permissions. Owners can. Workspace admins can change permissions for documents in the workspace (product rule BR-SHARE-004).
- Folder permissions, when present, grant access to documents in that folder and descendant folders. A document grant can only raise access, not lower a folder grant, in V3+. V1 has no folder ACL. V1 is direct document ACL only.
- Every share, revoke, transfer, and link create writes an audit event.

**SRS.** `FR-SHARE-001` through `FR-SHARE-016`.

### 9.6 Comments

**Purpose.** Discussion stays attached to a range of text.

**Stories.**

- As a commenter or editor, I create a thread on a selection, reply, edit my own comment, delete my own comment, and resolve a thread.
- As a mentioned user with access, I receive a notification.
- As a reader, I see an orphan thread if the anchor text was deleted, labeled as detached, until someone resolves it.

**Acceptance.**

- Anchors use CRDT relative positions so inserts around the range move the anchor.
- Comment bodies are plain text with mention tokens. They are not a second rich-text surface in V3.
- Viewers can read comments and cannot create them.
- Resolving does not delete history.

**SRS.** `FR-CMT-001` through `FR-CMT-012`.

### 9.7 Suggestion mode

**Purpose.** A commenter or editor proposes inserts, deletes, and formatting without those changes becoming accepted text until an editor accepts them.

**Stories.**

- As a commenter, I turn on suggestion mode and my inserts and deletes are attributed to me.
- As an editor or owner, I accept or reject one suggestion or all pending suggestions.
- As a viewer, I see accepted text. Suggestion marks are hidden.

**Acceptance.**

- Pending insertions are visible to editors and commenters and are excluded from the accepted-text projection used for viewer rendering, word count in viewing mode, and export default.
- Export can optionally include suggestions as revisions in DOCX. PDF export uses the accepted projection unless the user selects "include suggestions".
- Two people can suggest in the same paragraph. Accepting one does not discard the other except where the accepted edit deletes the anchored characters. In that case the surviving suggestion becomes detached and remains rejectable.

**SRS.** `FR-SUG-001` through `FR-SUG-010`.

### 9.8 Version history

**Purpose.** Users can return to an earlier checkpoint without storing every keystroke as a full document copy.

**Stories.**

- As an editor, I see automatic checkpoints and their authors and times.
- As an editor, I name the current document state.
- As an editor, I preview and compare two versions.
- As an owner or editor, I restore a version after confirmation.

**Acceptance.**

- Automatic checkpoints are created by policy, not per character. Default policy: a checkpoint at most every 5 minutes of activity or 100 persisted updates, whichever comes first, and only if the document changed.
- Named versions are retained until an owner deletes the name. The underlying snapshot is kept while any version references it.
- Restore is a new collaborative epoch. Connected clients replace local state with the restored snapshot. Updates from the previous epoch are rejected.
- Restore does not delete comment threads. Threads whose anchors no longer resolve are marked detached.

**SRS.** `FR-VER-001` through `FR-VER-010`.

### 9.9 Import and export

**Purpose.** Users move documents in and out of CollabDocs.

**Acceptance.**

- Export formats: PDF, DOCX, HTML, plain text.
- Import formats: DOCX, HTML.
- Jobs are asynchronous. The UI shows queued, running, succeeded, and failed.
- Failed jobs can be retried. A retry creates a new job.
- Download requires the same role as export: viewer or higher.
- Files over the size limit fail validation before queueing.

**Fidelity.** See [HLD import/export](../02-architecture/HLD.md#10-import-and-export) and SRS `FR-EXP-001` through `FR-EXP-012`.

### 9.10 Productivity features

| Feature | Priority | Version | Notes |
| --- | --- | --- | --- |
| Built-in templates (blank, letter, meeting notes, project brief) | Should | V4 | System templates ship as snapshots |
| Workspace templates | Could | After V4 | |
| Page setup (size, margins, orientation) | Could | V4 | Stored as document settings, applied at export and print |
| Headers, footers, page numbers | Could | V4 | Export and print. Not a live paginated canvas in the editor |
| Table of contents | Could | V4 | Generated from headings |
| Footnotes | Could | After V4 | Requires schema migration |
| Document outline | Should | V4 | Derived from headings, click to scroll |
| Spell-check | Could | V4 | Browser native spell-check first. No custom dictionary in this program |
| Smart links | Could | V4 | Paste of an http(s) URL becomes a link. No unfurl service in V4 |
| Notifications | Should | V3 | In-app. Email for mentions and share invites |
| Activity history | Should | V3 | Audit-derived document activity for members with access |
| Keyboard access | Must | V1 and ongoing | SRS accessibility section |
| Tablet and phone layout | Should | V4 | Editing supported. Some toolbar items move into menus |
| Printing | Should | V4 | Browser print of accepted HTML projection |
| Workspace organization | Must for personal; Should for multi-member | V1 personal, V3 multi-member admin | |
| Cross-document search | Should for titles; Could for body | V1 title, later body | |

## 10. Permission model (product rules)

Roles, from lowest to highest: `viewer`, `commenter`, `editor`, `owner`.

| Action | viewer | commenter | editor | owner |
| --- | --- | --- | --- | --- |
| Read content and comments | yes | yes | yes | yes |
| Export and print | yes | yes | yes | yes |
| Comment, reply, resolve own thread | no | yes | yes | yes |
| Suggest | no | yes | yes | yes |
| Accept or reject suggestions | no | no | yes | yes |
| Edit accepted content | no | no | yes | yes |
| Rename, move, duplicate, star | no | no | yes | yes |
| Trash and restore | no | no | yes | yes |
| Purge, share, change roles, links, transfer | no | no | no | yes |
| Delete any comment in the document | no | no | no | yes |

Workspace roles: `owner`, `admin`, `member`, `guest`.

- Workspace owner and admin may share and revoke document access inside the workspace.
- Members create documents and receive owner role on documents they create.
- Guests access only documents shared with them.
- A personal workspace is created at registration. The registering user is workspace owner.

Effective document role is the maximum of: direct user grant, accepted email invite for the verified email, valid share link role redeemed into a grant, and workspace-admin override. Folder inheritance is off until the version that implements `FR-SHARE-014`.

## 11. Non-functional product expectations

Measurable engineering targets are in [NFR and capacity](../07-quality/NFR_AND_CAPACITY.md). Product-level expectations:

- The UI names four save states: editing locally, saving, saved, and save failed.
- "Saved" means the server committed the user's pending updates.
- The product remains usable for reading a document already open if collaboration sync pauses, with a visible disconnected state.
- The interface meets WCAG 2.2 AA for the flows listed in the SRS accessibility section.
- Users can complete V1 editing with keyboard only.

## 12. Account and document deletion policy

**Proposed default, confirm before implementation (OQ-1).**

- Account deletion requires the current password and the literal confirmation string `DELETE`.
- The account enters `pending_deletion` for 7 days. Login cancels deletion.
- After 7 days, a worker anonymizes the user: display name becomes "Deleted user", email is released, password and sessions are removed, avatar object is deleted.
- Documents owned solely by that user are trashed and purged with the account.
- Documents owned by that user in a multi-member workspace transfer ownership to the workspace owner before purge of the user record. If the deleter is the workspace owner and other members exist, deletion is blocked until ownership of the workspace is transferred.
- Comments and suggestions remain with author displayed as "Deleted user".

## 13. MVP definition

MVP is roadmap V1:

- Email registration, verification, login, logout, refresh, password reset, profile name.
- Personal workspace, folders, document create, rename, duplicate, move, trash, restore, star, recent, title search.
- Rich-text Must list, images deferred to V4, tables deferred to V4.
- Autosave through the same update log used later for collaboration.
- Recovery of committed content after refresh and after API restart.
- Docker Compose development with no host install of Java, Node, PostgreSQL, or Redis.

MVP excludes live multi-user cursors, comments, suggestions, and export. Those start at V2, V3, and V4.

## 14. Roadmap summary

| Version | Outcome |
| --- | --- |
| V1 | Single-user editor, accounts, organization, durable autosave |
| V2 | Multi-user CRDT collaboration, presence, sharing |
| V3 | Comments, suggestions, versions, notifications, audit |
| V4 | Tables, images, templates, layout, import/export, accessibility depth |
| V5 | Horizontal scale, security hardening, backups, load tests, production deployment |

Detail: [ROADMAP.md](../09-roadmap/ROADMAP.md).

## 15. Risks and assumptions

| ID | Type | Statement |
| --- | --- | --- |
| A-1 | Assumption | Users have a modern evergreen browser: current Chrome, Firefox, Edge, or Safari. Internet Explorer is unsupported. |
| A-2 | Assumption | Email delivery exists in production. Development uses Mailpit inside Docker. |
| A-3 | Assumption | One region is enough for the first production deployment. |
| A-4 | Assumption | Document body search can wait until after title search. |
| R-1 | Risk | Suggestion marks inside a CRDT are easy to get wrong during accept/reject. Mitigation: dedicated test corpus before V3 exit. |
| R-2 | Risk | Export fidelity will not match every editor construct. Mitigation: publish the fidelity matrix and fail the job with a warning list rather than silently dropping content without a report. |
| R-3 | Risk | A JVM port of the CRDT library does not exist. Mitigation: Java stores and relays opaque updates; a small compactor container applies them with Yjs. |
| R-4 | Risk | Large documents degrade editor latency. Mitigation: hard size cap and load tests at the cap. |
| OQ-1 | Open | Is the 7-day account deletion grace acceptable, or must deletion be immediate for some workspaces? Default: 7 days. |
| OQ-2 | Open | Should a missing document and a forbidden document both return 404? Default: 404 when the caller has never been granted access; 403 when a grant or link was revoked. |
| OQ-3 | Open | May workspace admins read all documents without a direct grant? Default: yes, admins have editor-equivalent access for support and policy, and the access is audited. Confirm before V3. |
| OQ-4 | Open | Are commenters allowed to accept suggestions? Default: no. |
| OQ-5 | Open | Should link sharing allow the editor role? Default: no. Links max role is commenter unless a workspace policy explicitly allows editor links. |

## 16. Requirement versus design

The feature behavior in this PRD is required. The following are design choices recorded so implementation stays aligned, and they may change only through an ADR:

- Java 21 and Spring Boot for API, collaboration gateway, and workers.
- React, TypeScript, and Vite for the SPA.
- Yjs with ProseMirror bindings as the collaborative document.
- PostgreSQL for metadata and the update log.
- Redis for presence, fan-out, locks, and rate limits.
- S3-compatible object storage for blobs.
- All runtimes in containers.
