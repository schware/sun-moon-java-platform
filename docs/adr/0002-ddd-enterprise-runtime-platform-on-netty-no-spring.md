# ADR-0002: A Spring-free, Netty-based DDD Enterprise Runtime Platform

- **Status**: Accepted (the shape and stack below); several specifics
  remain open — see Consequences
- **Date**: 2026-09-06
- **Deciders**: Project owner, with stack suggestions from Claude

## Context

This repo exists to demonstrate the owner's 8 years of production
Java/Spring Boot experience (see `Alignment`'s ADR-0005), the same way
`sun-moon-python-platform` and `sun-moon-c-server` demonstrate the Python
and C/C++ sides of the same background. The direction was worked out over
several turns in `Alignment` before any code was written here
(`Alignment`'s ADR-0008 → 0009 → 0010):

1. ADR-0008 originally scoped Java narrowly, as just a Spring Batch
   reference implementation in a three-way Job/Step/Chunk comparison
   against `sun-moon-python-platform`'s `batch-service` and
   `sun-moon-c-server`'s `batch_runner`.
2. ADR-0009 records the owner expanding this into a full **DDD-based
   Enterprise Runtime Platform**: one Java runtime hosting Socket, REST
   API, WebSocket, and Batch Job together, open-source-first, targeting
   1,000-10,000 concurrent connections — structurally the same ambition as
   `sun-moon-c-server` (one runtime, multiple transports), not a Batch-only demo.
3. ADR-0010 records the owner rejecting Claude's suggestion to add Spring
   (Core DI + Spring Batch + Spring WebFlux) to that stack, keeping the
   originally-proposed stack instead.

## Decision

**Stack, confirmed 2026-09-06, no Spring anywhere:**

- **Netty** — Core Runtime / Transport Engine. Handles Socket, REST API,
  and WebSocket directly on its own `ChannelPipeline` mechanism (no
  Reactor Netty, no Spring WebFlux).
- **Quartz** — Batch Scheduler. Triggers batch runs; does not supply the
  Job/Step/Chunk model itself (see below).
- **MyBatis + Oracle** — Persistence Layer.
- **Redis** — Cache / Session / distributed Lock.
- **Kafka** — Event Bus.
- **Logback** — Logging.
- **Micrometer / Prometheus / Grafana** — Monitoring.
- **OpenTelemetry** — Distributed Tracing.

**Also planned, Spring-independent** (suggested in `Alignment` ADR-0009,
unaffected by the Spring drop): HikariCP (connection pooling), Jackson
(JSON), Jakarta Bean Validation/Hibernate Validator (input validation),
Resilience4j (circuit breaker/retry/rate limiter), Redisson (distributed
locking), Flyway (Oracle schema migrations), JUnit5 + Mockito +
Testcontainers (tests verified against real instances, not just mocks).

**No DI container.** Dependency wiring happens by hand in a composition
root (`Bootstrap.java`) — see `src/main/java/com/sunmoon/platform/Bootstrap.java`.

**No Spring Batch.** Quartz triggers a hand-rolled Job/Step/Chunk/Reader/
Processor/Writer engine written directly in Java (interfaces/classes),
the same way `sun-moon-c-server`'s `batch_runner` hand-rolled the same
pattern in C (a struct of function pointers standing in for Reader/
Processor/Writer — see that repo's ADR-0001). This means Java's Batch
implementation is no longer "the one using the canonical framework" the
way `Alignment`'s ADR-0008 originally framed it — its differentiator is
now the owner's 8 years of production Java/OOP design judgment, not
framework availability.

**Design constraint carried from `Alignment`'s ROADMAP.md**: Netty's
event-loop threads must never block, but MyBatis + Oracle (JDBC) calls are
blocking by nature. Domain-service calls that hit Oracle must be handed
off to a separate worker thread pool, with the result bridged back onto
the Netty channel — never called inline on an I/O thread. This needs
deliberate handling precisely because there's no Spring/framework layer
to paper over it.

**First slice, built and verified 2026-09-06**: a minimal Core Runtime
(`CoreRuntime.java`) binding one Netty `ServerBootstrap` to
`HttpServerInitializer`, serving `GET /health` (`HealthCheckEndpoint.java`,
routed through `RestRequestRouter.java`) — returns `{"status":"UP"}`,
verified end-to-end with `./gradlew run` + a live HTTP request, then a
clean shutdown. This is deliberately the smallest possible proof the Core
Runtime works, matching how `sun-moon-python-platform` and
`sun-moon-c-server` both started (small, verified steps, not the whole
stack at once). Socket, WebSocket, Batch, and every stack item above are
added the same way, one at a time, as each is actually built.

**Toolchain**: JDK 21 (Eclipse Temurin), Gradle 8.11 (Kotlin DSL,
wrapper committed to the repo). Neither was present on the development
machine before this session — both were installed as part of starting
this repo (JDK via `winget`, Gradle via the official
`services.gradle.org` binary distribution, used once to generate the
wrapper).

## Alternatives considered

- **Spring Core DI + Spring Batch + Spring WebFlux** — `Alignment`'s
  ADR-0009 suggestion; rejected by the owner in ADR-0010. See that ADR for
  the full reasoning and its consequence for the "reference
  implementation" narrative.
- **Maven instead of Gradle** — not chosen; no strong reason either way
  was raised, Gradle (Kotlin DSL) was picked as the more common default
  for new JVM projects today. Revisit if this turns out to be friction.
- **A DI-container replacement (Guice/Dagger) instead of manual wiring**
  — not chosen for this first slice; manual wiring is simpler while the
  object graph is still small (one runtime, one endpoint). Revisit once
  the graph grows enough that manual wiring becomes its own maintenance burden.

## Consequences

- This repo can now build (`./gradlew build`) and run
  (`./gradlew run`) end-to-end on a clean machine, given only JDK 21.
- **Still open**: exact package boundaries for the DDD layers (domain/
  application/infrastructure) beyond the `core`/`transport` split that
  exists so far; whether manual wiring stays viable as more
  services/adapters are added; repo's actual GitHub name (working name:
  `sun-moon-java-platform`, matching the family convention).
- Every stack item listed above beyond Netty+HTTP is **not yet
  implemented** — this ADR records the *plan*, not a finished system. Each
  gets its own follow-up ADR (or an update noting "implemented, see
  commit X") as it's actually built, per ADR-0001's rule.

## References

- `Alignment`'s ADR-0008, ADR-0009, ADR-0010 — the full history of how
  this stack was arrived at.
- `sun-moon-c-server`'s ADR-0001 — the hand-rolled Job/Step/Chunk
  precedent this repo's future Batch engine will follow.
- `sun-moon-python-platform`'s ADR-0002/ADR-0003 — the sibling
  "language as implementation choice" / DDD ADRs this one mirrors in shape.
