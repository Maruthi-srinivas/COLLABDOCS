# Architecture Decision Records

| Field | Value |
| --- | --- |
| Product | CollabDocs |
| Status | Accepted for this documentation baseline |
| Related | [HLD](HLD.md), [PRD](../01-product/PRD.md) |

Decisions are accepted so agents do not reopen them during a milestone unless they hit a revisit condition. Each record lists trade-offs. None of these choices is universally correct for every product.

## ADR-001 Editor framework

- **Status.** Accepted.
- **Context.** The product needs rich text, tables, suggestions, collaborative cursors, and a schema we can version. The frontend must stay a lightweight SPA.
- **Options.**
  1. Tiptap on ProseMirror, with React bindings.
  2. Lexical.
  3. Slate.
  4. A custom ProseMirror editor without Tiptap.
- **Decision.** Tiptap with ProseMirror, React, and TypeScript, built by Vite.
- **Rationale.** ProseMirror's document model and step model are a proven base for word-processor features. Tiptap supplies extensions for marks, lists, tables, and collaboration bindings without hiding the schema. `y-prosemirror` is maintained against this stack. Vite keeps the production frontend as static files served by Nginx, with no Node server in the request path.
- **Trade-offs.** Tiptap upgrades can require schema care. Lexical has strong React ergonomics and a less direct Yjs story. Slate's operational-transform history is a weaker fit for Yjs. Hand-rolled ProseMirror would duplicate extension work.
- **Consequences.** Schema in `FR-EDIT-001` is the contract. Agents add nodes through a schema migration, not ad hoc HTML.
- **Revisit if.** A required feature cannot be represented as a ProseMirror node or mark without forking Tiptap, or bundle size exceeds the NFR budget after measurement.

## ADR-002 CRDT selection

- **Status.** Accepted.
- **Context.** Concurrent editing must converge without last-write-wins on the whole document.
- **Options.**
  1. Yjs.
  2. Automerge.
  3. Operational transform with a central sequencer that rewrites operations.
- **Decision.** Yjs, document fragment `prosemirror`, awareness separate from the document.
- **Rationale.** Yjs has a small update encoding, a ProseMirror binding, and commutative updates that a Java server can store opaquely. OT would require the server to understand every editor operation and to rewrite history, which couples the Java gateway to ProseMirror internals.
- **Trade-offs.** Yjs history is not a perfect user-level undo across peers. Tombstones grow until compaction rebuilds a fresh state. Automerge's JavaScript and Rust story is solid, and its JSON model is a weaker match for ProseMirror than `y-prosemirror`.
- **Consequences.** The server never merges by parsing updates. Clients and the compactor apply bytes with Yjs.
- **Revisit if.** A supported JVM CRDT with byte compatibility and a ProseMirror binding reaches production quality, or update size fails the capacity plan.

## ADR-003 Collaboration transport

- **Status.** Accepted.
- **Context.** Peers need low-latency delivery, authentication, and backpressure. Several Yjs servers speak the `y-websocket` binary protocol.
- **Options.**
  1. Adopt `y-websocket` or Hocuspocus as the public protocol.
  2. Use a custom envelope that carries Yjs update bytes, implemented by the Java gateway.
- **Decision.** Custom subprotocol `collabdocs.v1`. Payloads that represent document changes are unmodified Yjs updates (`Y.encodeStateAsUpdate` fragments produced by `Y.mergeUpdates` or single updates). Control messages are JSON.
- **Rationale.** The Java gateway must check roles, epochs, and idempotency keys before persisting. The stock y-websocket session does not know our permission model. Wrapping Yjs bytes keeps CRDT compatibility without adopting a Node server as the session owner.
- **Trade-offs.** We maintain a protocol. Off-the-shelf Yjs debugging clients do not connect without an adapter. Hocuspocus would have reduced protocol work and moved session ownership to Node, conflicting with the Java gateway decision.
- **Consequences.** The protocol document is normative. Version bumps use a new subprotocol name.
- **Revisit if.** Permission hooks in a maintained Yjs server implement epoch restore, idempotent persistence, and Java-independent audits with less code than the gateway.

## ADR-004 Collaboration persistence and the compactor

- **Status.** Accepted.
- **Context.** Acknowledged updates must survive process death. Joining clients need a current state. The JVM should not reimplement Yjs.
- **Options.**
  1. Store a JSON document and overwrite it.
  2. Store the Yjs update log in PostgreSQL and compact it with a Node Yjs process.
  3. Run a full Node collaboration server that persists with `y-redis` or LevelDB.
- **Decision.** Append-only `collab_updates` in PostgreSQL. Snapshots written by the `compactor` container. The Java gateway is the only writer of live updates.
- **Rationale.** Option 1 loses concurrent characters. Option 3 makes Node the system of record for the most important bytes and splits authorization. Option 2 keeps one database and limits Node to a library host.
- **Trade-offs.** Every update is a transaction. Snapshot lag means join payloads include a tail. The compactor is an extra container and an extra runtime in an otherwise Java backend. That exception is explicit and should stay small.
- **Consequences.** Agents must not add a second document body column as the source of truth. Derived JSON is a projection.
- **Revisit if.** Update-log write latency exceeds `NFR-PERF-005` after index and hardware tuning, or a Java Yjs-compatible library can apply updates in-process with matching test vectors.

## ADR-005 Backend language and service boundaries

- **Status.** Accepted.
- **Context.** The product constraint requires a Java backend. The original baseline in early notes mentioned Python and FastAPI. That baseline is not used.
- **Options.**
  1. Java 21 and Spring Boot 3 for API, gateway, and worker. Node only for the compactor.
  2. Java API plus a Node collaboration server.
  3. Python API, which violates the constraint.
- **Decision.** Option 1. Framework is Spring Boot 3.4 on Java 21. Build tool is Maven. Modules share `collabdocs-common`.
- **Rationale.** One language for auth, ACL, WebSocket, and export reduces the ways tokens and roles can drift. Spring Security, JDBC transactions, Flyway, and Apache POI cover the required backend work. Export libraries are stronger in Java than in a from-scratch Node exporter.
- **Trade-offs.** Spring services use more memory than a minimal Go or Node process. WebSocket libraries in the JVM are mature but less commonly paired with Yjs examples. Developers run Java only inside Docker, so host JDK install is still not required.
- **Consequences.** New backend features go into the existing modules unless an ADR splits them. The compactor exposes two internal HTTP endpoints and no public API.
- **Revisit if.** Gateway garbage-collection pauses break `NFR-PERF-003` and a measured prototype of a Java virtual-thread tuning pass does not fix them.

## ADR-006 PostgreSQL for relational data

- **Status.** Accepted.
- **Context.** Users, permissions, comments, and jobs are relational. Document bytes are ordered logs.
- **Options.**
  1. PostgreSQL for metadata and for the update log.
  2. PostgreSQL for metadata and MongoDB or CouchDB for document bodies.
  3. A document database for everything.
- **Decision.** PostgreSQL 16 for metadata and collaborative bytes, except blobs over 512 KB.
- **Rationale.** Foreign keys keep grants, comments, and documents consistent. `bytea` plus a sequence column is enough for the log. A second database would add a dual-write problem between ACL and content.
- **Trade-offs.** Very large update tables need retention and, later, partitioning. PostgreSQL is not an ideal blob store, so large snapshots move to object storage.
- **Consequences.** Flyway owns schema changes. Application roles do not get superuser.
- **Revisit if.** Update storage exceeds the capacity plan and partitioning plus retention is not enough.

## ADR-007 Redis

- **Status.** Accepted.
- **Context.** Fan-out, presence, rate limits, and job queues need a fast ephemeral store.
- **Options.**
  1. Redis 7 for pub/sub, keys, and streams.
  2. PostgreSQL `LISTEN/NOTIFY` and `SKIP LOCKED` for all of the above.
  3. A dedicated broker such as RabbitMQ or NATS.
- **Decision.** Redis 7.
- **Rationale.** Pub/sub matches multi-gateway fan-out. Streams match export jobs. TTLs match presence and denylist. One extra data service is cheaper to operate than a broker plus Redis.
- **Trade-offs.** Pub/sub does not buffer for a gateway that is down. Those clients recover by resync, which is already required. Redis is not the source of truth. A crash may drop a stream entry, so workers reconcile from PostgreSQL job rows.
- **Consequences.** No feature may store the only copy of document content in Redis.
- **Revisit if.** Job volume needs replay and routing that Redis streams handle poorly in testing.

## ADR-008 Object storage

- **Status.** Accepted.
- **Context.** Avatars, images, and exports do not belong in PostgreSQL.
- **Options.**
  1. S3-compatible storage. MinIO in development.
  2. Files on a container volume.
  3. PostgreSQL large objects.
- **Decision.** S3-compatible API. Development uses MinIO. Production may use any S3-compatible service with the same client configuration.
- **Rationale.** Volumes do not follow multi-instance workers. Large objects complicate backups. The S3 API is the common portability layer.
- **Trade-offs.** Another service in development. Eventual consistency on some providers means the worker uploads before it marks a job succeeded, and the download path retries once on a missing key.
- **Consequences.** Buckets are private. Downloads go through the API.
- **Revisit if.** The deployment environment cannot offer S3-compatible storage.

## ADR-009 Authentication

- **Status.** Accepted.
- **Context.** The SPA needs short-lived API authority and the ability to revoke sessions.
- **Options.**
  1. Server sessions stored only in Redis, cookie session id.
  2. JWT access token in memory plus rotating refresh token in an HttpOnly cookie, refresh rows in PostgreSQL.
  3. Long-lived JWT in local storage.
- **Decision.** Option 2. Access tokens use RS256. Refresh tokens are opaque and hashed. Reuse of a rotated token revokes the family.
- **Rationale.** The gateway can validate a JWT with the public key without a database read on every frame, and still check a compact denylist. Refresh rotation detects theft. Local storage JWTs are exposed to script.
- **Trade-offs.** Logout does not instantly expire an access token unless the denylist is checked. We check it. Clock skew of more than 60 seconds breaks validation. Key rotation must publish the new public key to every API and gateway replica.
- **Consequences.** Refresh is the only cookie-authenticated route. Other routes use `Authorization: Bearer`.
- **Revisit if.** A requirement appears for immediate revocation in under a second across regions where Redis replication lag exceeds that bound.

## ADR-010 Version history

- **Status.** Accepted.
- **Context.** Storing a full document per keystroke is unnecessary and expensive. Users still need checkpoints and restore.
- **Options.**
  1. Full copy on every update.
  2. Update log only, no snapshots.
  3. Periodic snapshots plus named snapshots, restore by starting a new epoch.
- **Decision.** Option 3. Policy is 100 updates or 5 minutes. Restore increments `epoch` and installs the chosen bytes as the new base snapshot.
- **Rationale.** Join and preview need snapshots. The log needs pruning. A new epoch makes it obvious that in-flight updates from the old timeline must not apply on top of a restored document. Rewriting history inside one Y.Doc would surprise connected clients.
- **Trade-offs.** Restore interrupts collaborators. Offline edits from the old epoch are not auto-merged. Snapshot policy can lag a few minutes, so the latest named version is forced immediately rather than waiting for the timer.
- **Consequences.** Clients handle `document.restored` by replacing the Y.Doc.
- **Revisit if.** User studies show restore must preserve concurrent edits made after the checkpoint. That is a different product rule and needs an explicit merge UI.

## ADR-011 Import and export conversion

- **Status.** Accepted.
- **Context.** DOCX and PDF need a server pipeline. The canonical editor state is Yjs, not OOXML.
- **Options.**
  1. Convert in Java with Apache POI and OpenHTMLToPDF, using the compactor only to project Yjs to JSON and JSON to Yjs.
  2. Convert entirely in Node with existing ProseMirror serializers.
  3. Shell out to LibreOffice in a container.
- **Decision.** Option 1 for V4. LibreOffice is a documented fallback if POI fidelity fails acceptance tests for tables.
- **Rationale.** The worker is already Java. POI avoids a second conversion stack. The compactor remains the only Yjs interpreter. LibreOffice improves fidelity and adds process isolation, image attack surface, and large images to the worker container.
- **Trade-offs.** POI will not preserve every DOCX feature. The fidelity matrix is the contract, not pixel-perfect Word compatibility.
- **Consequences.** Import reports list dropped features. Remote images are not fetched.
- **Revisit if.** The V4 fidelity suite misses the agreed matrix and a LibreOffice prototype passes it without unacceptable operational cost.

## ADR-012 Monolith versus separate services

- **Status.** Accepted.
- **Context.** Small teams and AI agents need few repositories. Export CPU and WebSocket connections should not share a fate with request threads.
- **Options.**
  1. One Spring process for everything.
  2. Separate containers for `api`, `collab`, `worker`, and `compactor`, one database, one Git repository.
  3. Separate repositories and databases per service.
- **Decision.** Option 2.
- **Rationale.** Process isolation matches scaling and failure domains. One repository and one schema keep agents from inventing conflicting contracts. Separate databases would break cross-entity transactions.
- **Trade-offs.** Local Compose runs more containers. Shared schema means a bad migration affects all services. Deployment order is: migrate, then rolling services.
- **Consequences.** Docker Compose is the development environment. Host installs of Java, Node, PostgreSQL, and Redis are not required.
- **Revisit if.** Independent team release cadences require separately versioned schemas.

## ADR-013 Horizontal scaling of collaboration

- **Status.** Accepted.
- **Context.** More than one gateway replica will receive sockets for the same document.
- **Options.**
  1. Sticky sessions and in-memory rooms.
  2. Stateless gateways, PostgreSQL sequencing, Redis pub/sub fan-out.
  3. A single-threaded room owner elected in Redis, with followers forwarding.
- **Decision.** Option 2.
- **Rationale.** Sticky rooms lose state on death and complicate deploys. An elected owner adds failover logic we do not need while PostgreSQL can order a few writes per second per document. Human editors do not produce broker-scale event rates.
- **Trade-offs.** Redis pub/sub loss during a network blip is recovered by client resync, which can add latency. Sequence assignment contends on one row per document.
- **Consequences.** Load tests must include two gateway replicas and a client on each.
- **Revisit if.** A single document's sequence row becomes a measured bottleneck. The next design is option 3 for that document only.

## ADR-014 Offline editing

- **Status.** Accepted.
- **Context.** Short disconnects are common. Long offline editing conflicts with version restore.
- **Options.**
  1. Refuse editing while the socket is down.
  2. Edit locally, queue updates, sync on reconnect, and refuse to auto-merge across an epoch change.
  3. Full offline branch with a manual merge UI in V2.
- **Decision.** Option 2, delivered in V2. V1 may edit during a brief HTTP retry but does not promise IndexedDB durability.
- **Rationale.** Yjs already merges concurrent updates if the epoch is unchanged. A restore is a deliberate history break. Automatic merge would hide data loss or duplication. A manual merge UI is a later product, not a silent default.
- **Trade-offs.** Users who edit offline across a restore must recover from a downloaded update file. That path is rare and must be tested.
- **Consequences.** The client stores `epoch` beside the IndexedDB document.
- **Revisit if.** Offline editing across days becomes a primary scenario. Then schedule a merge UI rather than weakening epoch checks.

## ADR-015 Frontend lightness

- **Status.** Accepted.
- **Context.** The product constraint asks for a lightweight frontend technology. The editor still needs a component model and a rich-text ecosystem.
- **Options.**
  1. React, TypeScript, and Vite as a static SPA.
  2. Next.js with server rendering.
  3. Preact or vanilla custom elements.
  4. Vue and Vite.
- **Decision.** Option 1.
- **Rationale.** The app is an authenticated editor. Server rendering does not speed the editing loop and would put Node back into the production request path. React matches Tiptap's primary bindings. Vite's dev server runs in Docker and the production artifact is static files. Vue would also be lightweight. React is selected so collaboration examples and `y-prosemirror` React bindings match the editor ADR.
- **Trade-offs.** React is larger than Preact. Preact compatibility with Tiptap's React package is an unnecessary risk. The SPA sends a client bundle on first load.
- **Consequences.** No server components. Route changes are client-side. Nginx falls back to `index.html`.
- **Revisit if.** First-load bundle exceeds 300 KB gzip for the editor route after code splitting and the team accepts a different editor binding.

## ADR-016 Container-only development

- **Status.** Accepted.
- **Context.** Contributors and agents must not install Node, Java, PostgreSQL, or Redis on the host.
- **Decision.** Docker Compose provides every runtime, including the Vite dev server, Maven builds inside image builds, Mailpit, MinIO, PostgreSQL, and Redis. The host needs Docker and a container runtime only.
- **Trade-offs.** File watching on Windows bind mounts can be slower. Compose files set polling for the web dev server.
- **Consequences.** Scripts and CI also run inside containers or the CI images defined in the workflow, not via a host toolchain assumption.
- **Revisit if.** A required tool cannot run in a container. The default response is to containerize that tool.
