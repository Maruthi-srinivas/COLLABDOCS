# CollabDocs Observability and Operations

| Field | Value |
| --- | --- |
| Audience | Operators and backend agents |
| Depends on | [HLD](../02-architecture/HLD.md), [NFR](../07-quality/NFR_AND_CAPACITY.md) |

## 1. Signals

Every Java service and the compactor emit structured JSON logs to stdout, Prometheus metrics on `/metrics` bound to the Docker network, and trace spans when `OTEL_EXPORTER_OTLP_ENDPOINT` is set. Development Compose includes no collector. Staging adds an OpenTelemetry collector container in a later overlay. The application must run with the endpoint unset.

## 2. Logs

Required fields: `timestamp`, `level`, `service`, `message`, `correlationId`, `documentId` when a document is in scope, `userId` when authenticated. Never log tokens, passwords, refresh cookies, raw share tokens, or `payloadBase64`.

Levels:

- `info` for lifecycle: login success, job finished, snapshot written.
- `warn` for rejected updates, retryable dependency errors.
- `error` for unhandled exceptions and job failure at the last attempt.

Login failures log email hash, not the email address, at `info` with `outcome=failure`.

## 3. Correlation

`X-Correlation-Id` enters at Nginx if the client did not send one. Nginx forwards it. The API puts it on Redis job messages. The worker logs it. WebSocket connections log the id from the auth message or a generated ULID for the socket lifetime. Fan-out messages include the origin correlation id.

## 4. Metrics

| Name | Type | Labels |
| --- | --- | --- |
| `http_server_requests_seconds` | histogram | method, route, status |
| `collab_sockets_open` | gauge | none |
| `collab_update_ack_seconds` | histogram | result |
| `collab_fanout_lag_seconds` | histogram | none |
| `collab_updates_total` | counter | result |
| `snapshot_lag_updates` | gauge | document_id is not a label. Use a histogram of lag across documents instead: `snapshot_lag_updates_bucket` |
| `export_jobs` | gauge | status |
| `export_job_duration_seconds` | histogram | format, result |
| `db_up` | gauge | service |
| `redis_up` | gauge | service |
| `ratelimit_block_total` | counter | route |

`snapshot_lag_updates` as a single gauge records the maximum lag observed by the compactor in the last cycle, named `snapshot_lag_updates_max`.

Gateway also exports `collab_auth_failures_total` by reason.

## 5. Tracing

Spans: HTTP request, database query, Redis publish, object put, compactor HTTP call, export job. Attributes include `document.id` and `job.id`. Do not attach document text. Trace sample rate is 10% in production and 100% in development when a collector is enabled.

## 6. Dashboards

Minimum panels for V5:

- API p95 and error rate by route.
- Ack latency p95.
- Open sockets.
- Max snapshot lag.
- Export queue depth and age of the oldest queued job.
- PostgreSQL connections and disk free.
- Redis memory.
- Ready-check failures.

## 7. Alerts

| Alert | Condition | Severity |
| --- | --- | --- |
| ApiErrorRate | 5xx over 2% for 5 minutes | page |
| AckSlow | ack p95 over 500 ms for 10 minutes | ticket |
| GatewaySockets | open sockets over 80% of the configured cap | ticket |
| SnapshotLag | max lag over 500 updates for 15 minutes | ticket |
| ExportStuck | a job queued or running over 10 minutes | page |
| DatabaseDown | `db_up` is 0 for 1 minute | page |
| RedisDown | `redis_up` is 0 for 1 minute | page |
| DiskLow | volume over 70% | ticket |
| DiskCritical | volume over 85% | page |

## 8. SLOs and error budgets

| SLI | SLO | Window |
| --- | --- | --- |
| API availability, non-5xx on metadata routes | 99.9% medium | 30 days |
| Ack success, excluding client 4xx | 99.5% | 30 days |
| Export jobs that finish within 2 minutes for documents under 1 MB | 95% | 30 days |

Error budget for API availability at 99.9% is about 43 minutes per 30 days. A release that has burned half the remaining budget waits for a fix-forward of reliability issues before a feature deploy. This policy starts in V5 when the SLO is measured.

## 9. Runbooks

### 9.1 A document is not synchronizing

1. Ask for `documentId` and `correlationId` from the client error or welcome frame.
2. Check gateway logs for that document id around the time.
3. Query `document_epochs` for `epoch`, `last_seq`, and `snapshot_through_seq`.
4. Query the latest `collab_updates.seq`. If the row exists and the peer did not apply it, the bug is fan-out or the client. Check Redis publish errors.
5. If the row does not exist, the origin never got an ack. Check database errors and client nacks.
6. If epochs differ, a restore happened. The client must reload. Confirm `audit_events` for `version.restore`.
7. If `compaction_status=failed`, editing should still work. Fix the compactor exception. Do not delete the update log as a first step.

### 9.2 An export is stuck

1. Read `export_jobs` status, attempts, and `started_at`.
2. If `queued` and the stream has no consumer, restart the worker. The sweeper should also requeue rows older than 2 minutes.
3. If `running` longer than 10 minutes, the worker died. Restart it. The lock expiry returns the job to queued.
4. If `failed`, read `error_code`. `SCHEMA_INVALID` needs a product fix, not a retry loop. `OBJECT_STORE` needs MinIO or S3 health.
5. Confirm the worker can reach `http://compactor:8083/health/live`.

### 9.3 The database is unavailable

1. API and collab ready checks go red. Writes fail with 503. No new acks.
2. Check disk, connections, and the PostgreSQL log.
3. Do not fail over by pointing the app at an empty database.
4. If the primary is unrecoverable, follow the restore drill. Until restore finishes, keep the site in maintenance mode at Nginx so clients do not treat 503 storms as saved documents.
5. After restore, spot-check one document's `last_seq` against a known ack from logs.

### 9.4 A collaboration server is overloaded

1. Confirm `collab_sockets_open` and CPU.
2. Scale gateway replicas. Clients reconnect. Sticky sessions are not required.
3. If one document has an abnormal update rate, inspect `collab_updates` counts per minute. A runaway client is rate limited. If the limiter failed, close sockets for that document and rely on reconnect.
4. Ack latency that tracks PostgreSQL lock waits points at the epoch row, not at the gateway CPU. See ADR-013 before sharding.

### 9.5 Redis is unavailable

Presence and multi-replica fan-out degrade. Single-replica local delivery and PostgreSQL writes continue. Restore Redis. Clients catch up by reconnect. Do not restore Redis from an old backup. Start empty.

## 10. Health endpoints

`/health/live` and `/health/ready` are defined in the API specification. Metrics scrapes must not be routed through the public Nginx server block. A private network is the scrape path.

## 11. On-call minimum for V5

The first production deployment has a human who can restart containers, read these runbooks, and restore a database dump. Paging is the alert table's page severity. There is no follow-the-sun rotation in this design.
