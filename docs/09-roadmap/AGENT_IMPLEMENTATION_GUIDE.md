# AI Coding Agent Implementation Guide

| Field | Value |
| --- | --- |
| Audience | Humans coordinating agents, and the agents themselves |
| Depends on | [Roadmap](ROADMAP.md), [HLD](../02-architecture/HLD.md), [API](../04-api/API_SPECIFICATION.md) |

## 1. How to use this guide

Give an agent one task from the roadmap, the file list it may edit, and the requirement ids. Do not give an agent a whole version. Point it at the documents below and tell it to follow them when the prompt and the code disagree with this documentation. If the documentation is wrong, the agent updates the documentation in the same change.

## 2. Roles

| Role | May edit | Must not edit |
| --- | --- | --- |
| Frontend editor | `services/web/**` | Java services, Flyway, gateway protocol semantics |
| Backend API | `services/api/**`, `services/common/**`, `services/persistence/**` | WebSocket handler, React components |
| Collaboration | `services/collab/**`, `services/common/**` protocol helpers, `services/web/src/collab/**` only when the task says the client protocol is in scope | Export, account deletion |
| Database | `db/migration/V*.sql` new files only, `docs/03-data/**` | Applied migration files |
| Security | Auth filters, `PermissionService`, security tests, `docs/05-security/**` | Editor schema |
| QA | `tests/**`, test sources inside modules | Production behavior without a failing test that the owning role then fixes |
| DevOps | `docker/**`, compose files, `.github/workflows/**`, `docs/08-devops/**` | Domain rules |

One agent may hold two roles in a single task when the task says so. Two agents may not edit the same file in parallel.

## 3. Parallelism

Safe in parallel after the OpenAPI file and `V1__init.sql` are frozen for that milestone:

- Frontend list pages and backend auth, until they integrate.
- Worker export and editor table extensions, in V4, until the projection JSON is the integration point.
- Dashboards and application features, in V5.

Not safe in parallel:

- `ContentUpdateService` and a second writer of `collab_updates`.
- Schema migrations. One open migration version at a time.
- Editor `schema.ts` and suggestion mark work.
- `PermissionService` role order.

Integration tasks start only when the interface task is merged: persistence service before the gateway, project endpoint before the exporter, protocol document before the client socket.

## 4. Branch and pull request workflow

- Branch from `main`: `v1/auth-register`, `v2/gateway-ack`, and similar.
- One concern per branch.
- Pull request describes requirement ids, test evidence, and any documentation updates.
- API or migration changes require the backend role and a review from whoever owns the contract tests.
- Do not force-push shared branches. Do not commit secrets or `.env`.

## 5. Architecture rules agents must keep

- Java 21 and Spring Boot for `api`, `collab`, and `worker`.
- React, TypeScript, and Vite for the SPA.
- Yjs updates are opaque in Java.
- Only `ContentUpdateService` inserts `collab_updates`.
- Ack means committed.
- Epoch mismatch rejects the update.
- Permission checks live on the server.
- New document nodes update `FR-EDIT-001`, the compactor, and a migration note together.
- Containers provide the toolchains. Do not add a README step that installs Node or Java on the host.
- Do not introduce a second document body column.

## 6. API coordination

The normative HTTP contract is `docs/04-api/openapi.yaml`. Changing a path requires:

1. Edit the YAML and `API_SPECIFICATION.md`.
2. Update the traceability row.
3. Add or adjust a contract test.

Clients generate types from the YAML. Hand-written duplicates are a bug.

## 7. Database coordination

- Add `db/migration/V{n}__description.sql`.
- Do not modify `V1__init.sql` after any shared database has applied it. Before the first shared apply, V1 may still be corrected.
- Update `DATABASE_DESIGN.md` in the same pull request.
- Include a rollback note in the pull request. Prefer forward fixes.

## 8. Dependency management

- Java dependencies are declared in the Maven BOM inside `services/pom.xml`.
- Web and compactor use lockfiles.
- A new library needs a sentence in the pull request saying why the standard library is not enough.
- Do not add a second HTTP client, a second CRDT, or a second editor framework.

## 9. Testing and done

A task is done when:

- The requirement's acceptance text is met.
- Automated tests named in the task pass in Docker or CI.
- Logs do not print tokens.
- The documentation that the change invalidated is updated.
- The service still starts with Compose.

## 10. Conflict resolution

If two pull requests touch `PermissionService` or `schema.ts`, the later one rebases and re-runs the permission or editor tests. If they disagree about behavior, the SRS wins until a human changes the SRS. Agents do not silently pick a new role order or a new save definition.

## 11. Reusable task prompt

Copy this and replace the brackets.

```text
You are implementing one CollabDocs task.

Read:
- docs/README.md
- docs/01-product/SRS.md for [FR-IDS]
- docs/02-architecture/HLD.md and LLD.md
- docs/04-api/openapi.yaml if the task touches HTTP
- docs/04-api/COLLABORATION_PROTOCOL.md if the task touches live editing
- docs/09-roadmap/AGENT_IMPLEMENTATION_GUIDE.md

Task: [TASK NAME FROM ROADMAP]
Edit only: [PATHS]
Do not edit: [PATHS]

Rules:
- Backend behavior belongs in Java (Spring Boot) except the Yjs compactor.
- Frontend stays a Vite React TypeScript SPA.
- Persist collaborative changes only through ContentUpdateService.
- An acknowledgement means the database transaction committed.
- Enforce permissions on the server.
- Add tests from docs/07-quality/TESTING_STRATEGY.md that apply to this task.
- Keep Docker as the way to run tools. Do not require a host JDK or Node install.
- Update the docs you make inaccurate.

Done when the task acceptance criteria pass and you can say which tests you ran.
```

## 12. Suggested first task

V1 task 1 and task 2 together: Compose health and Flyway on an empty database. The next session implements `ContentUpdateService` and `PermissionService` before any editor UI. The editor should not invent a private save format while waiting for the API.

## 13. Review checklist

- Requirement id is named.
- Negative permission test exists for a new route.
- Migration is a new version.
- No secret in the diff.
- Save and sync semantics unchanged, or the protocol document changed on purpose.
- Mermaid and examples still match the code.
