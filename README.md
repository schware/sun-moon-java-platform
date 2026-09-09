# sun-moon-java-platform

A Java, DDD-based **Enterprise Runtime Platform**: one [Netty](https://netty.io/)
runtime hosting Socket, REST API, WebSocket, and Batch Job together —
open-source-first, targeting 1,000-10,000 concurrent connections, and
deliberately **Spring-free**.

[**sun-moon-platform-core**](https://github.com/schware/sun-moon-platform-core)
was split out of this repository (`docs/adr/0014`) — the listener binding,
REST routing and off-event-loop execution contract — and arrives here as
the `core/` submodule. It is **archived (read-only)** now that this
runtime is its only consumer: it still clones and still builds, but
changing it means unarchiving it first (`docs/adr/0015`). Clone
accordingly:

```bash
git clone --recurse-submodules -b master https://github.com/schware/sun-moon-java-platform.git
```

**Back Office is no longer here.** It used to be `/bo/*` routes in this
process, then briefly its own Netty service; it is now
[sun-moon-java-platform-bo](https://github.com/schware/sun-moon-java-platform-bo),
a Spring service in the `main` branch's family — BO is administration with
no throughput requirement, and hand-writing session auth for it bought
nothing (`docs/adr/0015`). The Netty implementation is archived as
[sun-moon-platform-bo-netty](https://github.com/schware/sun-moon-platform-bo-netty).

📐 **[`docs/DESIGN.md`](docs/DESIGN.md)** ([한국어](docs/DESIGN_kr.md)) — the
as-built design: runtime topology, layering, batch engine, data model, and
an honest list of what isn't built yet. Start there. For *why* each decision was made, see [`docs/adr/`](docs/adr/).

🚀 **[`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)** ([한국어](docs/DEPLOYMENT_kr.md)) —
runbook for deploying to the owner's Debian server.

This is the Java counterpart to
[`sun-moon-python-platform`](../Python) and [`sun-moon-c-server`](../C) —
same `sun-moon-*` family, demonstrating the owner's 8 years of production
Java/Spring Boot experience the way those two demonstrate the Python and
C/C++ sides.

## Status

**Working scaffold, not a finished system.** Verified live (see
`docs/adr/0003`):

**Ports** (`docs/adr/0013`) — two listeners, this runtime's slots in the
family-wide scheme:

| Port | Listener | Serves | Override |
|---|---|---|---|
| 8083 | API | `/health`, `/metrics`, `/orders`, WebSocket `/ws` | `API_PORT` |
| 9011 | Socket | raw TCP (echo) | `SOCKET_PORT` |

- **Order API**: `POST /orders` (Jakarta Bean Validation + Resilience4j), WebSocket `/ws` (echo)
- **Batch**: a hand-rolled Job/Step/Chunk engine, triggered by a real Quartz `Scheduler`
- **Metrics**: Micrometer → Prometheus text format; **Tracing**: OpenTelemetry spans (logging exporter)

**Ready to deploy, not yet deployed**: `Dockerfile` and a runbook are in
place (`docs/adr/0011`, `docs/adr/0012`); the target is the owner's own
Debian server, and the remaining steps need `sudo` there. Endpoints run on a bounded worker
pool rather than the event loop (`docs/adr/0010`), so the real JDBC-backed
adapters can be switched on — setting `POSTGRES_JDBC_URL` is the switch.

**Not live-verified — no local Postgres/Redis/Kafka in this dev
environment, by choice** (see `docs/adr/0003`, `docs/adr/0005`): the real
`MyBatisOrderRepository` (PostgreSQL, swapped from Oracle),
`RedissonCacheClient` (Redis), and
`KafkaEventPublisher` (Kafka) adapters exist and compile, but `Bootstrap`
wires in their in-memory fakes by default, and no test exercises the real
ones. Verification is deferred to the actual server deployment. A
`docker-compose.yml` is included for local/CI use if that's ever wanted.

## Stack

| Concern | Choice | Live-verified here? |
|---|---|---|
| Core Runtime / Transport | Netty (raw — no Reactor Netty, no Spring WebFlux) | Yes |
| Batch Scheduler | Quartz (triggers a hand-rolled Job/Step/Chunk engine) | Yes |
| Persistence | MyBatis + PostgreSQL, HikariCP, Flyway | No — see `docs/adr/0005` |
| Cache / Session / Lock | Redis, Redisson | No — see `docs/adr/0003` |
| Event Bus | Kafka | No — see `docs/adr/0003` |
| Logging | Logback | Yes |
| Monitoring | Micrometer, Prometheus, Grafana | Micrometer/Prometheus format yes; Grafana not yet |
| Tracing | OpenTelemetry | Yes (logging exporter) |
| Validation | Jakarta Bean Validation (Hibernate Validator) | Yes |
| Resilience | Resilience4j | Yes (wraps the event-publish call) |
| JSON | Jackson | Yes |
| Testing | JUnit5, Mockito | Yes |

No Spring anywhere — see [`docs/adr/0002`](docs/adr/0002-ddd-enterprise-runtime-platform-on-netty-no-spring.md).

## Build & run

Requires JDK 21+. The Gradle wrapper is committed, so no local Gradle
install is needed.

```
./gradlew build
./gradlew test
./gradlew run
```

Then, from another shell:

```
curl http://localhost:8083/health
# {"status":"UP"}

curl http://localhost:8083/metrics
# Prometheus text-format scrape, including health_check_requests_total

curl -X POST http://localhost:8083/orders -H "Content-Type: application/json" -d "{\"customerId\":\"cust-1\",\"amount\":42.50}"
# 201 {"customerId":"cust-1","amount":42.50,"id":1}
```

See the port table above for defaults and the env vars that override them.
On startup, the Order Summary Batch job runs once immediately (against the
in-memory fake order data) and logs its report.

### Bringing up real infra (Postgres/Redis/Kafka/Prometheus/Grafana)

```
docker compose up -d
```

Then, in `Bootstrap.java`, swap the `InMemoryXxxRepository`/
`InMemoryCacheClient`/`InMemoryEventPublisher` constructions for the
`MyBatis*`/`RedissonCacheClient`/`KafkaEventPublisher` ones, and run
`FlywayMigrator.migrate(...)` once against the Postgres datasource. This
hasn't been done/tested here — no local database in this dev environment,
by choice (see `docs/adr/0005`, `docs/adr/0007`).

## Structure

```
core/                         the kernel, as a git submodule — Netty listener
                              binding, REST routing, off-event-loop execution,
                              shared MyBatis/Flyway/Micrometer/OTel wiring
src/main/java/com/sunmoon/platform/
  Bootstrap.java              composition root — manual wiring, no DI container
  PlatformConfig.java         this runtime's two ports and its worker-pool size
  api/                        the REST endpoints this runtime serves
  transport/ws/, transport/socket/   WebSocket and raw Socket transports
  batch/                      Job/Step/Chunk engine + Quartz scheduling
  domain/order/               records + ports
  infrastructure/persistence/ MyBatis+Postgres (real, unverified) / in-memory (fake, tested)
  infrastructure/cache/       Redisson (real, unverified) / in-memory (fake, tested)
  infrastructure/messaging/   Kafka (real, unverified) / in-memory (fake, tested)
```

Everything the kernel owns lives in `core/` and is imported from
`com.sunmoon.platform.core.*` / `.transport.http.*` / `.observability.*` —
no package is split between this repository and the submodule, so an import
tells you which side of the boundary a class is on.

Grows one package at a time as each piece is actually built — see
`docs/adr/` for the reasoning behind each addition as it happens.
