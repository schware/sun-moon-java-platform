*🇰🇷 Korean version: [DESIGN_kr.md](DESIGN_kr.md)*

# sun-moon-java-platform — Design

As-built design of this repository as of 2026-09-09. This describes what
exists and runs, and states plainly what doesn't — the "Known gaps"
section at the end is part of the design, not an afterthought.

For *why* each decision was made, see [`docs/adr/`](adr/); this document
describes the resulting system. ADR references appear throughout.

> **Note on the repo name**: `sun-moon-java-platform` on GitHub hosts two
> unrelated codebases on two branches. `main` is a pre-existing Spring
> Boot/microservices system (Order/KDS/Delivery). `master` — this branch —
> is the from-scratch, Spring-free rebuild described here.

---

## 1. What this is

The **Device Server** of the `sun-moon-*` platform: one JVM process,
built on Netty, that holds terminal connections (POS, KDS, DID) and
routes order events to them (`docs/adr/0016`). No Spring anywhere
(ADR-0002, ADR-0010 in `Alignment`).

It reached this shape by way of a broader ambition — a Java **Enterprise
Runtime Platform** hosting Socket, REST, WebSocket and Batch in one
process, DDD-structured, targeting 1,000-10,000 concurrent connections
(ADR-0002). That ambition explains why it's built the way it is (raw
Netty, hand-rolled everything); the Device Server role is *what* it does
with that shape.

It is the Java member of the `sun-moon-*` family, alongside
`sun-moon-python-platform` and `sun-moon-c-server`, which prove the same
architectural ideas in Python and C.

## 2. Principles that actually drive the design

1. **One runtime, several transports.** Socket, REST, WebSocket and Batch
   live in one process sharing one pair of Netty event-loop groups
   (ADR-0002). Ports separate *exposure*, not processes (ADR-0009).
2. **No framework magic.** No DI container, no annotation scanning, no
   Spring Batch. Everything is wired by hand in one composition root
   (`Bootstrap.java`), and the Job/Step/Chunk engine is written out
   explicitly. The cost is more code; the benefit is that every mechanism
   is inspectable.
3. **A terminal is told "something changed," not handed the change.**
   The WebSocket carries a nudge; the terminal fetches the truth over
   REST. This is what lets a terminal that dropped off reconnect to
   *current state* rather than to a replay of frames it may have missed
   (ADR-0016).
4. **Identify before you commit.** A terminal is checked at the WebSocket
   handshake, before the upgrade completes — a wrong id gets an HTTP 400
   it can read, not a socket that silently never delivers anything.
5. **Ports and adapters, with fakes as the default**, for the parts that
   still use it (the legacy order module, §8). A real adapter that
   compiles but is **not** live-verified in this environment sits behind
   the same interface as an in-memory fake wired in by default (ADR-0003,
   ADR-0005). `./gradlew run` and `./gradlew test` work on a machine with
   nothing but a JDK.
6. **Say what is verified.** Claims in docs distinguish "tested against a
   running server" from "compiles". See §11.

## 3. Runtime topology

One process. One `NioEventLoopGroup` boss (1 thread) + one worker group
(Netty default: 2 × available cores), shared by every listener.

```
                       ┌──────────────────────── one JVM ─────────────────────────┐
                       │                                                          │
  Terminals    ──8087──┼─▶ HTTP/WS listener "API" ─┐                             │
  (POS/KDS/DID)        │  /health /metrics          │                             │
                       │  /terminals /orders  /ws   ├─▶ shared boss + worker      │
                       │                            │   event-loop groups        │
  (raw socket) ──9011──┼─▶ Socket listener         ─┘                             │
                       │                                                          │
                       │   Redis 'order-events' ──▶ OrderEventSubscriber          │
                       │   (subscribed at startup, pushes to connected terminals) │
                       │                                                          │
                       │   Quartz scheduler ─▶ Batch engine (own thread pool)     │
                       └──────────────────────────────────────────────────────────┘
                                        │
                                        ▼ (REST proxy)
                         Spring Order service (sun-moon-java-platform-order, :8083)
```

| Port | Listener | Serves | WebSocket | Env override |
|---|---|---|---|---|
| 8087 | API | `/health`, `/metrics`, `/terminals`, `/orders`(proxy), `/ws` | yes | `API_PORT` |
| 9011 | Socket | raw TCP (echo) | — | `SOCKET_PORT` |

These are this runtime's slots in the family-wide scheme (ADR-0013): the
8081-8089 band for REST APIs, 90x1 for sockets by language. 8083, the
number this runtime used to reserve, belongs to the Spring Order service
permanently — that was the point ADR-0016 settled (`docs/adr/0016`).

**Threading.** Event-loop threads run the codec, the WebSocket handshake
check and the router only. `RestEndpoint.handle()` is dispatched to a
**bounded worker pool** (`platform-worker-N`, sized by `WORKER_THREADS`,
default `availableProcessors × 4`), because the order-proxy endpoints make
a blocking HTTP call to the Spring Order service — inline on the event
loop, that would stall every connection the loop serves (ADR-0010).
Responses return via `ctx.writeAndFlush`, which hands back to the event
loop itself. Pool saturation answers 503; an endpoint that throws answers
500. Terminal WebSocket frames are handled directly on the event loop
(`TerminalFrameHandler`) since nothing there blocks — registering a
connection and writing a push frame are both non-blocking. Batch runs on
Quartz's own `SimpleThreadPool`, also off the event loop.

## 4. Layering

Hexagonal, expressed as packages under `com.sunmoon.platform`:

```
core/               the kernel, a git submodule (com.sunmoon.platform.core,
                      .transport.http, .observability, .infrastructure.persistence)
                      — listener binding, REST routing, off-event-loop execution,
                      shared MyBatis/Flyway/Micrometer/OTel wiring
api/
  TerminalEndpoints.java      GET /terminals, POST /terminals/push|broadcast
  OrderProxyEndpoints.java    GET/POST /orders(/status) — forwards to Spring Order
  CreateOrderEndpoint.java    legacy (§12)
domain/
  terminal/           TerminalId, TerminalGroup, TerminalType, TerminalSession,
                       TerminalRegistry, DeviceDirectory (+ AcceptKnownFormatDirectory)
  order/              legacy (§12) — predates the Device Server role
transport/
  ws/                 DeviceServerInitializer, TerminalHandshakeHandler,
                       TerminalFrameHandler
  socket/             raw TCP handler
infrastructure/
  order/              OrderClient — talks to the Spring Order service over HTTP
  messaging/          OrderEventSubscriber (Redis), legacy EventPublisher adapters
  persistence/, cache/  legacy adapters behind domain/order
batch/              Job/Step/Chunk engine + Quartz scheduling
Bootstrap.java      composition root — the only place implementations are chosen
PlatformConfig.java ports, worker-pool size, Order service URL, Redis URL, terminal grace
```

**Dependency rule.** `domain` depends on nothing but the JDK. `api`/
`transport` and `infrastructure` both depend on `domain`, never on each
other. The kernel depends on none of them — it is a library this runtime
composes, and no package is split between the two, so an import tells you
which side of the boundary a class is on (ADR-0014).
`Bootstrap` is the single place that knows which adapter implements which
port — swapping the whole system from fakes to real infrastructure is a
change to that one file.

**Why `transport/ws` composes its own pipeline instead of using the
kernel's `HttpServerInitializer`.** The kernel only accepts a frame
handler for WebSocket, which is correct — letting it accept a
pre-handshake handler would mean the kernel knowing what a terminal is
(ADR-0014). `TerminalHandshakeHandler` has to run *before* the WebSocket
upgrade so a bad connection gets an HTTP 400 rather than a dead socket,
which the kernel's contract has no room for. So `DeviceServerInitializer`
assembles `HttpServerCodec` → `HttpObjectAggregator` →
`TerminalHandshakeHandler` → `WebSocketServerProtocolHandler` →
`TerminalFrameHandler` → `RestRequestRouter` itself, out of kernel parts
plus this repository's own handlers.

Domain models are Java `record`s. Ports are interfaces declared in the
domain package they serve (e.g. `domain.terminal.DeviceDirectory`).

## 5. Request lifecycle

### A terminal connecting

```
TCP → HttpServerCodec → HttpObjectAggregator → TerminalHandshakeHandler
    → [reject: HTTP 400] or [continue] → WebSocketServerProtocolHandler
    → TerminalFrameHandler.userEventTriggered(HandshakeComplete)
    → TerminalRegistry.register(deviceId, storeId, type, channel)
```

`TerminalHandshakeHandler` reads `deviceId`, `storeId` and `type` from the
query string and validates them *before* the WebSocket upgrade — a
missing or malformed value is answered with an HTTP 400 and the
connection is closed, never upgraded. Once the handshake completes,
`TerminalFrameHandler` registers the connection and sends a `WELCOME`
frame. Registering a second connection for the same `(storeId, deviceId)`
displaces the first, which is closed with WebSocket code 4001 so its
client stops retrying rather than fighting for an id it no longer holds.

### An order event reaching a terminal

```
Order service → Redis 'order-events' → OrderEventSubscriber.onEvent()
    → audienceFor(status) picks POS/KDS/DID
    → TerminalRegistry.channelsOf(storeId, type).writeAndFlush(event)
```

If the audience is empty and the status is `PLACED`,
`isPresentOrRecentlySeen` is checked first: within the grace period
(default 3 minutes since a terminal of that kind was last seen at that
store) the event is dropped and the order stays `PLACED`, on the
assumption the terminal is reconnecting. Otherwise the subscriber calls
back into Order to reject it (`rejectUnattended`) — the routing decision
is made here, but the transition itself still goes through Order's own
state machine.

### A terminal fetching or acting on an order

```
Terminal → GET/POST http://<device-server>/orders(/status) → OrderProxyEndpoints
    → OrderClient (blocking HTTP, off the event loop) → Spring Order service
    → response passed through unchanged (status code included)
```

- **Routing** (`RestRequestRouter`) matches on `RouteKey(HttpMethod, path)`,
  with the query string stripped (ADR-0008). No path variables: a target
  row's key travels in the request body (`DELETE`) or query string
  (`?group=`, `?type=`). Unmatched → 404.
- A 409 from Order (illegal state transition) passes through unchanged —
  the Device Server has no opinion about whether a move is legal.

## 6. Back Office — moved

Back Office lives in
[**sun-moon-java-platform-bo**](https://github.com/schware/sun-moon-java-platform-bo)
now, with its own design document. It was three route groups in this
process until the port scheme made one-container-per-service the shape,
and BO's internal-only exposure stopped fitting alongside a device-facing
API (ADR-0014).

What went with it: the operator/commoncode/device domains, session auth
and the `AuthorizedEndpoint` permission decorator, and migrations V2-V4.

**It then went further.** BO briefly ran as its own Netty service on this
kernel; it is now a **Spring** service in the `main` branch's family, and
the Netty implementation is archived as
[sun-moon-platform-bo-netty](https://github.com/schware/sun-moon-platform-bo-netty).
BO is administration with no throughput requirement, so hand-writing
sessions, BCrypt and per-action authorization bought it nothing that
`spring-boot-starter-security` does not already provide (ADR-0015). The
permission model itself — three tiers, `create` and `save` distinct —
survived the rewrite unchanged.

## 7. Batch

A hand-rolled equivalent of Spring Batch's chunk-oriented model — written
out because Spring was dropped, and mirroring how `sun-moon-c-server`
implemented the same pattern in C with function-pointer structs.

```
BatchJob ──▶ Step (ChunkStep<I,O>)
                 ├─ ItemReader<I>     readChunk(offset, size) → empty list ends the step
                 ├─ ItemProcessor<I,O> per item
                 └─ ItemWriter<O>     once per chunk (the commit interval)
```

`ChunkStep` loops read → process → write until the reader returns empty,
counting items and chunks and capturing any `RuntimeException` into a
failed `StepExecution`. `BatchJob` runs its steps in order and stops at
the first failure. Results surface as `JobExecution` / `StepExecution`.

**Quartz is only the trigger.** `BatchScheduler` schedules a `Runnable`
via a `JobDataMap` and a `RAMJobStore`; the Job/Step/Chunk model itself is
entirely ours. The concrete job today is `OrderSummaryBatchJob`, which
runs against the **legacy** in-memory order data (§8, §12) — it predates
the Device Server role and is kept as the cross-language Batch comparison
with Python's `batch-service` and C's `batch_runner`, not because it
reads real orders anymore (the real ones live in the Spring Order
service).

## 8. Persistence and data model

**Legacy.** `domain/order`, its MyBatis/Postgres adapter and its Flyway
migration predate the Device Server role (ADR-0016) and are not part of
the real order flow — orders now live entirely in
`sun-moon-java-platform-order` (Spring, PostgreSQL+JSONB, its own
migrations). This module remains only to feed `OrderSummaryBatchJob`
(§7) and is a candidate for removal — see §12.

MyBatis (annotation-mapped SQL) over HikariCP over PostgreSQL, with Flyway
for schema, is what it uses where it's still wired.

| Migration | Table | Key |
|---|---|---|
| `V1` | `orders` | `id` |

Connection settings come from `POSTGRES_JDBC_URL` / `POSTGRES_USER` /
`POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE` — unset, the legacy module runs
on an in-memory fake and the rest of the Device Server is unaffected.

**None of this has run against a live database**, unlike the Redis
Pub/Sub path (§9), which has. See §12.

## 9. Cross-cutting concerns

| Concern | Mechanism | State |
|---|---|---|
| Configuration | environment variables via `PlatformConfig` and `*Settings.fromEnv()` | working |
| Logging | Logback → console | working |
| Metrics | Micrometer `PrometheusMeterRegistry`, scraped at `GET /metrics` | working, verified |
| Tracing | OpenTelemetry SDK with a logging span exporter | working, verified |
| Order event routing | `OrderEventSubscriber` on Redisson Pub/Sub, `StringCodec` per topic | **working, live-verified** (ADR-0016) |
| REST proxy to Order | `OrderClient` (`java.net.http.HttpClient`), off the event loop | working, verified |
| Legacy persistence | MyBatis+Postgres behind `domain/order` | fake only in practice |
| Legacy cache / event bus | `CacheClient`/`EventPublisher` ports, Redisson/Kafka adapters | fake only in practice |

## 10. Configuration reference

| Variable | Default | Purpose |
|---|---|---|
| `API_PORT` | `PORT`, else `8087` | the terminal/REST listener (ADR-0016) |
| `SOCKET_PORT` | `9011` | raw Socket listener |
| `WORKER_THREADS` | `cores × 4` | pool REST endpoints (incl. the Order proxy) run on (ADR-0010) |
| `ORDER_SERVICE_URL` | `http://localhost:8083/order` | where `OrderClient` sends proxied requests |
| `REDIS_URL` | none | **the switch**: set → subscribes to Order's `order-events` channel and pushes terminals; unset → terminals connect and can be fetched from, but aren't nudged |
| `TERMINAL_GRACE` | `PT3M` (ISO-8601 duration) | how long a disconnected terminal still counts as present at its store |
| `POSTGRES_JDBC_URL` | none | switch for the **legacy** `domain/order` module only — unrelated to real orders |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE` | `app` / `app` / `5` | legacy database credentials and pool size |

## 11. Testing strategy

24 tests here, all passing — 27 with the kernel's 3.

- **Live transport tests** (`DeviceServerTest`, 8 tests). Boots the real
  `CoreRuntime` with the actual `DeviceServerInitializer` pipeline and
  drives it with real clients — `java.net.http.HttpClient`'s WebSocket
  client and a raw `java.net.Socket`. Covers: handshake accepted/rejected
  (missing device id, missing store id, unknown type), a connected
  terminal appearing in `/terminals` and receiving a push, pushing to an
  offline terminal reporting `delivered:false`, and the raw socket echo.
- **`TerminalRegistryTest`** (9 tests). The registry decides whether a
  store counts as staffed, which is the line between "a screen's wifi
  dropped" and "an order was refused" — tested directly rather than only
  through a socket. Includes the property store-scoping exists to
  guarantee: `oneStoresTerminalDoesNotVouchForAnother` and
  `theSameDeviceIdInTwoStoresIsTwoTerminals`.
- **Unit tests** for the pieces with no transport: the Batch engine
  (directly and through a real Quartz scheduler) and the legacy in-memory
  adapters.

Deliberately absent: any test of the legacy MyBatis/Kafka adapters, or of
`RedissonCacheClient` (as opposed to the Pub/Sub path, which the live
end-to-end check below does exercise). Testcontainers would be the
natural tool for the former and needs Docker, which this environment
doesn't have (ADR-0003).

**Verified beyond the test suite, live on the deployed server**: placing
an order at a store with no terminal connected auto-rejects it within
about half a second; placing one with a real WebSocket client connected
as `store-01/pos-01` keeps it `PLACED` and delivers the push frame to
that client; accepting it through this server's proxy sets Order's
`acceptedBy` to `pos-01`, confirmed by querying Order directly rather
than only through this server's own account of it; re-accepting an
already-accepted order is refused with 409.

## 12. Known gaps

Ordered by what would bite first in production.

1. **Device identity is format-only.** `AcceptKnownFormatDirectory`
   accepts any well-formed `(storeId, deviceId)`; it does not ask BO
   whether that device is real or belongs to that store. The real
   implementation needs service-to-service credentials between this
   server and BO, which have not been decided (`docs/adr/0016`). One
   direction for the terminal side of this: log in with an id/password
   rather than typing a store and device id, and resolve both from that
   credential server-side — folds authentication and identity resolution
   into one step, and removes two free-text fields an operator has to
   get right today.
2. **"One order-receiving terminal per store" is not enforced.** The
   owner's rule — several terminals may exist at a store, but only one
   receives orders — has no home yet. It needs a `receivesOrders` flag on
   BO's Device record and `DeviceDirectory` widened from a format check to
   a real lookup. Today it works only by convention (one POS per store).
3. **The legacy order module is dead weight.** `domain/order`, `api/
   CreateOrderEndpoint`, their MyBatis/Postgres adapter and Flyway
   migration predate the Device Server role and back nothing but the
   startup Batch demo. Nothing in the real order flow touches them. A
   candidate for removal, not done in this pass so as not to bundle
   cleanup with the Device Server pivot.
4. **No cancellation state.** Order's state machine has no `CANCELLED`;
   an order can be rejected or expired before acceptance, but not called
   off afterward.
5. **KDS, DID and the two channel apps (order-placing, delivery) don't
   exist yet.** Only the POS terminal
   (`sun-moon-terminal-pos`) is built. The routing this server does for
   KDS/DID audiences is implemented and tested against an empty
   registry, but has never pushed to a real KDS or DID screen.
6. **HTTP pipelining could reorder responses.** Offloading endpoints
   (ADR-0010) means two requests sent on one connection without waiting
   may finish out of order. Normal keep-alive clients wait for each
   response, so this doesn't arise in practice; documented rather than
   fixed.
7. **The raw Socket transport is still an echo handler.** It proves the
   transport works; it carries no protocol, and no terminal uses it (all
   current terminals are WebSocket).

## 13. Decision index

| ADR | Decision |
|---|---|
| [0001](adr/0001-use-adrs-for-decisions.md) | Record decisions as ADRs |
| [0002](adr/0002-ddd-enterprise-runtime-platform-on-netty-no-spring.md) | Spring-free, Netty-based DDD runtime |
| [0003](adr/0003-fakes-by-default-for-unavailable-infra.md) | Ports + in-memory fakes by default |
| [0004](adr/0004-bo-endpoints-session-auth.md) | BO scope, session auth, 3-tier permissions |
| [0005](adr/0005-switch-oracle-to-postgres.md) | Oracle → PostgreSQL; no local DB install |
| [0006](adr/0006-bo-auth-built-and-verified.md) | BO auth built and verified |
| [0007](adr/0007-deployment-target-render-neon-free-forever.md) | Deploy to Render + Neon (superseded by 0012) |
| [0008](adr/0008-common-code-crud-and-method-aware-routing.md) | Common Code CRUD; method-aware routing |
| [0009](adr/0009-device-crud-and-port-separation.md) | Device CRUD; BO/API port split |
| [0010](adr/0010-run-endpoints-off-the-event-loop.md) | Endpoints run on a bounded worker pool |
| [0011](adr/0011-deployment-mechanics.md) | Dockerfile, PORT, adapter selection, Secure cookie |
| [0012](adr/0012-deploy-to-own-debian-server.md) | Deploy to the owner's Debian server instead |
| [0013](adr/0013-withdraw-port-8084.md) | Withdraw port 8084; the family-wide port scheme |
| [0014](adr/0014-split-the-kernel-from-the-domains-built-on-it.md) | Split the kernel out; BO becomes its own repository |
| [0015](adr/0015-bo-returns-to-spring-and-leaves-this-lineage.md) | BO leaves for Spring; the Netty implementation is archived |
| [0016](adr/0016-the-runtime-becomes-the-device-server.md) | This runtime becomes the Device Server: terminal WebSockets, store-scoped routing, Order proxy |

The deployment runbook is [`DEPLOYMENT.md`](DEPLOYMENT.md).

Career-strategy context for why this repo exists lives in the separate
`Alignment` repository.
