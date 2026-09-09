# ADR-0004: BO joins this family as a Spring service, replacing its Netty implementation

- **Status**: Accepted
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

Back Office — operator accounts, 공통코드, device master data — was built
in the `sun-moon-java-platform` **`master`** branch: the Spring-free,
Netty-based rebuild that shares this repository's name but none of its
code. It was later extracted into its own repository and its own
container.

That extraction is what exposed the question. While BO lived inside a
single Netty runtime, being Spring-free was not a choice — you cannot run
Spring MVC and raw Netty channel pipelines in one process. Once BO became
a separate deployable, nothing forced it either way, and the honest
comparison became possible:

| BO needs | Netty version | Spring |
| --- | --- | --- |
| Session auth | `SessionStore`, cookie parsing, `LoginEndpoint` | `starter-security` |
| BCrypt | `PasswordHasher` | `PasswordEncoder` |
| Per-screen, per-action authorization | `AuthorizedEndpoint` decorator | `@PreAuthorize` |
| CRUD endpoints | four classes per screen | one `@RestController` |
| CSRF | absent | on by default |
| Distributed sessions (unbuilt) | a `RedisSessionStore` to write | Spring Session, one dependency |

**BO has no throughput requirement.** Its users are a handful of
operators. Netty's reason to exist — event loops, ten thousand
connections — buys BO nothing, so BO was paying the whole cost of having
no framework and collecting none of the benefit.

It was also the only service on this server not built like its siblings:
different stack, different deployment shape, different everything, for the
most conventional workload in the system.

## Decision

**BO is rebuilt as `sun-moon-java-platform-bo`, the fourth service in this
family, and joins as a submodule here.**

It follows the conventions Order/KDS/Delivery already share — Spring Boot
3.3.4, embedded Jetty rather than Tomcat, `JdbcTemplate` rather than an
ORM, `schema.sql` rather than Flyway, springdoc pinned at 2.6.0,
context-path, `RootRedirectController`, the path-redaction trio, one
container. Those conventions are now written down and, more importantly,
made checkable: see [`../CONVENTIONS.md`](../CONVENTIONS.md).

The three permission tiers survive intact, expressed as Spring Security
authorities: `super_admin` → `ROLE_SUPER_ADMIN`, and each allowed action →
`SCREEN_ACTION`, so a controller states its requirement inline and an
unprotected method reads as a missing annotation. `create` and `save` stay
separate rather than becoming an upsert, because 신규 and 저장 are
separate permissions.

Storage is plain relational columns rather than the JSONB Order and
Delivery use — theirs was designed for a document store (ADR-0001), BO's
data is relational. Per-service choice, same as KDS on Redis.

The Netty implementation is archived as `sun-moon-platform-bo-netty`,
following the `-jetty` convention already used for the superseded WAR
versions.

## Alternatives considered

- **Keep the Netty BO and link it from here** — rejected. It would leave
  the family with two stacks, two deployment shapes and two sets of
  conventions for no gain, and BO would keep paying for a runtime property
  it does not use.
- **Move the device-facing runtime to Spring too** — not part of this
  decision. That runtime exists *because* it needs raw sockets, a
  WebSocket transport and a 1,000-10,000 connection target; those are the
  things Netty is for. It stays on `master` with its kernel.
- **Port BO's hand-written auth into a Spring service** — rejected. The
  code being replaced is precisely the code Spring Security already
  provides, tested by more people than will ever read this repository.

## Consequences

- **BO's remaining gaps get cheaper.** The Operator management screen,
  CORS and distributed sessions were the three things listed as unbuilt;
  all three are configuration or a controller in Spring rather than new
  mechanism.
- **The portfolio argument changes, and should be stated honestly.** The
  Netty BO demonstrated building session auth and a permission model by
  hand; that artifact still exists, archived, and the reasoning survives
  in its ADRs. This repository now demonstrates something different and
  more common in practice — recognising that a service had the wrong
  amount of machinery for its job, and reversing it.
- **CSRF is now on**, which changes how BO is called: fetch
  `/bo/auth/csrf`, then send `X-XSRF-TOKEN`. The token rotates at login.
- **A fourth database**, `bo_service`.
- **The kernel `sun-moon-platform-core` loses a consumer**, leaving one:
  the device-facing runtime. Whether a kernel with a single consumer is
  still worth a separate repository is a fair question, and is left open
  rather than answered here.

## References

- ADR-0001 (per-service database choice), ADR-0003 (Docker replaces shared
  Jetty), [`../CONVENTIONS.md`](../CONVENTIONS.md).
- `master` branch ADR-0004/0006 (BO's original scope, session auth and
  3-tier permissions — the design this rewrite preserves), ADR-0014 (the
  repository split whose BO half this supersedes).
