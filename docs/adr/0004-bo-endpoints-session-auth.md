# ADR-0004: BO (Back Office) as `/bo/*` endpoints, session-based auth, CRUD-only scope

- **Status**: Accepted (scope, placement, auth approach); domain models and exact endpoints — **Ongoing**
- **Date**: 2026-09-08
- **Deciders**: Project owner (scope, sequencing, and the session-vs-JWT call); Claude (laid out the session-vs-JWT tradeoffs and the current OWASP-guidance context)

## Context

The original plan was REST API Server → Client (device) application next. The
owner found that building the Client side first doesn't work — a Client
(a connected device/terminal, in the same sense as the owner's own C# POS
Client production background, ADR-0005 in `Alignment`) needs somewhere to
be registered, and needs operators/reference data to exist first. So BO
(Back Office — the internal admin system) moves ahead of Client in the
build order.

Two scope-narrowing decisions came out of the same conversation:

1. **"Client" in this project means a connected device/terminal, not a
   SaaS tenant.** Device *connection*/handshake handling is explicitly
   **not** BO's job — a separate, not-yet-designed **Device Server** will
   own that (owner's words: "핸드셰이크 부분인 BO가 아닌 Device Server를
   새로 만들어서 접근할께요"). BO's relationship to devices is
   **CRUD only** — device master-data registration/lookup, not live
   connection state.
2. **BO needs its own login** for internal operators, separate from
   whatever auth the eventual Client-facing side uses.

## Decision

**Placement**: BO lives inside `sun-moon-java-platform`, as new REST
endpoints under `/bo/*`, following the same `RestEndpoint`/
`RestRequestRouter` pattern already used for `/health`, `/metrics`, and
`/orders` — not a separate service. (Repo: `github.com/schware/sun-moon-java-platform`,
not yet wired as this local clone's `git remote`.)

**Scope, minimal-first** (owner's own framing: "너무 큰 접근보다는
최소화의 방향으로"):

1. BO operator/admin accounts — login, and the permission model they
   need (roles are still open — see Consequences).
2. Common Code (공통코드) management — CRUD over shared reference/code tables.
3. Device management — CRUD over device master data only. Explicitly
   **excludes** connection state, handshake, or anything live-socket-related
   — that's the Device Server's job, out of scope here.

**Suggested build order**: operator accounts first (everything else needs
someone logged in to be useful), then Common Code (simplest CRUD, sets the
pattern), then Device CRUD (touches the existing `domain`/`infrastructure`
persistence layers most).

**Auth: session-based, via `HttpOnly`+`Secure`+`SameSite` cookie.** Chosen
over JWT after laying out both:

- **Session** — server issues an opaque token, sets it in a cookie,
  holds the actual state (operator id, roles) server-side.
  Immediate revocation (delete the record); needs server-side state.
- **JWT** — server issues a signed, self-contained token; client sends it
  as `Authorization: Bearer`; no server-side state, but revocation before
  expiry needs a denylist anyway, which reintroduces state for that one purpose.

Reasoning for session over JWT, specific to BO:

- BO is a first-party SPA talking only to its own backend — not a public
  API, not multiapp/mobile. Current OWASP guidance and modern auth
  libraries (Auth.js, Lucia) have moved back toward `HttpOnly` cookie +
  server-side session for exactly this shape of app, after the
  ~2015-2020 JWT-in-localStorage era turned out to have real problems
  (XSS-exposed tokens, no real revocation) for this use case. JWT remains
  the right choice for public/third-party APIs and mobile — not this.
- An admin panel specifically wants **immediate** access revocation on a
  compromised/offboarded operator account — session's core strength.
- The Core Runtime is a single process today — JWT's main advantage
  (statelessness for horizontal scaling) isn't needed yet.
- Fits the hexagonal ports-+-fakes pattern already used for
  `OrderRepository`/`CacheClient`/`EventPublisher` (see `docs/adr/0003`):
  a `SessionStore` port, an `InMemorySessionStore` fake wired in by
  default now, a `RedisSessionStore` real adapter later (Redis is already
  in the planned stack for exactly this — "Cache/Session/Lock" — just not
  live-verified in this dev environment yet).

## Alternatives considered

- **JWT + refresh token** — the modern "trendy" choice for OAuth2/OIDC-style
  systems; rejected as over-engineered for a first-party admin panel with
  no third-party consumers.
- **BO owning device connection/handshake logic directly** — rejected by
  the owner; that's a live-socket/protocol concern, structurally different
  from BO's CRUD-only shape, and belongs in a separate Device Server.
- **A separate service for BO** instead of new endpoints in
  `sun-moon-java-platform` — not chosen; the owner explicitly asked to add
  it as endpoints in the existing repo.

## Consequences

- BO needs a `SessionStore` port + `InMemorySessionStore` (same shape as
  `CacheClient`/`InMemoryCacheClient`), a login endpoint, a session-cookie
  check on every `/bo/*` request, and CORS configured for
  `credentials: include` once a Frontend origin exists.
- **Not yet decided**: exact `/bo/*` endpoint list per area, the Operator
  permission/role model (a single admin role, or something finer-grained),
  the Common Code and Device data models (and whether they go through
  MyBatis+Oracle like `OrderRepository` — likely, but not yet stated),
  CORS/cookie specifics once the Frontend's actual origin/dev setup is
  known, and whether `RedisSessionStore` gets built now (unverified, like
  the other Redis-backed pieces) or deferred.
- The Device Server itself is out of scope for this ADR entirely — a
  separate design conversation once BO's CRUD layer exists.

## References

- `Alignment`'s ADR-0005 — the C# POS Client production background this
  project's "Client = device" framing traces back to.
- `docs/adr/0002`, `docs/adr/0003` (this repo) — the Netty/no-Spring stack
  and the hexagonal ports-+-fakes pattern this ADR extends to BO.
- OWASP Session Management Cheat Sheet; general 2020s-era guidance moving
  first-party SPA auth back toward `HttpOnly` cookies over
  JWT-in-localStorage.
