# ADR-0003: Hexagonal ports + in-memory fakes by default, for infra this environment can't run live

- **Status**: Accepted
- **Date**: 2026-09-06
- **Deciders**: Project owner (asked to expand every planned piece at once); Claude (chose this pattern given the environment's constraints)

## Context

Asked to expand the whole stack from ADR-0002 in one pass (Socket/WebSocket
transports, Quartz-triggered Batch, MyBatis+Oracle, Redis, Kafka,
Micrometer/Prometheus, OpenTelemetry, Validation, Resilience4j), a hard
constraint surfaced immediately: **this development environment has no
Docker, no Oracle instance, no Redis, and no Kafka broker** — only a JDK
and (as of ADR-0002) a Gradle wrapper. Writing real MyBatis/Redisson/Kafka
adapters is straightforward; *verifying* them end-to-end, the way every
other piece of every `sun-moon-*` repo has been verified so far, is not
possible here without installing much heavier infrastructure (Docker
Desktop, at minimum, plus enabling virtualization) — a bigger ask than
installing a JDK, and not something to do unprompted mid-task.

`sun-moon-python-platform`'s own ADR-0008 ("testing with fakes instead of
real infra") already established a precedent for exactly this situation.

## Decision

For every piece that needs external infra this environment doesn't have
(Oracle, Redis, Kafka), the same shape is used:

1. **A port** (a plain interface in `domain` or `infrastructure`) —
   `OrderRepository`, `CacheClient`, `EventPublisher`.
2. **A real adapter** implementing it against the actual technology —
   `MyBatisOrderRepository` (Oracle via MyBatis+HikariCP),
   `RedissonCacheClient` (Redis via Redisson), `KafkaEventPublisher`
   (Kafka via the official client). These compile and are structurally
   correct, but **have not been run against a live instance in this
   environment** — no test exercises them, and nothing in `Bootstrap`
   constructs them by default.
3. **An in-memory fake adapter** — `InMemoryOrderRepository`,
   `InMemoryCacheClient`, `InMemoryEventPublisher`. These are what
   `Bootstrap` actually wires up, and what the test suite actually
   exercises (`OrderSummaryBatchJobTest`, `InMemoryCacheClientTest`,
   `InMemoryEventPublisherTest`).

Everything that needs **no** external infra was built and verified for
real, the same way ADR-0002's first slice was: Socket and WebSocket
transports (`CoreRuntimeTransportsTest` — a real TCP socket, a real
`java.net.http.HttpClient` WebSocket client, both against the actual
running Netty server), the Batch engine + Quartz
(`OrderSummaryBatchJobTest` — runs the real `ChunkStep` loop directly, and
again through a real Quartz `Scheduler` with a `RAMJobStore`), Micrometer
(`GET /metrics` returns real Prometheus-format scrape output — verified
with a live request, not a mock), OpenTelemetry (a real `LoggingSpanExporter`
span for every `/health` call, verified in the console log), Jakarta Bean
Validation and Resilience4j (both exercised live through `POST /orders` —
a blank `customerId` really returns `400 {"error":"must not be blank"}`).

A `docker-compose.yml` (Oracle via `gvenzl/oracle-free`, Redis, Kafka in
KRaft mode, Prometheus, Grafana) is included so the real adapters can
actually be brought up and tested once Docker is available — see
`docker-compose.yml` and `config/prometheus.yml`. Running it and switching
`Bootstrap` over to the real adapters is deliberately left as unfinished,
clearly-flagged future work, not claimed as done.

## Alternatives considered

- **Install Docker Desktop and stand up real infra** — not taken
  unprompted: it needs virtualization enabled and likely a reboot, a much
  bigger ask than the JDK/Gradle installs ADR-0002 already did with
  explicit permission each time. Left for the owner to decide when to do.
- **Write the real adapters and claim them "done" without caveats** —
  rejected: would misrepresent what's actually been verified, contrary to
  this project's discipline (every other `sun-moon-*` repo states plainly
  what's verified vs. not, e.g. `sun-moon-c-server`'s README "Known gap" notes).
- **Skip the real adapters entirely, only build fakes** — rejected: the
  owner asked for the whole stack to be expanded, and the real adapters
  are useful now even unverified (they're what gets used the moment
  Docker/Oracle/Redis/Kafka exist) — omitting them would mean redoing this
  work later instead of just flipping a wire-up in `Bootstrap`.

## Consequences

- `./gradlew run` and `./gradlew test` both work today, out of the box, on
  a machine with only a JDK — no external infra required, because
  `Bootstrap` defaults to the fakes.
- Switching to real infra later is a small, localized change: build
  `MyBatisConfig`/`RedissonClientFactory`/`KafkaEventPublisher.create(...)`
  instances in `Bootstrap` instead of the `InMemoryXxx` ones, run
  `FlywayMigrator.migrate(...)` once, bring up `docker-compose.yml`.
- **Not yet done, explicitly**: no test has ever run `MyBatisOrderRepository`,
  `RedissonCacheClient`, or `KafkaEventPublisher` against a live instance.
  Treat them as "compiles, believed correct, unverified" until that happens
  — the same epistemic status Alignment's own memory system would flag.
- Testcontainers (the natural next step for verifying these against real,
  ephemeral containers) is intentionally not added yet — it would need
  Docker too, and a dependency + test suite for infra that can't run here
  would just be dead weight. Add it alongside actually getting Docker.

## References

- `sun-moon-python-platform`'s ADR-0008 ("testing with fakes instead of
  real infra") — the direct precedent this ADR follows.
- ADR-0002 (this repo) — the stack this ADR expands, and the "small,
  verified steps" discipline this ADR is being explicit about deviating
  from for exactly three adapters.
