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

**Ports** (`docs/adr/0009`) — one runtime, three listeners:

| Port | Listener | Serves | Override |
|---|---|---|---|
| 8080 | BO | `/health`, `/metrics`, `/bo/*` | `BO_PORT` |
| 8083 | Order API | `/health`, `/orders`, WebSocket `/ws` | `API_PORT` |
| 9090 | Socket | raw TCP (echo) | `SOCKET_PORT` |

- **BO auth** (`docs/adr/0004`/`0006`): `POST /bo/auth/login`/`logout`, `GET /bo/auth/me`, session cookie (`HttpOnly`+`SameSite=Lax`), BCrypt password hashing, a 3-tier permission model (super admin / per-screen / per-screen-per-action), enforced via an `AuthorizedEndpoint` decorator. First operator is seeded on startup from `BO_ADMIN_USERNAME`/`BO_ADMIN_PASSWORD`.
- **BO Common Code CRUD** (`docs/adr/0008`) and **BO Device CRUD** (`docs/adr/0009`): `GET`/`POST`/`PUT`/`DELETE` on `/bo/common-code` and `/bo/devices` — each permission-checked against the matching 조회/신규/저장/삭제 action. Devices are master data only; connection state belongs to the (unbuilt) Device Server.
- **Order API**: `POST /orders` (Jakarta Bean Validation + Resilience4j), WebSocket `/ws` (echo)
- **Batch**: a hand-rolled Job/Step/Chunk engine, triggered by a real Quartz `Scheduler`
- **Metrics**: Micrometer → Prometheus text format; **Tracing**: OpenTelemetry spans (logging exporter)

**Deployment target picked, not yet deployed** (`docs/adr/0007`): Render
(app, publishing `BO_PORT`) + Neon (Postgres), both permanently free as of
2026-09. All BO screens now exist; next up is the `Dockerfile`.

**Not live-verified — no local Postgres/Redis/Kafka in this dev
environment, by choice** (see `docs/adr/0003`, `docs/adr/0005`): the real
`MyBatisOrderRepository`/`MyBatisOperatorRepository`/`MyBatisCommonCodeRepository`
(PostgreSQL, swapped from Oracle), `RedissonCacheClient` (Redis), and
`KafkaEventPublisher` (Kafka) adapters exist and compile, but `Bootstrap`
wires in their in-memory fakes by default, and no test exercises the real
ones. Verification is deferred to the actual Render+Neon deployment. A
`docker-compose.yml` is included for local/CI use if that's ever wanted.

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
# BO (8080)
curl http://localhost:8080/health
# {"status":"UP"}

curl http://localhost:8080/metrics
# Prometheus text-format scrape, including health_check_requests_total

# BO needs a session; set BO_ADMIN_USERNAME/BO_ADMIN_PASSWORD before ./gradlew run,
# then log in and keep the cookie:
curl -c cookies.txt -X POST http://localhost:8080/bo/auth/login \
  -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"...\"}"
curl -b cookies.txt http://localhost:8080/bo/devices

# Order API (8083)
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
src/main/java/com/sunmoon/platform/
  Bootstrap.java              composition root — manual wiring, no DI container
  core/                       Core Runtime (Netty listeners, config)
  transport/http/             REST transport (router, listener specs, endpoints)
  transport/http/bo/          BO auth + the AuthorizedEndpoint permission decorator
  transport/http/bo/commoncode/, .../device/   BO screens (CRUD endpoints)
  transport/ws/, transport/socket/             WebSocket and raw Socket transports
  batch/                      Job/Step/Chunk engine + Quartz scheduling
  domain/                     order, operator, commoncode, device — records + ports
  infrastructure/persistence/ MyBatis+Postgres (real, unverified) / in-memory (fake, tested)
  infrastructure/auth/        session store + BCrypt hashing
  infrastructure/cache/       Redisson (real, unverified) / in-memory (fake, tested)
  infrastructure/messaging/   Kafka (real, unverified) / in-memory (fake, tested)
  observability/              Micrometer + OpenTelemetry wiring
```

Grows one package at a time as each piece is actually built — see
`docs/adr/` for the reasoning behind each addition as it happens.
