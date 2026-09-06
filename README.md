# sun-moon-java-platform

A Java, DDD-based **Enterprise Runtime Platform**: one [Netty](https://netty.io/)
runtime hosting Socket, REST API, WebSocket, and Batch Job together —
open-source-first, targeting 1,000-10,000 concurrent connections, and
deliberately **Spring-free**. See [`docs/adr/0002`](docs/adr/0002-ddd-enterprise-runtime-platform-on-netty-no-spring.md)
for the full reasoning, and [`docs/adr/`](docs/adr/) for everything else.

This is the Java counterpart to
[`sun-moon-python-platform`](../Python) and [`sun-moon-c-server`](../C) —
same `sun-moon-*` family, demonstrating the owner's 8 years of production
Java/Spring Boot experience the way those two demonstrate the Python and
C/C++ sides.

## Status

**Working scaffold, not a finished system.** Verified live (see
`docs/adr/0003`):

- **REST**: `GET /health`, `GET /metrics` (Prometheus scrape), `POST /orders` (Jakarta Bean Validation + Resilience4j)
- **WebSocket**: `/ws` (echo)
- **Raw Socket**: port 9090 (echo)
- **Batch**: a hand-rolled Job/Step/Chunk engine, triggered by a real Quartz `Scheduler`
- **Metrics**: Micrometer → Prometheus text format
- **Tracing**: OpenTelemetry spans (logging exporter)

**Not live-verified — no Docker/Oracle/Redis/Kafka in this dev
environment** (see `docs/adr/0003` for why and what that means): the real
`MyBatisOrderRepository` (Oracle), `RedissonCacheClient` (Redis), and
`KafkaEventPublisher` (Kafka) adapters exist and compile, but `Bootstrap`
wires in their in-memory fakes by default, and no test exercises the real
ones. A `docker-compose.yml` is included to stand up real infra when ready.

## Stack

| Concern | Choice | Live-verified here? |
|---|---|---|
| Core Runtime / Transport | Netty (raw — no Reactor Netty, no Spring WebFlux) | Yes |
| Batch Scheduler | Quartz (triggers a hand-rolled Job/Step/Chunk engine) | Yes |
| Persistence | MyBatis + Oracle, HikariCP, Flyway | No — see `docs/adr/0003` |
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
curl http://localhost:8080/health
# {"status":"UP"}

curl http://localhost:8080/metrics
# Prometheus text-format scrape, including health_check_requests_total

curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d "{\"customerId\":\"cust-1\",\"amount\":42.50}"
# 201 {"customerId":"cust-1","amount":42.50,"id":1}
```

Default HTTP port `8080`, Socket port `9090` — override with `HTTP_PORT` /
`SOCKET_PORT`. On startup, the Order Summary Batch job runs once
immediately (against the in-memory fake order data) and logs its report.

### Bringing up real infra (Oracle/Redis/Kafka/Prometheus/Grafana)

```
docker compose up -d
```

Then, in `Bootstrap.java`, swap the `InMemoryOrderRepository`/
`InMemoryCacheClient`/`InMemoryEventPublisher` constructions for
`MyBatisOrderRepository`/`RedissonCacheClient`/`KafkaEventPublisher`, and
run `FlywayMigrator.migrate(...)` once against the Oracle datasource. This
hasn't been done/tested here — no Docker in this dev environment (see
`docs/adr/0003`).

## Structure

```
src/main/java/com/sunmoon/platform/
  Bootstrap.java              composition root — manual wiring, no DI container
  core/                       Core Runtime (Netty bootstrap, config)
  transport/http/             REST transport (codec, router, endpoints)
  transport/ws/                WebSocket transport
  transport/socket/            raw Socket transport
  batch/                       Job/Step/Chunk engine + Quartz scheduling
  domain/order/                Order aggregate + OrderRepository port
  infrastructure/persistence/  MyBatis+Oracle (real, unverified) / in-memory (fake, tested)
  infrastructure/cache/        Redisson (real, unverified) / in-memory (fake, tested)
  infrastructure/messaging/    Kafka (real, unverified) / in-memory (fake, tested)
  observability/               Micrometer + OpenTelemetry wiring
```

Grows one package at a time as each piece is actually built — see
`docs/adr/` for the reasoning behind each addition as it happens.
