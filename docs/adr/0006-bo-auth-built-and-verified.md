# ADR-0006: BO auth/permission layer — built and live-verified

- **Status**: Accepted (built); Common Code and Device CRUD screens remain unbuilt
- **Date**: 2026-09-08
- **Deciders**: Project owner

## Context

`docs/adr/0004` scoped BO's auth approach (session-based) and 3-tier
permission model but left domain models, exact endpoints, and the
Operator permission model as **Ongoing**. Later the same session, the
owner asked to build it. This ADR records what actually exists now,
closing out those open items — rather than editing ADR-0004 directly, per
this repo's own rule (`0000-template.md`: "write a new one... rather than
editing this file").

## Decision

**Built and live-tested** (`BoAuthTest`, 5/5 passing, run over real HTTP
against the real Netty server — not mocked):

- Domain: `Operator`, `Screen` (enum: `COMMON_CODE`, `DEVICE`, `OPERATOR`),
  `Action` (enum: `VIEW`, `CREATE`, `SAVE`, `DELETE`),
  `OperatorScreenPermission` (per-screen booleans + `allows(Action)`),
  `OperatorRepository` port.
- Persistence: `InMemoryOperatorRepository` (default), `MyBatisOperatorRepository`
  (PostgreSQL — unverified, `docs/adr/0005`), Flyway `V2__create_operator_tables.sql`.
- Auth: `PasswordHasher` (BCrypt via `at.favre.lib:bcrypt`), `Session`/
  `SessionStore` port, `InMemorySessionStore` (default — no `RedisSessionStore`
  yet, see Consequences).
- Endpoints: `POST /bo/auth/login` (issues the `HttpOnly`+`SameSite=Lax`
  session cookie), `POST /bo/auth/logout` (deletes the session server-side
  — the whole point of choosing session over JWT), `GET /bo/auth/me`.
- `AuthorizedEndpoint`: a `RestEndpoint` decorator taking `(Screen, Action,
  SessionStore, delegate)` — super admin bypasses the check entirely,
  otherwise the caller's session must carry a permission for that screen
  that allows that action. Any future `/bo/*` endpoint gets protected by
  wrapping it in this, without the endpoint itself knowing about auth.
- `Bootstrap.seedSuperAdminIfNeeded`: reads `BO_ADMIN_USERNAME`/
  `BO_ADMIN_PASSWORD` once, only when the operator store is empty; logs a
  warning and skips (no guessable default) if unset.

## Alternatives considered

- N/A — this ADR records completion of decisions already made in ADR-0004;
  no new alternatives were weighed here.

## Consequences

- The permission *model* is proven end-to-end; no permission *data entry
  UI* exists yet — permissions are only set via `OperatorRepository`
  directly (no `/bo/operators` CRUD endpoint yet).
- **Still not built**: Common Code CRUD (`/bo/common-code/*`), Device CRUD
  (`/bo/devices/*`) — the two screens this auth layer exists to protect;
  an Operator-management CRUD endpoint (create/edit operators and their
  permissions, currently only possible via the startup seed or direct
  repository calls); `RedisSessionStore` (real adapter — `SessionStore`
  is the one port in this repo with no real adapter yet, unlike
  `OrderRepository`/`CacheClient`/`EventPublisher`); CORS configuration
  (no Frontend origin exists yet to configure it against).
- Next session's natural starting point: Common Code CRUD — simplest
  data shape, establishes the CRUD-endpoint pattern the Device screens
  will reuse, per the build order `docs/adr/0004` already set (operator →
  Common Code → Device).

## References

- `docs/adr/0004` — the design this ADR reports back against.
- `src/test/java/.../transport/http/bo/BoAuthTest.java` — the live
  verification this ADR's "built and live-tested" claim rests on.
