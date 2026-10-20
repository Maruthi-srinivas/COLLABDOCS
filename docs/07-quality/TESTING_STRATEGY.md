# CollabDocs Testing Strategy

| Field | Value |
| --- | --- |
| Audience | QA and all implementation agents |
| Depends on | [SRS](../01-product/SRS.md), [Protocol](../04-api/COLLABORATION_PROTOCOL.md), [NFR](NFR_AND_CAPACITY.md) |

## 1. Goals

Tests prove the requirement ids they name. A change that alters sync, permissions, or retention is not done without the tests listed for that area. Flaky timing checks use deterministic clocks and injected delays, not arbitrary sleeps, except for end-to-end reconnect tests that bound the wait.

## 2. Layers

| Layer | Tooling | Owns |
| --- | --- | --- |
| Java unit | JUnit 5, Mockito | Permission ordering, token rotation logic, validators |
| Java integration | JUnit, Testcontainers PostgreSQL and Redis, MockWebServer for compactor | Repositories, services, API slices |
| Compactor unit | Node test runner | Projection, encode, schema migration |
| Frontend unit | Vitest, Testing Library | Toolbar commands, save status, protocol encode |
| API contract | The integration suite asserts status codes and JSON against OpenAPI examples | Drift between YAML and controllers |
| End-to-end | Playwright running in a container against Compose | Register, edit, reload |
| Collaboration | Testcontainers plus two simulated clients, and Playwright for cursors | Convergence and revoke |
| Load | k6 in a container | Targets in the NFR document |
| Security | Integration tests named in SECURITY.md plus dependency scan | IDOR, XSS sanitizer, SSRF sink |

Agents run the layer they can run inside Docker. They do not require a host JDK or Node.

## 3. Environments

| Environment | Data | Purpose |
| --- | --- | --- |
| Unit | None | Fast logic |
| Integration | Testcontainers, discarded | Real SQL and Redis |
| Compose e2e | Seed user and empty workspace | Browser flows |
| Staging | Anonymized or synthetic | Load and backup restore |
| Production | Customer data | Synthetic probes only, no destructive tests |

Seed passwords in tests are fixed fixtures in test resources, not production secrets.

## 4. Quality gates

A pull request merges when:

- Unit and integration tests for the touched module pass in CI.
- OpenAPI diff is reviewed if the YAML changes.
- Flyway migration applies on an empty database and on a database that has the previous version.
- Frontend lint and unit tests pass when `services/web` changes.
- New `FR-*` behavior has at least one automated test or a written manual script linked from the traceability matrix. Manual scripts are allowed for visual print layout only.

Release to staging additionally requires the e2e smoke suite. V5 production release requires the load and restore drills.

## 5. Requirement coverage rules

- Each Must requirement in V1 has an automated test before V1 exit.
- Permission tests use two users. The negative case is required.
- Collaboration tests assert database rows, not only pixels.
- A test that only mocks `ContentUpdateService` does not count as persistence coverage.

## 6. Functional test suites

### 6.1 Authentication

- Register, duplicate email, login, refresh, reuse of old refresh token revokes the family.
- Unverified user cannot create a document.
- Password reset revokes sessions.
- Logout puts the session on the denylist and the next API call with that access token returns 401.

### 6.2 Documents

- Create, rename conflict with stale `If-Match`, move, duplicate does not copy grants, trash, restore, purge.
- Search does not return another user's document.
- Folder cycle is rejected.

### 6.3 Editor

- Each formatting command in the V1 Must list changes the ProseMirror document as expected.
- Paste of HTML containing a script yields text without a script node.
- Reload after ack shows the text.

### 6.4 Import and export

- Fixture DOCX with a heading, a list, and a table imports and exports with those constructs present.
- HTML import with `img src=http://sink.invalid/x.png` does not connect to the sink and lists a warning.
- Download after revoke returns 403.

## 7. Collaboration test cases

### TC-COLLAB-001 Concurrent insert

1. Start two clients on one document, both welcomed at seq 0.
2. Both insert a character at position 0 without waiting.
3. Both eventually contain both characters.
4. `collab_updates` has two rows with distinct seq values.

### TC-COLLAB-002 Concurrent delete and insert

1. Document text is "abcd", acked.
2. Client A deletes "bc". Client B inserts "X" between b and c before receiving A's update.
3. Both converge to the same string. The test records the string. It must be identical on both clients and after a third client joins.

### TC-COLLAB-003 Concurrent formatting

1. Text "hello" is acked.
2. A bolds the whole word. B italicizes "ell".
3. The overlapping characters have both marks. The other characters have only bold.

### TC-COLLAB-004 Duplicate update

1. Send `sync.update` with id U1.
2. Send it again after ack.
3. One row exists. Second ack has `duplicate: true`. The peer receives one `sync.apply`.

### TC-COLLAB-005 Join catch-up

1. Produce 10 updates.
2. Compact through seq 6 if the compactor is running, or insert a snapshot test fixture through seq 6.
3. A new client welcome contains the snapshot and updates 7 through 10.
4. The new client's text matches a client that was online the whole time.

### TC-COLLAB-006 Lost connection

1. Client B disconnects.
2. Client A inserts "A" and receives an ack.
3. Client B inserts "B" offline into its local doc and queue.
4. B reconnects.
5. Both documents contain A and B.

### TC-COLLAB-007 Stale permission

1. B is an editor and connected.
2. Owner revokes B.
3. Within 5 seconds B's socket closes with 4403.
4. A further update from B is not stored. Use a second connection attempt if the first already closed.

### TC-COLLAB-008 Viewer write

1. Viewer connects with `intent=view`.
2. `sync.update` returns nack `FORBIDDEN`.
3. No new row in `collab_updates`.

### TC-COLLAB-009 Gateway restart

1. Ack an update.
2. Kill the gateway container.
3. Start it.
4. Client reconnects and the text is present.
5. A second update acks with a higher seq.

### TC-COLLAB-010 Two replicas

1. Run two gateway containers.
2. Connect client A to replica 1 and client B to replica 2.
3. A's update appears on B.
4. Seq values are unique and ordered.

### TC-COLLAB-011 Autosave failure

1. Stop PostgreSQL.
2. Client sends an update.
3. No ack arrives. UI state is error.
4. Start PostgreSQL.
5. Retry with the same update id stores one row.

### TC-COLLAB-012 Restore epoch

1. Two clients connected.
2. Restore a version.
3. Both receive `document.restored`.
4. An update with the old epoch is rejected.
5. The document matches the restored snapshot.

### TC-COLLAB-013 Large payload

1. Send a 300 KB decoded payload.
2. Expect nack or HTTP 413.
3. The previous text remains after the client reloads.

## 8. Suggestion and comment cases

- Accept and reject change the projection and the status row together. A forced database error on the status update rolls back the content insert. Simulate with a test that aborts the transaction.
- Two accept calls race. One wins. The other receives 409.
- A comment anchor still resolves after an insert before the range.
- Deleting the anchored text lets the client mark the thread detached. The thread remains readable.

## 9. Failure and chaos tests

Run against Compose on a schedule, not on every pull request.

| Case | Steps | Pass |
| --- | --- | --- |
| Redis down | Stop Redis, send an editor update on one gateway | Ack still happens. Document row exists |
| Redis down, second gateway | Peer on the other gateway | Peer misses the live message and matches after reconnect |
| Worker killed mid-export | Kill during `running` | Job returns to queued or failed after lock expiry, never stuck running past 10 minutes |
| MinIO down | Start export | Job fails, editor still saves |
| Compactor down | Keep typing | Acks continue. Snapshot lag grows. Alert condition is observable |
| Migration failure | Apply a deliberately bad script in a scratch database | Flyway stops. Previous schema remains. The bad script is not used in the product |

## 10. Load tests

k6 scripts live in `tests/load` when V5 starts. Minimum scripts:

- `metadata.js` for NFR-PERF-001.
- `updates.js` for NFR-PERF-005.
- `sockets.js` opens N connections, holds them, and sends awareness.

Record p50, p95, p99, error rate, and gateway heap. A run fails if error rate exceeds 1% or p95 exceeds the target.

## 11. Accessibility tests

- Axe in Playwright on login, list, editor, and share dialog. Serious and critical violations fail the suite.
- Keyboard-only script: register is covered by typing, create a document, bold a word with the shortcut, open share with the keyboard, close it.

## 12. Database migration tests

CI job:

1. Start PostgreSQL.
2. Run Flyway migrate.
3. Run Flyway validate.
4. Apply migrations again and expect no change.
5. From V2 onward, also migrate a dump of the previous version's schema.

## 13. End-to-end smoke

1. Register through the UI with Mailpit capturing the verification mail.
2. Verify.
3. Create a document.
4. Type "Hello CollabDocs".
5. Wait for Saved.
6. Reload.
7. Assert the text.

V2 adds a second browser context that sees the text without reload.

## 14. Release criteria by version

| Version | Required suites |
| --- | --- |
| V1 | Auth, document, editor unit, autosave integration, e2e smoke, migration |
| V2 | All TC-COLLAB cases except those that need suggestions |
| V3 | Comments, suggestions, restore, audit row assertions |
| V4 | Fidelity fixtures, image upload, a11y axe |
| V5 | Load tests, backup restore drill, two-replica TC-COLLAB-010 |

## 15. Defect severity

| Severity | Examples |
| --- | --- |
| Blocker | Data loss of acked content, cross-user read, auth bypass |
| Critical | Divergence after the collaboration suite, restore applies old epoch updates |
| Major | Export drops a construct the matrix marks as preserved |
| Minor | Visual spacing, non-blocking copy |

Blocker and critical defects stop the milestone exit.
