# sun-moon-java-platform

A Java, DDD-based **Enterprise Runtime Platform**: one [Netty](https://netty.io/)
runtime hosting Socket, REST API, WebSocket, and Batch Job together —
open-source-first, targeting 1,000-10,000 concurrent connections, and
deliberately **Spring-free**.

[**sun-moon-platform-core**](https://github.com/schware/sun-moon-platform-core)
was split out of this repository (`docs/adr/0014`) — the listener binding,
REST routing and off-event-loop execution contract — and arrives here as
the `core/` submodule. Clone accordingly:

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

## This runtime is the Device Server

This is what the runtime is *for* — the thing ADR-0014 left as "business
undecided" (`docs/adr/0016`). It holds the WebSocket connections for
terminals in the field (POS, KDS, DID), subscribes to the Spring
[Order service](https://github.com/schware/sun-moon-java-platform-order)'s
Redis events, and pushes each one to the terminals that should see it —
routed **per store**, because every shop has its own `pos-01`.

```
Order (Spring, :8083) ──Redis 'order-events'──▶ Device Server (:8087)
                                                       │
                          WebSocket, store-scoped ─────┼───▶ POS / KDS / DID terminals
                          REST proxy (fetch, accept) ──┘
```

- A terminal identifies itself **at the handshake**
  (`/ws?deviceId=pos-01&storeId=store-01&type=POS`) — a wrong or missing
  id is refused with HTTP 400, never an open socket that silently never
  receives anything.
- **The WebSocket carries a nudge, not the data.** A frame means
  "something changed"; the terminal then fetches the current list
  through this server's REST proxy. That is what lets a terminal that
  was briefly offline reconnect to the truth instead of to whatever
  frames it missed.
- An order placed at a store with **no terminal connected is refused
  immediately** — but a terminal seen within the last few minutes still
  counts as present, so a dropped wifi connection costs a delay, not a
  customer (`TERMINAL_GRACE`, default 3 minutes).
- **Verified live, end to end**: a real WebSocket client connecting as a
  named terminal, an order placed with no terminal present being
  auto-rejected, one placed with a terminal connected being pushed to it,
  and an accept call landing in Order as `ACCEPTED` with `acceptedBy`
  set — confirmed by querying Order directly.

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

**Deployed, and its core purpose is live-verified** (see `docs/adr/0016`).

**Ports** (`docs/adr/0013`, `docs/adr/0016`) — two listeners:

| Port | Listener | Serves | Override |
|---|---|---|---|
| 8087 | API | `/health`, `/metrics`, `/terminals`, `/orders` (proxy), WebSocket `/ws` | `API_PORT` |
| 9011 | Socket | raw TCP (echo) | `SOCKET_PORT` |

8083 was this runtime's port under ADR-0013/0014's "reserved, undecided"
framing; it moved to 8087 once the Device Server role made clear that
8083 belongs to the Spring Order service permanently.

- **Terminals**: `GET /terminals` (who's connected), `POST /terminals/push`
  and `POST /terminals/broadcast` (send to one device or every terminal of
  a kind at a store) — mostly an operator/debug surface now that Order's
  events drive pushes automatically.
- **Order proxy**: `GET /orders` and `POST /orders/status` forward to the
  Spring Order service so a terminal never needs to know where it lives.
- **Batch**: a hand-rolled Job/Step/Chunk engine, triggered by a real Quartz `Scheduler`
- **Metrics**: Micrometer → Prometheus text format; **Tracing**: OpenTelemetry spans (logging exporter)

**Redis Pub/Sub is live-verified** — this is the first real exercise of
the Redisson adapter, subscribing to Order's events and pushing to
connected terminals on the deployed server.

**Still not live-verified** — no local Postgres/Kafka in this dev
environment, by choice (see `docs/adr/0003`, `docs/adr/0005`): the real
`MyBatisOrderRepository` (PostgreSQL) and `KafkaEventPublisher` (Kafka)
adapters exist and compile, but back the runtime's **legacy**
`domain/order` module (see Known Gaps in `docs/DESIGN.md` §12) — not the
real order flow, which lives entirely in the Spring Order service now.

## Stack

| Concern | Choice | Live-verified here? |
|---|---|---|
| Core Runtime / Transport | Netty (raw — no Reactor Netty, no Spring WebFlux) | Yes |
| Batch Scheduler | Quartz (triggers a hand-rolled Job/Step/Chunk engine) | Yes |
| Persistence | MyBatis + PostgreSQL, HikariCP, Flyway | No — backs the legacy `domain/order` module only |
| Cache / Session / Lock | Redis, Redisson | **Pub/Sub: yes** (terminal event routing). Cache client: no |
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

```bash
./gradlew build
./gradlew test
./gradlew run
```

Then, from another shell:

```bash
curl http://localhost:8087/health
# {"status":"UP"}

curl http://localhost:8087/terminals
# {"count":0,"terminals":[]}

# A terminal connects with a store and device id — reject cases are
# refused before the WebSocket upgrade completes:
#   ws://localhost:8087/ws?deviceId=pos-01&storeId=store-01&type=POS

# Once a terminal is connected, an order placed at its store stays
# PLACED and is pushed to it; with none connected, Order rejects it
# within about half a second (the Device Server tells it nobody's there).
```

On startup, the Order Summary Batch job runs once against the legacy
in-memory order data and logs its report — unrelated to the real order
flow, which lives in the Spring Order service.

### Bringing up real infra (Postgres/Redis/Kafka/Prometheus/Grafana)

```bash
docker compose up -d
```

`REDIS_URL` (e.g. `redis://localhost:6379`) enables the live Order-event
subscription — without it the server still runs, terminals still connect,
they simply aren't pushed anything automatically. `POSTGRES_JDBC_URL` only
affects the legacy `domain/order` module described above.

## Structure

```
core/                         the kernel, as a git submodule — Netty listener
                              binding, REST routing, off-event-loop execution,
                              shared MyBatis/Flyway/Micrometer/OTel wiring
src/main/java/com/sunmoon/platform/
  Bootstrap.java              composition root — manual wiring, no DI container
  PlatformConfig.java         ports, worker-pool size, Order service URL, Redis URL, terminal grace
  api/
    TerminalEndpoints.java     GET /terminals, POST /terminals/push|broadcast
    OrderProxyEndpoints.java    GET/POST /orders(/status) — forwards to Spring Order
    CreateOrderEndpoint.java    legacy — see Known Gaps in docs/DESIGN.md
  domain/
    terminal/                  TerminalId, TerminalGroup, TerminalType, TerminalRegistry,
                                DeviceDirectory (+ AcceptKnownFormatDirectory)
    order/                     legacy — predates the Device Server role
  transport/
    ws/                        DeviceServerInitializer (composes the kernel's HTTP pipeline
                                with terminal handling), TerminalHandshakeHandler
                                (identifies before upgrade), TerminalFrameHandler
    socket/                    raw TCP transport
  infrastructure/
    order/OrderClient.java      talks to the Spring Order service over HTTP
    messaging/OrderEventSubscriber.java   subscribes to Order's Redis channel
    persistence/, cache/        legacy adapters behind the domain/order module
  batch/                       Job/Step/Chunk engine + Quartz scheduling
```

Everything the kernel owns lives in `core/` and is imported from
`com.sunmoon.platform.core.*` / `.transport.http.*` / `.observability.*` —
no package is split between this repository and the submodule, so an import
tells you which side of the boundary a class is on.

See `docs/DESIGN.md` §12 for what's built but not yet wired (device
authentication, one-order-terminal-per-store) and what's dead weight
worth removing (the legacy order module) — see `docs/adr/` for the
reasoning behind each addition as it happens.
