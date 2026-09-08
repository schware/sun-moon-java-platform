# ADR-0008: Common Code CRUD built; router made method-aware to support it

- **Status**: Accepted (built and live-verified)
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

`docs/adr/0006` named Common Code CRUD as the next BO screen after auth.
Building it surfaced a real limitation in `RestRequestRouter`
(`docs/adr/0002`'s original comment: "No regex/path-variable routing yet —
add it when a second endpoint actually needs it, not before"): the router
keyed routes by the full request URI string, so (a) a query string like
`?group=X` would never match a route registered without one, and (b) the
same path couldn't be registered twice for different HTTP methods —
exactly what CRUD needs (`GET`/`POST`/`PUT`/`DELETE` all on `/bo/common-code`,
each requiring a *different* one of the four 조회/신규/저장/삭제
permissions from `docs/adr/0004`'s model).

## Decision

**Router now keys by `(HttpMethod, path)`**, path with the query string
stripped (`RouteKey.java`, using Netty's `QueryStringDecoder`). Every
existing route in `Bootstrap` was updated to declare its method
explicitly (`GET /health`, `POST /orders`, etc.) — this is a required
consequence of the key type changing, not scope creep.

**Common Code**, built end-to-end:

- `CommonCode` domain record (`groupCode`, `code`, `name`, `sortOrder`,
  `active`) + `CommonCodeRepository` port.
- `InMemoryCommonCodeRepository` (default) and `MyBatisCommonCodeRepository`
  (PostgreSQL — unverified, `docs/adr/0005`) + `V3__create_common_code_table.sql`.
- Four endpoints under `/bo/common-code`, each wrapped in
  `AuthorizedEndpoint` with the matching `Action`: `GET` (list all, or one
  group via `?group=X`) → `VIEW`; `POST` (신규, 409 if the
  (groupCode, code) pair already exists) → `CREATE`; `PUT` (저장, 404 if
  it doesn't exist yet) → `SAVE`; `DELETE` (key in the body, no
  path-variable routing) → `DELETE`.
- `RequestValidation`, a small shared Jakarta-Validator helper, so the two
  new body-validating endpoints don't each re-declare a `Validator` field
  (`CreateOrderEndpoint` still has its own — left alone, not broken).

**Live-verified**: `CommonCodeCrudTest` (full create→list→save→delete
cycle, duplicate-create → 409, save-on-missing → 404, a view-only operator
getting 200 on `GET` but 403 on `POST`) plus a manual `./gradlew run` +
`Invoke-WebRequest` smoke pass — including Korean text (`대기`/`대기중`)
round-tripping correctly through the JSON API. 19 tests total, all passing.

## Alternatives considered

- **Path-variable routing** (`/bo/common-code/{group}/{code}`) — not
  built; the body-carries-the-key approach (already used for `DELETE`)
  works fine for CRUD without adding a routing library, and Device CRUD
  (next) can reuse the same pattern.
- **One `CommonCodeEndpoint` class dispatching internally on
  `request.method()`** (like `CreateOrderEndpoint` and `LoginEndpoint` did
  for their single method) — not chosen for the four Common Code
  operations, since each needs a *different* `Action` for the permission
  check, and `AuthorizedEndpoint` checks one fixed `(Screen, Action)` per
  wrapped instance. Four small classes, one per action, was simpler than
  teaching `AuthorizedEndpoint` to resolve the action from the method.

## Consequences

- Device CRUD can follow the exact same shape: domain record + port +
  fake/real adapter pair + Flyway migration + four
  `AuthorizedEndpoint`-wrapped classes under `/bo/devices`.
- `CreateOrderEndpoint`/`LoginEndpoint`'s own internal `HttpMethod` checks
  are now redundant (the router won't dispatch the wrong method to them
  regardless) but harmless — left as-is rather than touching stable, tested code.
- Per `docs/adr/0007`'s deployment checklist: Device CRUD is the one
  remaining item before deployment prep starts.

## References

- `docs/adr/0002` (this repo) — the router comment this ADR resolves.
- `docs/adr/0004`, `docs/adr/0006` (this repo) — the permission model this
  screen is the first real consumer of beyond the login flow itself.
