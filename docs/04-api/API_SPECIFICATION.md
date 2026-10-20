# CollabDocs API Specification

| Field | Value |
| --- | --- |
| Base URL | `/api/v1` |
| Audience | API, frontend, and QA agents |
| Machine contract | [openapi.yaml](openapi.yaml) |
| Depends on | [SRS](../01-product/SRS.md), [Database](../03-data/DATABASE_DESIGN.md) |

If this narrative and `openapi.yaml` disagree, fix both in the same change. The YAML is what contract tests load.

## 1. Conventions

### 1.1 Authentication

Protected routes require:

```http
Authorization: Bearer <access-token>
X-Correlation-Id: <optional ulid>
X-CollabDocs-Request: 1
```

`X-CollabDocs-Request` is required on every API call from the browser, including login. Requests without it receive `400 MISSING_CLIENT_HEADER`. This blocks cross-site form posts. Server-to-server jobs are not exposed on this API.

Refresh uses the cookie `cd_refresh` and still requires the custom header.

### 1.2 Error body

See SRS section 8. Every error includes `error.code`, `error.message`, `error.details`, and `error.correlationId`.

### 1.3 Pagination

List routes accept `limit` (default 50, max 100) and `cursor` (opaque). Responses contain `items` and `nextCursor`. A null cursor means the end. Cursors are not page numbers.

### 1.4 Idempotency

`POST` content updates are idempotent on `(documentId, clientId, updateId)`. Export create is idempotent on `Idempotency-Key` for 24 hours. Other POST routes create new resources unless a section says otherwise. Repeating them creates duplicates or returns the existing open row where the SRS says so (access requests).

### 1.5 Rate limits

Limits are per minute unless noted. Exceeded calls return `429` and `Retry-After`.

| Route group | Limit |
| --- | --- |
| `POST /auth/login` | 10 per IP |
| `POST /auth/register` | 5 per hour per IP |
| `POST /auth/password/forgot` | 5 per hour per IP |
| `POST /auth/verify-email/resend` | 3 per hour per user |
| Other `/auth/*` | 30 per IP |
| `POST /documents/{id}/content-updates` | 60 per user per document, burst 120 |
| `POST /documents/{id}/exports` | 10 per hour per user |
| `POST /imports` | 10 per hour per user |
| Other authenticated routes | 300 per user |

### 1.6 Common status codes

`200` success with body, `201` created, `202` accepted for async work, `204` success without body, plus the error table in the SRS.

## 2. Resource shapes

```json
{
  "id": "018f2c1a-7c31-7b2a-9a10-2d5e6f7a8b9c",
  "workspaceId": "018f2c1a-1111-7b2a-9a10-2d5e6f7a8b9c",
  "folderId": null,
  "ownerId": "018f2c1a-2222-7b2a-9a10-2d5e6f7a8b9c",
  "title": "Untitled document",
  "status": "active",
  "epoch": 1,
  "schemaVersion": 1,
  "wordCount": 0,
  "effectiveRole": "owner",
  "starred": false,
  "createdAt": "2026-10-19T12:00:00Z",
  "updatedAt": "2026-10-19T12:00:00Z",
  "lastEditedAt": null
}
```

User public fields: `id`, `displayName`, `avatarUrl`. Avatar URL is an API path, not a storage URL. Email is returned only on `/me`.

## 3. Authentication endpoints

### POST /api/v1/auth/register

- **Purpose.** Create an account, personal workspace, and session. `FR-AUTH-001`.
- **Auth.** None.
- **Body.** `{ "email", "displayName", "password" }`.
- **Response.** `201` `{ "accessToken", "expiresIn": 900, "user", "workspace" }`. Sets `cd_refresh`.
- **Errors.** `400` validation, `409 EMAIL_TAKEN`, `429`.
- **Idempotency.** Not idempotent. Duplicate email is `409`.
- **Example.**

```json
{ "email": "priya@example.com", "displayName": "Priya", "password": "correct-horse-battery" }
```

### POST /api/v1/auth/login

- **Purpose.** `FR-AUTH-004`.
- **Body.** `{ "email", "password" }`.
- **Response.** `200` same session shape as register.
- **Errors.** `401 INVALID_CREDENTIALS`, `403 ACCOUNT_DISABLED`.

### POST /api/v1/auth/refresh

- **Purpose.** Rotate refresh token. `FR-AUTH-005`.
- **Auth.** Cookie `cd_refresh`.
- **Response.** `200` `{ "accessToken", "expiresIn": 900 }`.
- **Errors.** `401 SESSION_REUSED` or `401 INVALID_CREDENTIALS`.

### POST /api/v1/auth/logout

- **Purpose.** Revoke the current refresh session. `FR-AUTH-006`.
- **Auth.** Bearer, so the session id claim is known.
- **Response.** `204`. Clears cookie.

### POST /api/v1/auth/logout-all

- **Purpose.** Revoke every refresh session for the user.
- **Auth.** Bearer.
- **Response.** `204`.

### POST /api/v1/auth/verify-email

- **Purpose.** `FR-AUTH-002`.
- **Auth.** None. The token is the secret.
- **Body.** `{ "token" }`.
- **Response.** `200` `{ "status": "active" }`.
- **Errors.** `400 TOKEN_INVALID`.

### POST /api/v1/auth/verify-email/resend

- **Purpose.** `FR-AUTH-003`.
- **Auth.** Bearer.
- **Response.** `202`.
- **Errors.** `429`.

### POST /api/v1/auth/password/forgot

- **Purpose.** `FR-AUTH-007`.
- **Auth.** None.
- **Body.** `{ "email" }`.
- **Response.** Always `202`.

### POST /api/v1/auth/password/reset

- **Purpose.** Set a new password and revoke sessions.
- **Body.** `{ "token", "password" }`.
- **Response.** `204`.
- **Errors.** `400 TOKEN_INVALID` or validation.

### GET /api/v1/auth/sessions

- **Purpose.** `FR-AUTH-010`.
- **Auth.** Bearer.
- **Response.** `200` `{ "items": [{ "id", "createdAt", "lastUsedAt", "userAgent", "ipPrefix", "current": true }] }`.

### DELETE /api/v1/auth/sessions/{sessionId}

- **Purpose.** Revoke one session owned by the caller.
- **Response.** `204`.
- **Errors.** `404` if the session is not the caller's.

## 4. Profile

### GET /api/v1/me

- **Auth.** Bearer.
- **Response.** `200` `{ "id", "email", "displayName", "emailVerified", "status", "avatarUrl" }`.

### PATCH /api/v1/me

- **Purpose.** Update display name. `FR-AUTH-008`.
- **Body.** `{ "displayName" }`.
- **Response.** `200` user.

### POST /api/v1/me/avatar

- **Purpose.** `FR-AUTH-009`.
- **Body.** `multipart/form-data` field `file`.
- **Response.** `200` `{ "avatarUrl" }`.
- **Errors.** `400 UNSUPPORTED_IMAGE`, `413`.

### DELETE /api/v1/me/avatar

- **Response.** `204`.

### DELETE /api/v1/me

- **Purpose.** Schedule account deletion. `FR-AUTH-011`.
- **Body.** `{ "password", "confirmation": "DELETE" }`.
- **Response.** `202` `{ "purgeAfter" }`.
- **Errors.** `409 WORKSPACE_OWNERSHIP`, `403` bad password.

### GET /api/v1/users/search

- **Purpose.** Find users by email or display name prefix inside the caller's workspaces, for sharing. Minimum query length 2.
- **Query.** `q`.
- **Response.** `200` `{ "items": [{ "id", "displayName", "email" }] }` at most 10.
- **Authz.** Any authenticated verified user. Results are limited to users who share a workspace with the caller, plus an exact email match so an invite can target someone outside the workspace. Exact email match returns `id` only when that user exists. It does not return a list for partial emails outside the workspace.

## 5. Workspaces

### GET /api/v1/workspaces

- **Purpose.** List workspaces the caller belongs to.
- **Response.** `200` `{ "items": [{ "id", "name", "role", "linkSharing" }] }`.

### POST /api/v1/workspaces

- **Purpose.** Create a workspace. Caller becomes owner.
- **Body.** `{ "name" }`.
- **Response.** `201` workspace.
- **Version.** V3. V1 creates only the personal workspace during registration. The route exists and returns `403 WORKSPACE_CREATE_DISABLED` until V3 configuration enables it.

### GET /api/v1/workspaces/{workspaceId}

- **Authz.** Member.
- **Response.** `200` workspace plus `role`.

### PATCH /api/v1/workspaces/{workspaceId}

- **Authz.** Workspace owner or admin.
- **Body.** `{ "name", "linkSharing" }` either field optional.
- **Response.** `200` workspace.

### GET /api/v1/workspaces/{workspaceId}/members

- **Authz.** Member.
- **Response.** Paginated `{ "id", "displayName", "email", "role" }`.

### POST /api/v1/workspaces/{workspaceId}/members

- **Authz.** Admin.
- **Body.** `{ "email", "role": "member" | "admin" | "guest" }`.
- **Response.** `201` membership or pending invite.
- **Version.** Multi-member behavior is V3. The personal workspace owner is the only member in V1.

### PATCH /api/v1/workspaces/{workspaceId}/members/{userId}

- **Authz.** Admin. Cannot demote the last owner.
- **Body.** `{ "role" }`.
- **Response.** `200`.

### DELETE /api/v1/workspaces/{workspaceId}/members/{userId}

- **Authz.** Admin, or the user removing themselves when they are not the last owner.
- **Response.** `204`.

## 6. Folders

### GET /api/v1/workspaces/{workspaceId}/folders

- **Query.** `parentId` optional. Empty means root.
- **Authz.** Member.
- **Response.** `200` `{ "items": [{ "id", "name", "parentId" }] }`.

### POST /api/v1/workspaces/{workspaceId}/folders

- **Authz.** Member.
- **Body.** `{ "name", "parentId": null }`.
- **Response.** `201` folder.
- **Errors.** `422 FOLDER_DEPTH`, `404` parent.

### PATCH /api/v1/folders/{folderId}

- **Authz.** Member of the workspace.
- **Body.** `{ "name", "parentId" }`.
- **Response.** `200`.
- **Errors.** `422` cycle or depth.

### DELETE /api/v1/folders/{folderId}

- **Purpose.** Trash the subtree. `FR-ORG-002`.
- **Response.** `200` `{ "trashBatchId" }`.

### POST /api/v1/folders/{folderId}/restore

- **Purpose.** Restore the trash batch that contains this folder.
- **Response.** `200`.

## 7. Documents

### GET /api/v1/workspaces/{workspaceId}/documents

- **Query.** `folderId`, `sort` (`updatedAt`, `title`, `createdAt`), `direction` (`asc`, `desc`), `limit`, `cursor`.
- **Authz.** Member. Results are filtered to documents the caller can access unless admin implicit access is on.
- **Response.** Paginated document summaries.

### POST /api/v1/workspaces/{workspaceId}/documents

- **Purpose.** Create a blank document or from a template. `FR-DOC-001`.
- **Authz.** Verified member.
- **Body.** `{ "title": "Untitled document", "folderId": null, "templateId": null }`.
- **Response.** `201` document.
- **Errors.** `403 EMAIL_UNVERIFIED`, `404` folder or template.

### GET /api/v1/documents/{documentId}

- **Purpose.** Metadata. `FR-DOC-002`.
- **Authz.** Any effective role.
- **Response.** `200` document resource.
- **Errors.** `404` or `403` per OQ-2.

### PATCH /api/v1/documents/{documentId}

- **Purpose.** Rename. `FR-DOC-003`.
- **Authz.** Editor.
- **Headers.** `If-Match` optional optimistic `version`.
- **Body.** `{ "title" }`.
- **Response.** `200` document.
- **Errors.** `409 VERSION_CONFLICT`.

### POST /api/v1/documents/{documentId}/duplicate

- **Purpose.** `FR-DOC-005`.
- **Authz.** Viewer or higher.
- **Body.** `{ "workspaceId", "folderId": null, "title": null }`.
- **Response.** `201` new document.

### POST /api/v1/documents/{documentId}/move

- **Purpose.** `FR-DOC-004`.
- **Authz.** Editor.
- **Body.** `{ "folderId": null }`.
- **Response.** `200` document.
- **Errors.** `422` cross-workspace. Move stays in the document's workspace. Changing workspace is duplicate, not move.

### DELETE /api/v1/documents/{documentId}

- **Purpose.** Trash. `FR-DOC-006`.
- **Authz.** Editor.
- **Response.** `200` `{ "status": "trashed", "purgeAfter" }`.

### POST /api/v1/documents/{documentId}/restore

- **Authz.** Editor.
- **Response.** `200` document.
- **Errors.** `409` if purged.

### DELETE /api/v1/documents/{documentId}/purge

- **Authz.** Owner.
- **Response.** `204`.
- **Errors.** `409` if status is not trashed. Owners trash first, then purge, so purge is deliberate.

### POST /api/v1/documents/{documentId}/star

- **Authz.** Any role with access.
- **Response.** `204`.

### DELETE /api/v1/documents/{documentId}/star

- **Response.** `204`.

### GET /api/v1/workspaces/{workspaceId}/documents/recent

- **Response.** Up to 50 document summaries.

### GET /api/v1/workspaces/{workspaceId}/documents/starred

- **Response.** Paginated starred documents.

### GET /api/v1/workspaces/{workspaceId}/trash

- **Response.** Trashed documents and folders the caller can restore.

### GET /api/v1/workspaces/{workspaceId}/search

- **Query.** `q` required.
- **Purpose.** Title search. `FR-ORG-003`.
- **Response.** `{ "items": [document summary] }`.

### POST /api/v1/documents/{documentId}/content-updates

- **Purpose.** V1 durable autosave and any non-socket writer. `FR-COLLAB-003`.
- **Authz.** Editor for `intent = edit`. Commenter for `intent = suggest`.
- **Body.**

```json
{
  "clientId": "018f2c1b-1111-7b2a-9a10-2d5e6f7a8b9c",
  "updateId": "018f2c1b-2222-7b2a-9a10-2d5e6f7a8b9c",
  "epoch": 1,
  "intent": "edit",
  "payloadBase64": "AAECAw=="
}
```

- **Response.** `200` `{ "epoch": 1, "seq": 42, "duplicate": false }`.
- **Errors.** `409 STALE_EPOCH`, `403`, `413`, `409 DOCUMENT_NOT_EDITABLE`.
- **Idempotency.** Duplicate `updateId` returns the original seq and `duplicate: true`.

### GET /api/v1/documents/{documentId}/content-state

- **Purpose.** Bootstrap and V1 reload. `FR-COLLAB-006`.
- **Authz.** Viewer or higher.
- **Query.** `sinceSeq` optional, default uses the latest snapshot boundary.
- **Response.**

```json
{
  "epoch": 1,
  "schemaVersion": 1,
  "snapshotBase64": "AAEC",
  "throughSeq": 40,
  "updates": [{ "seq": 41, "payloadBase64": "AA==" }]
}
```

- **Side effect.** Upserts `recent_documents`.

### POST /api/v1/workspaces/{workspaceId}/documents/from-template

- **Purpose.** Alias of create with a required `templateId`. Included so the template UI has a stable URL.
- **Body.** `{ "templateId", "folderId": null, "title": null }`.
- **Response.** `201` document.

## 8. Sharing

### GET /api/v1/documents/{documentId}/permissions

- **Authz.** Any role with access sees the list. Email invites are visible to owners and admins only. Other roles see accepted users and roles, not pending emails.
- **Response.** `{ "items": [{ "id", "principalType", "user", "email", "role", "expiresAt" }] }`.

### POST /api/v1/documents/{documentId}/permissions

- **Purpose.** Grant or invite. `FR-SHARE-001`.
- **Authz.** `canAdminister`.
- **Body.** `{ "userId": null, "email": "omar@example.com", "role": "editor" }`. Exactly one of `userId` or `email`.
- **Response.** `201` permission.
- **Errors.** `422` if role is `owner`. Ownership uses transfer.

### PATCH /api/v1/documents/{documentId}/permissions/{permissionId}

- **Body.** `{ "role" }`.
- **Authz.** `canAdminister`.
- **Errors.** `409` if the row is the owner grant.

### DELETE /api/v1/documents/{documentId}/permissions/{permissionId}

- **Purpose.** Revoke. `FR-SHARE-003`.
- **Response.** `204`.
- **Errors.** `409` on the owner grant.

### POST /api/v1/documents/{documentId}/transfer-ownership

- **Body.** `{ "userId" }`.
- **Authz.** Current owner. Workspace admins do not transfer ownership unless they are the owner. This is stricter than general share power.
- **Response.** `200` document.

### POST /api/v1/documents/{documentId}/share-links

- **Authz.** `canAdminister`.
- **Body.** `{ "role": "viewer", "expiresAt": null }`.
- **Response.** `201` `{ "id", "token", "role", "expiresAt", "url" }`. `token` is returned only on create.
- **Errors.** `403 LINK_SHARING_DISABLED`, `422 ROLE_ABOVE_POLICY`.

### GET /api/v1/documents/{documentId}/share-links

- **Authz.** `canAdminister`.
- **Response.** Items without raw tokens. Includes `revokedAt` and `expiresAt`.

### DELETE /api/v1/share-links/{linkId}

- **Authz.** `canAdminister` on the link's document.
- **Response.** `204`.

### POST /api/v1/share-links/{token}/redeem

- **Auth.** Bearer.
- **Purpose.** Create a grant for the caller. `FR-SHARE-005`.
- **Response.** `200` `{ "documentId", "role" }`.
- **Errors.** `404` unknown token, `410 LINK_EXPIRED`, `403` revoked.

### POST /api/v1/documents/{documentId}/access-requests

- **Auth.** Bearer. Caller must not already have access.
- **Response.** `201` or `200` existing open request `{ "id", "status": "open" }`.
- **Note.** This route returns `404` if the document does not exist and `201` if it exists, which reveals existence to any logged-in user who can guess the id. **Default changed for this route only:** return `201` with a generic request only when the document exists; for a missing id return `404`. Guessing UUIDv7 is the accepted residual risk recorded in the threat model. Do not add an unauthenticated existence check.

### POST /api/v1/access-requests/{requestId}/approve

- **Authz.** `canAdminister`.
- **Body.** `{ "role": "viewer" }`.
- **Response.** `200`.

### POST /api/v1/access-requests/{requestId}/deny

- **Authz.** `canAdminister`.
- **Response.** `200`.

## 9. Comments

### GET /api/v1/documents/{documentId}/threads

- **Authz.** Viewer.
- **Query.** `status` optional `open` or `resolved`.
- **Response.** Threads with nested comments, omitting soft-deleted comment bodies.

### POST /api/v1/documents/{documentId}/threads

- **Authz.** Commenter.
- **Body.**

```json
{
  "body": "Consider rephrasing this.",
  "quotedText": "quick brown",
  "anchorStartBase64": "QQ==",
  "anchorEndBase64": "Qg==",
  "mentionUserIds": []
}
```

- **Response.** `201` thread.

### POST /api/v1/threads/{threadId}/comments

- **Authz.** Commenter on the parent document.
- **Body.** `{ "body", "mentionUserIds": [] }`.
- **Response.** `201` comment.

### PATCH /api/v1/comments/{commentId}

- **Authz.** Author, inside 15 minutes.
- **Body.** `{ "body" }`.
- **Errors.** `409 COMMENT_LOCKED`.

### DELETE /api/v1/comments/{commentId}

- **Authz.** Author or document owner.
- **Response.** `204`.

### POST /api/v1/threads/{threadId}/resolve

- **Authz.** Commenter.
- **Response.** `200` thread.

### POST /api/v1/threads/{threadId}/reopen

- **Authz.** Commenter.
- **Response.** `200` thread.

### PATCH /api/v1/threads/{threadId}

- **Purpose.** Client reports `anchorState` of `bound` or `detached`.
- **Authz.** Any role with access. This does not change comment text.
- **Body.** `{ "anchorState": "detached" }`.

## 10. Suggestions

### GET /api/v1/documents/{documentId}/suggestions

- **Authz.** Viewer sees status rows. The text is in the document.
- **Query.** `status` default `pending`.
- **Response.** `{ "items": [{ "id", "type", "status", "author", "createdAt", "resolvedAt" }] }`.

### POST /api/v1/documents/{documentId}/suggestions

- **Purpose.** Register a suggestion id before or with the first marked update, so the review list can show authorship even before accept.
- **Authz.** Commenter.
- **Body.** `{ "id", "type": "insert" }`. Client-generated UUID.
- **Response.** `201`. Duplicate id by the same author returns `200`.

### POST /api/v1/documents/{documentId}/suggestions/{suggestionId}/accept

- **Authz.** Editor.
- **Body.** Same shape as a content update.
- **Response.** `200` `{ "suggestionStatus": "accepted", "seq": 50 }`.
- **Errors.** `409 SUGGESTION_RESOLVED`, `409 STALE_EPOCH`.

### POST /api/v1/documents/{documentId}/suggestions/{suggestionId}/reject

- **Authz.** Editor.
- **Body.** Content update that removes the suggestion.
- **Response.** `200`.

### POST /api/v1/documents/{documentId}/suggestions/accept-all

- **Authz.** Editor.
- **Body.** `{ "updates": [ content update plus suggestionId ] }`.
- **Response.** `200` `{ "accepted": 3 }`.
- **Failure.** Whole transaction rolls back.

### POST /api/v1/documents/{documentId}/suggestions/reject-all

- **Same contract** with reject semantics.

## 11. Versions

### GET /api/v1/documents/{documentId}/versions

- **Authz.** Viewer.
- **Response.** `{ "items": [{ "id", "kind", "label", "createdAt", "author" }] }`.

### POST /api/v1/documents/{documentId}/versions

- **Purpose.** Named checkpoint. `FR-VER-002`.
- **Authz.** Editor.
- **Body.** `{ "label" }`.
- **Response.** `201` version when the snapshot is ready, otherwise `202` `{ "requestId", "status": "pending" }`.

### GET /api/v1/documents/{documentId}/versions/{versionId}

- **Response.** Version metadata plus `snapshotId` and `byteSize`. Does not return raw bytes to the browser except through preview.

### GET /api/v1/documents/{documentId}/versions/{versionId}/preview

- **Purpose.** HTML preview. `FR-VER-004`.
- **Response.** `200` `text/html` sanitized fragment, or `202` if rendering is queued.
- **Authz.** Viewer.

### GET /api/v1/documents/{documentId}/versions/compare

- **Query.** `from` and `to` version ids.
- **Response.** `200` `{ "paragraphs": [{ "op": "equal|insert|delete", "text": "..." }] }`.

### POST /api/v1/documents/{documentId}/versions/{versionId}/restore

- **Authz.** Editor.
- **Body.** `{ "confirm": true }`.
- **Response.** `200` `{ "epoch": 2 }`.
- **Errors.** `400 CONFIRMATION_REQUIRED`.

### DELETE /api/v1/documents/{documentId}/versions/{versionId}

- **Purpose.** Delete a named label. Automatic versions cannot be deleted individually.
- **Authz.** Owner.
- **Response.** `204`.

## 12. Import and export

### POST /api/v1/workspaces/{workspaceId}/imports

- **Authz.** Verified member.
- **Body.** `multipart` field `file`, fields `folderId` and `format` (`docx` or `html`).
- **Response.** `202` `{ "id", "status": "queued" }`.
- **Errors.** `400` type mismatch, `413`.

### GET /api/v1/imports/{jobId}

- **Authz.** The user who created the job.
- **Response.** `{ "id", "status", "documentId", "report", "errorCode" }`.

### POST /api/v1/documents/{documentId}/exports

- **Authz.** Viewer.
- **Headers.** `Idempotency-Key` optional.
- **Body.** `{ "format": "pdf", "includeSuggestions": false }`.
- **Response.** `202` `{ "id", "status": "queued" }` or `200` if the idempotency key matches an existing job.

### GET /api/v1/exports/{jobId}

- **Authz.** Document reader.
- **Response.** `{ "id", "status", "format", "errorCode", "finishedAt" }`.

### GET /api/v1/exports/{jobId}/download

- **Authz.** Document reader at request time.
- **Response.** `200` file stream with `Content-Disposition: attachment`. `409` if the job is not succeeded.

## 13. Attachments

### POST /api/v1/documents/{documentId}/attachments

- **Authz.** Editor.
- **Body.** `multipart` field `file`, fields `altText`, `decorative`.
- **Limits.** 10 MB. PNG, JPEG, WebP, GIF.
- **Response.** `201` `{ "id", "mimeType", "byteSize", "width", "height" }`.

### GET /api/v1/documents/{documentId}/attachments/{attachmentId}

- **Authz.** Viewer.
- **Response.** Image bytes. `Cache-Control: private, max-age=300`.

### DELETE /api/v1/documents/{documentId}/attachments/{attachmentId}

- **Authz.** Editor.
- **Response.** `204`. The editor is expected to send a content update that removes the image node. The API does not edit Yjs on delete. Orphan attachments are purged with the document. A daily worker may delete attachments with `deleted_at` set.

## 14. Notifications

### GET /api/v1/notifications

- **Query.** `unread` boolean optional.
- **Response.** Paginated `{ "id", "type", "payload", "readAt", "createdAt" }`.

### POST /api/v1/notifications/{notificationId}/read

- **Response.** `204`.

### POST /api/v1/notifications/read-all

- **Response.** `204`.

## 15. Templates

### GET /api/v1/templates

- **Query.** `workspaceId` optional.
- **Response.** System templates plus templates for that workspace.
- **Auth.** Bearer. Workspace templates require membership.

## 16. Health

These routes are on each Java service and are not under `/api/v1`. Nginx does not expose them publicly except through the internal Docker network and a private admin listener. Local Compose publishes them on the service ports for debugging.

### GET /health/live

- **Response.** `200` `{ "status": "up" }` if the process is running.

### GET /health/ready

- **Response.** `200` `{ "status": "up", "checks": { "postgres": "up", "redis": "up" } }`.
- **Errors.** `503` if a required dependency is down. The compactor checks PostgreSQL only. The web container has no dependency check.

## 17. Internal routes

Not part of the public OpenAPI file.

| Method | URL | Caller |
| --- | --- | --- |
| POST | `http://compactor:8083/internal/project` | worker |
| POST | `http://compactor:8083/internal/encode` | worker |
| GET | `http://compactor:8083/health/live` | orchestrator |

## 18. Example error

```json
{
  "error": {
    "code": "STALE_EPOCH",
    "message": "The document was restored. Reload to continue.",
    "details": [{ "field": "epoch", "issue": "expected 2" }],
    "correlationId": "01JABCDE1234567890ABCDEF"
  }
}
```

## 19. Compatibility

Fields may be added to JSON responses without a version bump. Fields may not be removed or renamed inside `/api/v1`. Clients ignore unknown fields.
