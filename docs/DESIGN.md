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

A Java **Enterprise Runtime Platform**: one JVM process that hosts a REST
API, a WebSocket transport, a raw TCP Socket transport, and a Batch engine — DDD-structured, built on Netty, targeting
1,000-10,000 concurrent connections, with **no Spring anywhere**
(ADR-0002, ADR-0010 in `Alignment`).

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
3. **Ports and adapters, with fakes as the default.** Every external
   dependency (database, cache, message bus, session store) sits behind a
   domain-owned interface with two implementations: an in-memory fake that
   is wired in by default and covered by tests, and a real adapter that
   compiles but is **not** live-verified in this environment (ADR-0003,
   ADR-0005). `./gradlew run` and `./gradlew test` work on a machine with
   nothing but a JDK.
4. **Say what is verified.** Claims in docs distinguish "tested against a
   running server" from "compiles". See §11.

## 3. Runtime topology

One process. One `NioEventLoopGroup` boss (1 thread) + one worker group
(Netty default: 2 × available cores), shared by every listener.

```
                       ┌──────────────────────── one JVM ─────────────────────────┐
                       │                                                          │
  API clients  ──8083──┼─▶ HTTP listener "API"       ─┐                           │
  WS clients           │     /health /metrics         │                           │
                       │     /orders /ws              ├─▶ shared boss + worker    │
                       │                              │   event-loop groups       │
  Devices      ──9011──┼─▶ Socket listener           ─┘                           │
                       │                                                          │
                       │   Quartz scheduler ─▶ Batch engine (own thread pool)     │
                       └──────────────────────────────────────────────────────────┘
```

| Port | Listener | Serves | WebSocket | Env override |
|---|---|---|---|---|
| 8083 | API | `/health`, `/metrics`, `/orders`, `/ws` | yes | `API_PORT` |
| 9011 | Socket | raw TCP (echo) | — | `SOCKET_PORT` |

These are this runtime's slots in the family-wide scheme (ADR-0013): the
8081-8089 band for REST APIs, 90x1 for sockets by language. 8083 is where
the existing Spring Order service is expected to hand over; until this
runtime has a business function of its own, that number is reserved rather
than occupied.

**Threading.** Event-loop threads run the codec and the router only.
`RestEndpoint.handle()` is dispatched to a **bounded worker pool**
(`platform-worker-N`, sized by `WORKER_THREADS`, default
`availableProcessors × 4`), because endpoints call repositories and JDBC
blocks — a query inline on the event loop would stall every connection
that thread serves (ADR-0010). Responses return via `ctx.writeAndFlush`,
which hands back to the event loop itself. Pool saturation answers 503;
an endpoint that throws answers 500. Batch runs on Quartz's own
`SimpleThreadPool`, also off the event loop.

## 4. Layering

Hexagonal, expressed as packages under `com.sunmoon.platform`:

```
core/               the kernel, a git submodule (com.sunmoon.platform.core,
                      .transport.http, .observability, .infrastructure.persistence)
                      — listener binding, REST routing, off-event-loop execution,
                      shared MyBatis/Flyway/Micrometer/OTel wiring
api/                inbound adapters — the REST endpoints this runtime serves
transport/
  ws/, socket/      WebSocket and raw TCP handlers
domain/order/       the model + the ports it owns (interfaces only, no framework types)
infrastructure/     outbound adapters implementing domain ports
  persistence/      MyBatis+Postgres (real) and in-memory (fake)
  cache/            Redisson (real) and in-memory (fake)
  messaging/        Kafka (real) and in-memory (fake)
batch/              Job/Step/Chunk engine + Quartz scheduling
Bootstrap.java      composition root — the only place implementations are chosen
PlatformConfig.java the two ports and the worker-pool size
```

**Dependency rule.** `domain` depends on nothing but the JDK. `api`/
`transport` and `infrastructure` both depend on `domain`, never on each
other. The kernel depends on none of them — it is a library this runtime
composes, and no package is split between the two, so an import tells you
which side of the boundary a class is on (ADR-0014).
`Bootstrap` is the single place that knows which adapter implements which
port — swapping the whole system from fakes to real infrastructure is a
change to that one file.

Domain models are Java `record`s. Ports are interfaces declared in the
domain package they serve (e.g. `domain.device.DeviceRepository`).

## 5. Request lifecycle

A REST request, end to end:

```
TCP → HttpServerCodec → HttpObjectAggregator → [WebSocketServerProtocolHandler]
    → RestRequestRouter → [worker pool] → concrete RestEndpoint → domain port → adapter
```

- **Routing** (`RestRequestRouter`) matches on `RouteKey(HttpMethod, path)`,
  with the query string stripped (ADR-0008). No path variables: a target
  row's key travels in the request body (`DELETE`) or query string
  (`?group=`, `?type=`). Unmatched → 404.
- **Endpoints** implement a single-method interface
  (`RestEndpoint: FullHttpRequest → FullHttpResponse`), parse and validate
  their body, call domain ports, and return JSON via `JsonResponses`.

## 6. Back Office — moved

Back Office lives in
[**sun-moon-platform-bo**](https://github.com/schware/sun-moon-platform-bo)
now, with its own design document. It was three route groups in this
process until the port scheme made one-container-per-service the shape,
and BO's internal-only exposure stopped fitting alongside a device-facing
API (ADR-0014).

What went with it: the operator/commoncode/device domains, session auth
and the `AuthorizedEndpoint` permission decorator, their MyBatis adapters,
and migrations V2-V4 (renumbered V1-V3 there). Both services are built on
the same kernel, which arrives in each as the `core/` submodule.

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
entirely ours. The concrete job today is `OrderSummaryBatchJob` (sums
revenue per chunk through `OrderRepository`), the same job Python's
`batch-service` and C's `batch_runner` implement — deliberately, as a
cross-language comparison.

## 8. Persistence and data model

MyBatis (annotation-mapped SQL) over HikariCP over PostgreSQL, with Flyway
for schema. Postgres replaced Oracle because it is deployable on
free-tier hosting and Oracle is not (ADR-0005).

Each aggregate follows the same three-file shape: a `*Row` mutable POJO
for MyBatis to populate, a `*Mapper` interface holding the SQL, and a
`MyBatis*Repository` translating rows to domain records.

| Migration | Table | Key |
|---|---|---|
| `V1` | `orders` | `id` |

BO's migrations left with BO and are numbered from V1 there, so each
service needs its own database. Pointing both at one does not corrupt it —
Flyway refuses to migrate a history table holding applied migrations it
cannot resolve locally.

Connection settings come from `POSTGRES_JDBC_URL` / `POSTGRES_USER` /
`POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE`.

**None of this has run against a live database.** See §12, gap 2.

## 9. Cross-cutting concerns

| Concern | Mechanism | State |
|---|---|---|
| Configuration | environment variables via `PlatformConfig` and `*Settings.fromEnv()` | working |
| Logging | Logback → console | working |
| Metrics | Micrometer `PrometheusMeterRegistry`, scraped at `GET /metrics` | working, verified |
| Tracing | OpenTelemetry SDK with a logging span exporter | working, verified |
| Validation | Jakarta Bean Validation (Hibernate Validator) via `RequestValidation` | working, verified |
| Resilience | Resilience4j circuit breaker around the event-publish call | wired on one call path only |
| Cache / lock | `CacheClient` port; Redisson adapter exists | fake only in practice |
| Event bus | `EventPublisher` port; Kafka adapter exists | fake only in practice |

## 10. Configuration reference

| Variable | Default | Purpose |
|---|---|---|
| `API_PORT` | `PORT`, else `8083` | the REST/WebSocket listener. PaaS hosts inject `PORT` and require the published service to bind it (ADR-0011) |
| `SOCKET_PORT` | `9011` | raw Socket listener |
| `WORKER_THREADS` | `cores × 4` | pool REST endpoints run on (ADR-0010) |
| `POSTGRES_JDBC_URL` | none | **the switch**: set → real MyBatis/Postgres adapters + Flyway; unset → in-memory fakes |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE` | `app` / `app` / `5` | database credentials and pool size |
| `REDIS_URL` | `redis://localhost:6379` | Redisson (unused — no real `SessionStore`/`CacheClient` adapter is wired) |

## 11. Testing strategy

11 tests here, all passing, in two styles — 26 across the three
repositories (12 in BO, 3 in the kernel).

- **Live HTTP tests.** `CoreRuntimeTransportsTest` starts the real
  `CoreRuntime` on test ports and drives it with real clients —
  `java.net.http.HttpClient` (including its WebSocket client) and a raw
  `java.net.Socket`. Nothing is mocked; it exercises the actual Netty
  pipeline and routing. `BlockingWorkOffloadTest`, which proves endpoints
  run off the event loop, moved to the kernel repository that now enforces
  that property.
- **Unit tests** for the pieces with no transport: the Batch engine
  (directly and through a real Quartz scheduler) and the in-memory
  adapters.

Deliberately absent: any test of the MyBatis/Redisson/Kafka adapters.
Testcontainers would be the natural tool and needs Docker, which this
environment doesn't have (ADR-0003).

## 12. Known gaps

Ordered by what would bite first in production.

1. **No live database verification.** The MyBatis mappers, the Flyway
   migration, and `MyBatisConfig` compile but have never executed against
   a real PostgreSQL instance. What *is* verified is that the switch
   engages and fails loudly: running the packaged app with
   `POSTGRES_JDBC_URL` pointing at nothing exits with a HikariCP failure
   rather than silently falling back to fakes. First real exercise is the
   deployment to the Debian server (ADR-0012, `DEPLOYMENT.md`).
2. **This runtime has no business function yet.** `POST /orders` is a
   working REST endpoint, but the Order domain here is a module built to
   prove the shape; what it will actually do is undecided. That is why
   8083 is reserved rather than occupied.
3. **HTTP pipelining could reorder responses.** Offloading endpoints
   (ADR-0010) means two requests sent on one connection without waiting
   may finish out of order. Normal keep-alive clients wait for each
   response, so this doesn't arise in practice; documented rather than
   fixed.
4. **Socket and WebSocket transports are echo handlers.** They prove the
   transports work; they carry no protocol.
5. **The Dockerfile has never been built** — no Docker here (ADR-0003).
   The packaged `installDist` output it runs *is* verified to boot.
6. **Not deployed.** Everything in the repo is ready (ADR-0011,
   ADR-0012, `DEPLOYMENT.md`); the remaining steps need `sudo` on the
   target server — creating the database and opening the firewall.

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

The deployment runbook is [`DEPLOYMENT.md`](DEPLOYMENT.md).

Career-strategy context for why this repo exists lives in the separate
`Alignment` repository.
