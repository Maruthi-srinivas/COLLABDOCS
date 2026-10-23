# CollabDocs Non-Functional Requirements and Capacity Planning

| Field | Value |
| --- | --- |
| Audience | Engineering and operations |
| Depends on | [SRS](../01-product/SRS.md) |
| Status | Proposed targets until benchmarks exist |

Every number in the target tables is a goal for the test described, not a claim that the current repository already meets it. The repository starts as documentation and infrastructure. Benchmarks are exit criteria for the version that introduces the feature.

## 1. Latency targets

| ID | Metric | Target | How to measure |
| --- | --- | --- | --- |
| NFR-PERF-001 | API metadata read p95 | 200 ms | 50 virtual users, small profile, warm cache, `GET /documents/{id}` |
| NFR-PERF-002 | Local keystroke p95 | 30 ms | Editor unit harness, 100 KB snapshot, from keydown to frame that includes the character |
| NFR-PERF-003 | Collaboration delivery p95 | 200 ms | Two browsers, two gateway replicas, from ack on A to apply on B, includes the database commit |
| NFR-PERF-004 | Bootstrap p95 | 1.5 s | `GET /content-state` plus client apply, 100 KB snapshot, 50 tail updates |
| NFR-PERF-005 | Durable ack p95 | 500 ms | 16 KB update, small profile |
| NFR-PERF-006 | Title search p95 | 300 ms | 50,000 documents in one workspace, trigram index |

Editor input latency is local and does not include the network. A slow ack must not block the caret.

## 2. Durability and recovery targets

| ID | Target |
| --- | --- |
| NFR-AVAIL-001 | 99.5% monthly API availability on the small profile |
| NFR-AVAIL-002 | 99.9% monthly on medium and large profiles |
| NFR-AVAIL-003 | RPO is zero for updates that received `sync.ack` or HTTP ack. Unacked updates may be lost in V1 |
| NFR-AVAIL-004 | RTO 60 minutes small, 30 minutes medium, using backup restore drills |
| Autosave | Ack means the row is committed. The UI must not say Saved before that |
| Compaction lag | p95 under 6 minutes during active editing, measured as now minus `updated_at` of the latest snapshot when the document is dirty |

Availability excludes planned maintenance windows announced 24 hours ahead, capped at 2 hours per month. Those windows still should use rolling restarts that keep one API replica up on the medium profile.

## 3. Scale targets

| ID | Proposed limit |
| --- | --- |
| Concurrent human editors per document | 50 |
| Concurrent sockets per gateway instance | 5,000 |
| WebSocket connections, large profile | 50,000 across gateways |
| Compacted document size | 2 MB default hard cap |
| Single update | 256 KB |
| Import file | 20 MB |
| Attachment | 10 MB |
| Export jobs in parallel per worker replica | 2 |
| Registered users, large profile planning number | 100,000 |
| Documents, large profile planning number | 1,000,000 |

Fifty editors in one document is a product cap enforced by rejecting the 51st editor socket with `4429` and a message to try later. Viewers count toward the cap.

## 4. Small-profile measurements

Recorded on this workstation with the local Compose stack on 22 October 2026. The raw k6 summaries are [metadata-one.json](load/metadata-one.json), [updates-one.json](load/updates-one.json), and [updates-two.json](load/updates-two.json). The restore timing is [restore.txt](load/restore.txt). Sections 5 to 7 still use the earlier storage model (0.5 updates per second, 1 KB average update, 100 KB typical snapshot, 1 MB large snapshot, 10% monthly active, 10% of those concurrent, 2% of concurrent users editing, 7-day update retention, 30 snapshots per document, 50% index overhead, 2 MB average export). The k6 runs did not measure those distributions, and this version does not partition `collab_updates` or archive audit events.

- A-C1. Metadata, one API replica, 50 virtual users, 30 seconds, `GET /documents/{id}`: p50 4.17 ms, p95 91.59 ms, p99 164.68 ms, error rate 0%. The NFR-PERF-001 target is 200 ms.
- A-C2. Durable ack, one API replica, 10 virtual users, 30 seconds, 16 KB content update: p50 8.23 ms, p95 51.38 ms, p99 93.82 ms, error rate 0%. This meets NFR-PERF-005.
- A-C3. The same ack workload through Nginx in front of two API replicas: p50 9.36 ms, p95 32.68 ms, p99 70.20 ms, error rate 0%. This meets the 500 ms p95 and 1% error-rate bar.
- A-C4. These runs do not stand in for the 5,000-socket cap or a medium-hardware profile.
- A-C5. The encrypted dump plus WAL restore opened the known document in 19 seconds. The medium RTO target stays 1,800 seconds.
- A-C6. A client on gateway A and a client on gateway B received the same ordered update. A logged-out session was rejected on a new socket with close code 4401.
- A-C7. `collabdocs_app` can read `audit_events` and an `UPDATE` of that table fails with permission denied. Development Compose still uses the single `collabdocs` user.
- A-C8. An EICAR HTML import failed with `MALWARE_DETECTED`, left no document, and the stored object was removed.

## 5. Scenario definitions

### 5.1 Small

| Item | Value |
| --- | --- |
| Registered users | 200 |
| Documents | 2,000 |
| Peak concurrent users | 200 * 0.10 * 0.10 = 2, use a floor of 20 for a classroom demo |
| Peak editor sockets | 20 |
| API replicas | 1 |
| Gateway replicas | 1 |
| Worker replicas | 1 |
| Compactor replicas | 1 |
| PostgreSQL | 1 instance, 2 vCPU, 4 GB RAM |
| Redis | 1 instance, 512 MB |

The floor of 20 concurrent users replaces the statistical 2, because a demo with one class is the real small case.

### 5.2 Medium

| Item | Value |
| --- | --- |
| Registered users | 10,000 |
| Documents | 100,000 |
| Monthly active | 1,000 |
| Peak concurrent users | 100 |
| Peak editor sockets | 20% of concurrent = 20, raised to a planning peak of 200 to allow a launch spike |
| API replicas | 2 |
| Gateway replicas | 2 |
| Worker replicas | 2 |
| PostgreSQL | 4 vCPU, 16 GB RAM |
| Redis | 2 GB |

### 5.3 Large

| Item | Value |
| --- | --- |
| Registered users | 100,000 |
| Documents | 1,000,000 |
| Monthly active | 10,000 |
| Peak concurrent users | 1,000 |
| Peak editor sockets | 500 |
| API replicas | 4 |
| Gateway replicas | 4 at 5,000 sockets each, headroom for 20,000 |
| Worker replicas | 4 |
| Compactor replicas | 2 |
| PostgreSQL | 8 vCPU, 32 GB RAM, storage sized below |
| Redis | 4 GB |

## 6. Storage calculations

### 6.1 Collaborative bytes, large profile

Assume 20% of documents are active in a month and receive 500 updates that month. The rest receive none.

- Active documents: 200,000
- New update bytes per month: 200,000 * 500 * 1 KB = 100,000,000 KB = about 95.4 GiB raw
- With 50% index overhead: about 143 GiB per month before pruning
- Pruning after 7 days once compacted: steady-state update log is about 7/30 of that month, about 33 GiB
- Snapshots: 1,000,000 documents * 30 snapshots * 100 KB = 3,000,000,000 KB = about 2.8 TiB raw if every document were 100 KB and kept 30 snapshots

That snapshot figure is the number that would force object storage and retention tuning. Planning response:

- Store snapshots over 512 KB in object storage.
- Keep the version retention policy tight for untouched documents: documents with no edits do not gain snapshots.
- Recompute when the average snapshot size and the average number of retained snapshots are known.

Planning budget for the large profile after retention, using 100,000 documents that actually have history and 10 retained snapshots of 100 KB:

- Snapshot bytes: 100,000 * 10 * 100 KB = about 95 GiB
- Update log steady state: about 33 GiB
- Metadata (users, grants, comments) estimate: under 20 GiB at this user count
- PostgreSQL volume: 250 GiB provisioned to leave headroom
- Object storage: snapshots overflow plus attachments. Assume 0.5 MB of images per document across 100,000 active documents = 50 GiB, plus 7 days of exports. If 1% of active documents export daily: 1,000 * 2 MB * 7 = about 14 GiB. Provision 200 GiB object storage.

### 6.2 Small profile storage

- 2,000 documents * 10 snapshots * 20 KB typical short docs = about 400 MB snapshots
- Update log negligible under 1 GB with pruning
- PostgreSQL volume: 20 GB
- Object storage: 10 GB

### 6.3 Medium profile storage

Scale the large active-document math by 0.1:

- PostgreSQL volume: 100 GB
- Object storage: 50 GB

## 7. Bandwidth

Large peak: 500 editors * 0.5 updates/s * 1 KB = 250 KB/s of raw updates upward. Fan-out: if the average document has 3 peers, downward traffic is about 750 KB/s. This is modest. The WebSocket connection count, not bandwidth, is the gateway limit.

Base64 on the wire multiplies payload size by about 4/3. Still under 2 MB/s downward at this peak. A 1 Gbps link is sufficient for the application tier. The database disk must sustain the write IOPS: 250 updates/s large peak, each a small transaction. That is within a single PostgreSQL primary for this product. It is not a multi-region write rate.

## 8. Connection capacity

| Profile | Peak sockets | Gateway replicas | Sockets per replica |
| --- | --- | --- | --- |
| Small | 20 | 1 | 20 |
| Medium | 200 | 2 | 100 |
| Large | 500 | 4 | 125 |

The 5,000 socket rating per replica is headroom, not the planning load. V5 measured the small-profile ack runs above and did not run a 5,000-socket test.

Memory budget: 200 KB per socket for buffers, 5,000 sockets = about 1 GB, plus JVM heap 1 GB. Gateway limit in Compose is 2 GB.

## 9. Export throughput

One worker completes a 20-page PDF in a target of 20 seconds single-flight (`G-7`). Two parallel jobs per replica. Medium profile with 2 replicas: about 12 PDFs per minute if saturated. The product does not promise more. Queue age alert fires at 2 minutes p95.

## 10. Database growth operations

- Alert when the PostgreSQL volume is 70% full.
- Run retention daily.
- `VACUUM` is autovacuum. Update-heavy tables are watched for bloat.
- A restore drill is required before V5 exit: restore a backup into a clean volume and open a document.

## 11. What this plan does not cover

- Multi-region active-active writes.
- Offline clients measured in days.
- Documents at the 2 MB cap with 50 concurrent editors. That combination is a specific load test, not the average case used above.

## 12. Review cadence

A-C1 through A-C8 cite the V5 small-profile runs. If update log growth exceeds the steady-state estimate by 2x for two weeks, shorten retention or lower the snapshot interval only with an ADR note, because shorter snapshots increase storage and longer intervals increase join tails.
