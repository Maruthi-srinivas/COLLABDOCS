# CollabDocs DevOps and Infrastructure

| Field | Value |
| --- | --- |
| Audience | DevOps and backend agents |
| Depends on | [HLD](../02-architecture/HLD.md) |
| Files | [docker-compose.yml](../../docker-compose.yml), [docker-compose.dev.yml](../../docker-compose.dev.yml), [`.env.example`](../../.env.example) |

Development uses Docker Compose only. The host needs Docker. It does not need a local JDK, Node.js, PostgreSQL, Redis, or Nginx.

## 1. Services

| Service | Image build | Published in dev | Purpose |
| --- | --- | --- | --- |
| `nginx` | `docker/nginx` | 8080 | Edge |
| `web` | `docker/web/Dockerfile.dev` | none, via Nginx | Vite |
| `api` | `docker/api/Dockerfile` | none, via Nginx | REST |
| `collab` | `docker/collab/Dockerfile` | none, via Nginx | WebSocket |
| `worker` | `docker/worker/Dockerfile` | none | Jobs |
| `compactor` | `docker/compactor/Dockerfile` | none | Yjs snapshots |
| `postgres` | `postgres:16-alpine` | none | Database |
| `redis` | `redis:7-alpine` | none | Ephemeral state |
| `minio` | `minio/minio` | 9001 console | Objects |
| `mailpit` | `axllent/mailpit` | 8025 UI | Mail capture |
| `migrate` | `flyway/flyway` | none | Schema |

Production Compose omits Mailpit and the MinIO console public port, terminates TLS in Nginx, and does not publish database ports.

## 2. Networking

One user-defined bridge network `collabdocs`. Service DNS names match the service keys. Only Nginx publishes a host port in the default project. Mailpit and the MinIO console publish in development so humans can open them. Automated tests use the Docker network.

Nginx routes:

| Path | Upstream |
| --- | --- |
| `/api/` | `api:8080` |
| `/collab/` | `collab:8081` with WebSocket upgrade |
| `/` | `web:5173` in dev, static files in prod |

Request body limit is 25 MB so imports of 20 MB fit. WebSocket read timeout is 3600 seconds. Proxy send timeout is 3600 seconds.

## 3. Volumes

| Volume | Contents |
| --- | --- |
| `pgdata` | PostgreSQL data directory |
| `redisdata` | Optional AOF. Development turns AOF off |
| `miniodata` | Objects |
| Bind `./db/migration` | Flyway scripts |
| Bind `./services` | Source for dev image builds and Vite |

Redis may lose data on restart in development. PostgreSQL may not.

## 4. Startup order

`migrate` waits for PostgreSQL health, then applies Flyway, then exits. `api`, `collab`, `worker`, and `compactor` wait for `migrate` to complete successfully and for Redis to be healthy. `nginx` waits for `api` and `web`.

Health checks:

| Service | Check |
| --- | --- |
| postgres | `pg_isready` |
| redis | `redis-cli ping` |
| minio | `GET /minio/health/live` |
| api, collab, worker | `GET /health/ready` |
| compactor | `GET /health/live` |
| web dev | `GET /` |
| nginx | `GET /nginx-health` on a local config that returns 200 |

Ready checks fail until dependencies respond. Live checks do not call the database.

## 5. Configuration and secrets

`.env.example` lists every variable. Compose uses `env_file: .env`. Do not commit `.env`.

Required:

- `POSTGRES_PASSWORD`
- `JWT_PRIVATE_KEY_PEM` and `JWT_PUBLIC_KEY_PEM` as single-line `\n` escaped PEM
- `INTERNAL_TOKEN`
- `MINIO_ROOT_PASSWORD`
- `ALLOWED_ORIGINS`

Development JWT keys may be generated inside a one-shot container. The command is in section 11. Generated keys stay in `.env`.

## 6. Environments

| Env | Difference |
| --- | --- |
| Development | HTTP, Mailpit, bind mounts, Vite HMR polling, debug logs |
| Staging | TLS, real SMTP, no bind mounts, images from the registry, one replica |
| Production | TLS, secret manager, resource limits, backups, at least the medium replica counts when the capacity plan says so |

The same images run in staging and production. Configuration differs.

## 7. CI/CD

Workflow file: `.github/workflows/ci.yml`.

On pull request:

1. Flyway migrate against a Postgres service container.
2. Maven test for Java modules with Testcontainers. The CI runner must provide Docker.
3. Compactor tests in `node:22-alpine`.
4. Web unit tests in `node:22-alpine`.
5. OpenAPI parse using `@redocly/cli lint` in a container.

On main:

1. Build images.
2. Push to the registry when credentials exist.
3. Deploy to staging is a manual workflow dispatch that runs Compose pull and up. Production dispatch additionally requires the staging smoke suite.

The pipeline does not use a self-hosted JDK on the developer laptop. CI runners may have Docker preinstalled. That is the runner, not the contributor machine.

## 8. Database migrations

Flyway runs as the `migrate` service before application startup. Application containers do not run Flyway on boot, so two replicas cannot race migrations.

Failed migration: fix with a forward script. Do not edit an applied version. If a migration fails mid-way, Flyway leaves it failed and refuses to continue. Repair only after the database is known to be at a clean version. The runbook is: stop writers, restore the pre-migration snapshot if the script was destructive, or complete a forward fix if it was not.

## 9. Deployment strategy

Rolling update for `api` and `collab`: start a new container, wait for ready, then stop the old one. WebSocket clients reconnect. `worker` drains by waiting up to 30 seconds for the current job on SIGTERM. The worker marks a job running and the sweeper requeues if the process dies.

Schema changes deploy before the new code when they are additive. Destructive changes follow the two-release rule in the database design.

## 10. TLS

Staging and production Nginx listens on 443 with a certificate mounted from `./docker/nginx/certs` or a platform secret. Ciphers are the Mozilla intermediate set. HTTP redirects to HTTPS. Development listens on 8080 without TLS. Do not reuse the development exception in staging.

## 11. Local start

```text
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.dev.yml run --rm migrate
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
```

Open `http://localhost:8080`. Mailpit is `http://localhost:8025`. MinIO console is `http://localhost:9001`.

Generate development keys with a container if the PEM variables are empty:

```text
docker run --rm alpine/openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048
```

Paste the private key and the extracted public key into `.env`. This is a development convenience, not a production key ceremony.

## 12. Backup and restore

PostgreSQL:

- Nightly `pg_dump` custom format to the backup bucket prefix `db/`.
- WAL archiving is required for the medium RPO story once V5 starts. Until WAL archiving exists, RPO is the time since the last dump, and the availability document's zero-RPO claim applies only to committed transactions that are still on the primary disk. State this honestly in operations: a disk loss without WAL loses changes since the last dump. V5 exit requires WAL archiving so a restored primary can reach the last committed transaction that reached disk.
- Restore drill: new volume, `pg_restore`, start API against it, open a known document.

Object storage:

- Versioning on the bucket in production.
- Lifecycle deletes export prefixes after 7 days.

Redis is not backed up.

## 13. Rollback

1. Redeploy the previous image tags.
2. If the new migration was additive, leave it.
3. If the new migration is incompatible with old code, restore the database snapshot taken immediately before migrate. Application rollback without database rollback is forbidden in that case. The release checklist requires the snapshot.

## 14. Resource limits

Development limits are loose. Production Compose sets:

| Service | CPU | Memory |
| --- | --- | --- |
| api | 1 | 1 GB |
| collab | 1 | 2 GB |
| worker | 1 | 1.5 GB |
| compactor | 0.5 | 512 MB |
| postgres | 2 | 4 GB on small |
| redis | 0.25 | 512 MB |

JVM flags: `-XX:MaxRAMPercentage=75`.

## 15. Scaling and load balancing

Nginx uses `least_conn` for gateways and round robin for API. No sticky cookie. Session state is in PostgreSQL and Redis.

Scale command shape:

```text
docker compose up -d --scale api=2 --scale collab=2 --scale worker=2
```

Nginx upstreams in the single-host file list the Compose service name, which Docker DNS load-balances across tasks. A later Kubernetes manifest can replace Compose without changing service contracts.

## 16. Disaster recovery

| Event | Action |
| --- | --- |
| Single container crash | Restart policy `unless-stopped` |
| Bad release | Rollback section |
| PostgreSQL data loss | Restore dump and WAL if V5 archiving is on |
| Region loss | Not covered. One region is assumption A-3 |
| Lost JWT key | Generate a new key. All access tokens become invalid. Refresh tokens remain until users refresh, and the API must still have the public key that matches signatures. If the private key is lost, users log in again. Refresh verification does not use the JWT key |
| Lost `INTERNAL_TOKEN` | Rotate the token and restart worker and compactor together |

RTO steps are timed in the V5 drill and compared with `NFR-AVAIL-004`.

## 17. Application database role

Production creates `collabdocs_app` with `SELECT, INSERT, UPDATE, DELETE` on application tables and `INSERT, SELECT` only on `audit_events`. Migrations use `collabdocs_migrator`. Development Compose uses one user for both to keep the first milestone smaller. The split is a V5 task and is specified so agents do not grant the application role superuser later.

## 18. Health and graceful shutdown

Java services implement Spring Boot actuator-style endpoints themselves at `/health/live` and `/health/ready` so the image does not depend on exposing the full actuator surface. Ready returns 503 when the database check fails.

On SIGTERM the gateway stops accepting sockets, waits up to 20 seconds for in-flight persists, then closes remaining sockets with `1001`.
