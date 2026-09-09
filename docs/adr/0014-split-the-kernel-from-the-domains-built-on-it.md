# ADR-0014: Split the kernel from the domains built on it — three repositories, one container each

- **Status**: Accepted — answers the question ADR-0013 left open, and narrows ADR-0002's single-runtime premise
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

ADR-0013 stopped the deployment and named the real question: not *which
port*, but **how many containers**. The port scheme the owner then gave
makes that concrete —

```
8000        hub (nginx) — everything is reached from here
8080        Java — BO
8081-8089   Java/Python/C — REST APIs
9011/9021/9031  Java/Python/C — Sockets
```

— because it allocates by *service*, and this repository shipped as one
container binding three ports in three different categories. No numbering
fixes that; the container has to be split.

Two further constraints came from the owner:

- BO is **internal only** for now, reachable from the LAN, not the
  internet. A service with that exposure has no business sharing a process
  with the device-facing API.
- BO gets its **own repository**, matching how the Spring services on the
  same server are organised.

That last point is what forced the design question. BO and the runtime
platform are not independent: they share the Netty listener binding, the
REST router, the off-event-loop execution contract (ADR-0010), and the
MyBatis/Flyway/Micrometer wiring. Copying that into a second repository
means two copies of the one rule that is hardest to keep — *endpoints
never run on an event-loop thread* — drifting apart quietly.

## Decision

**Extract the shared runtime into its own repository and have both
services depend on it, as a git submodule wired through a Gradle composite
build.**

```
sun-moon-platform-core     the kernel — a library, no main, no ports, no domain
sun-moon-platform-bo       BO (8080), core as a submodule
sun-moon-java-platform     the device-facing runtime, core as a submodule
```

The kernel is 15 classes: `CoreRuntime`, `ListenerSpec`, the HTTP
initializer and router, `RestEndpoint`/`RouteKey`/`JsonResponses`/
`RequestValidation`, health and metrics endpoints, `MetricsRegistry`,
`TracingConfig`, and the MyBatis/Postgres/Flyway wiring.

**The boundary was made to hold in place before any repository was
created.** Three things in the original leaked downward, and each was a
real coupling rather than a naming problem:

| Leak | Fix |
| --- | --- |
| `CoreRuntime` bound the socket port itself and constructed `SocketServerInitializer` | `CoreRuntime(List<ListenerSpec>)` — the caller names every listener, kernel binds them |
| `HttpServerInitializer` imported `WsEchoHandler` | takes a `Supplier<ChannelHandler>`; `null` means REST-only, which is what BO passes |
| `MyBatisConfig` listed four domain mappers | `buildSqlSessionFactory(dataSource, Class<?>... mappers)` |

`RuntimeConfig` did not move: it names `boPort` and `apiPort`, so a kernel
holding it would know which services exist. Each service has its own
config class instead (BO's is three fields, because BO has one listener).

Migrations stay with the service that owns the tables, so each numbers
from V1 and needs its own database. That is not silently wrong if
misconfigured — Flyway refuses to migrate a history table holding applied
migrations it cannot resolve locally.

**A submodule, not an artifact registry.** Publishing the kernel to a
registry would mean a token to create, store on the build machine, and
keep alive, for a dependency with exactly two consumers that live on the
same disk. `includeBuild("core")` costs nothing and keeps "change the
kernel, see what breaks" a single Gradle invocation. The trade is real
and accepted: a submodule pins a commit, so consumers upgrade by an
explicit pointer bump, and `git clone --recurse-submodules` becomes the
required clone command.

## Alternatives considered

- **Gradle multi-module inside one repository** — genuinely simpler, and
  it would enforce the same boundary. Rejected because the owner asked for
  BO to have its own repository, and because BO's LAN-only exposure is
  easier to keep true when it is a separate deployable with a separate
  release history.
- **Copy the shared classes into both repositories** — rejected. Two
  copies of the off-event-loop contract is exactly the drift this split
  exists to prevent.
- **Publish the kernel to GitHub Packages** — rejected for now; a token
  to maintain for two consumers on one machine. Reconsider if a third
  language's Java service appears, or if builds move to CI.
- **Leave the kernel in `sun-moon-java-platform` and have BO depend on
  that** — rejected: it makes BO depend on the order domain, the batch
  engine, and the socket transport to get a REST router.

## Consequences

- **Two containers where there was one.** BO deploys on 8080, LAN-only;
  the runtime platform keeps the socket transport and its API listener.
  This narrows ADR-0002 — "Socket, REST, WebSocket and Batch in a single
  runtime" is still the platform's shape, but BO was administration, not
  workload, and never belonged inside it.
- **The kernel is now separately testable, and tested.**
  `BlockingWorkOffloadTest` moved with it, so the invariant is proved in
  the repository that enforces it.
- **BO no longer compiles against the order domain, the batch engine, or
  the socket transport.** Every remaining `com.sunmoon.platform.*` import
  in BO names a kernel class — that is the boundary, checkable by grep.
- **BO's packages are `com.sunmoon.bo.*`.** Renaming them makes the
  direction of dependency visible at every import site rather than hidden
  in a build file.
- Verified end-to-end after the split: BO starts from its own
  `installDist` distribution, serves `/health`, refuses `/bo/devices`
  unauthenticated with 401, logs in, and round-trips Common Code CRUD —
  12 BO tests and 3 kernel tests green.
- **Clone instructions change** for both consumers
  (`--recurse-submodules`), and a kernel change is now a three-repository
  commit sequence: core, then each consumer's pointer.

## References

- ADR-0013 (withdrew 8084, named this question), ADR-0002 (single-runtime
  premise, now narrowed), ADR-0010 (the off-event-loop contract the kernel
  carries), ADR-0009 (port separation), ADR-0004/0006 (BO session auth).
- `Alignment` ADR-0007 — "prefer process isolation over port-splitting".
