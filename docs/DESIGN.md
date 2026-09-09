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
API, a Back Office (BO) admin API, a WebSocket transport, a raw TCP Socket
transport, and a Batch engine — DDD-structured, built on Netty, targeting
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
  BO Frontend  ──8080──┼─▶ HTTP listener "BO"        ─┐                           │
  (future SPA)         │     /health /metrics /bo/*   │                           │
                       │                              │                           │
  API clients  ──8083──┼─▶ HTTP listener "Order API" ─┼─▶ shared boss + worker    │
  WS clients           │     /health /orders /ws      │   event-loop groups       │
                       │                              │                           │
  Devices      ──9090──┼─▶ Socket listener           ─┘                           │
                       │                                                          │
                       │   Quartz scheduler ─▶ Batch engine (own thread pool)     │
                       └──────────────────────────────────────────────────────────┘
```

| Port | Listener | Serves | WebSocket | Env override |
|---|---|---|---|---|
| 8080 | BO | `/health`, `/metrics`, `/bo/*` | no | `BO_PORT` |
| 8083 | Order API | `/health`, `/orders`, `/ws` | yes | `API_PORT` |
| 9090 | Socket | raw TCP (echo) | — | `SOCKET_PORT` |

`/health` is on both HTTP listeners deliberately: each is independently
probeable. `/metrics` sits on BO because BO is the operational surface.

The ports above are the **code defaults**, used in local development. On
the deployment target they are remapped around services already running
there — BO moves to 8084 via `BO_PORT`; see `DEPLOYMENT.md` and ADR-0012.

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
transport/          inbound adapters — Netty pipelines, routing, endpoints
  http/             REST: router, listener specs, shared response/validation helpers
  http/bo/          BO auth + the permission decorator
  http/bo/commoncode/, http/bo/device/   BO screens
  ws/, socket/      WebSocket and raw TCP handlers
domain/             the model + the ports it owns (interfaces only, no framework types)
  order/ operator/ commoncode/ device/
infrastructure/     outbound adapters implementing domain ports
  persistence/      MyBatis+Postgres (real) and in-memory (fake)
  auth/             session store, BCrypt hashing
  cache/            Redisson (real) and in-memory (fake)
  messaging/        Kafka (real) and in-memory (fake)
batch/              Job/Step/Chunk engine + Quartz scheduling
observability/      Micrometer registry, OpenTelemetry tracer
core/               CoreRuntime (binds listeners), RuntimeConfig
Bootstrap.java      composition root — the only place implementations are chosen
```

**Dependency rule.** `domain` depends on nothing but the JDK. `transport`
and `infrastructure` both depend on `domain`, never on each other.
`Bootstrap` is the single place that knows which adapter implements which
port — swapping the whole system from fakes to real infrastructure is a
change to that one file.

Domain models are Java `record`s. Ports are interfaces declared in the
domain package they serve (e.g. `domain.device.DeviceRepository`).

## 5. Request lifecycle

A BO request, end to end:

```
TCP → HttpServerCodec → HttpObjectAggregator → [WebSocketServerProtocolHandler]
    → RestRequestRouter → AuthorizedEndpoint → concrete RestEndpoint → domain port → adapter
```

- **Routing** (`RestRequestRouter`) matches on `RouteKey(HttpMethod, path)`,
  with the query string stripped (ADR-0008). No path variables: a target
  row's key travels in the request body (`DELETE`) or query string
  (`?group=`, `?type=`). Unmatched → 404.
- **Authorization** (`AuthorizedEndpoint`) is a decorator, so endpoints
  never mention auth. It resolves the session cookie, then allows the call
  if the operator is a super admin, or if their permission for that
  `Screen` allows that `Action`. Otherwise 401 (no session) or 403.
- **Endpoints** implement a single-method interface
  (`RestEndpoint: FullHttpRequest → FullHttpResponse`), parse and validate
  their body, call domain ports, and return JSON via `JsonResponses`.

## 6. Back Office

### 6.1 Authentication — server-side sessions, not JWT

Login issues an opaque session id stored server-side and returned in an
`HttpOnly` + `SameSite=Lax` cookie scoped to `/bo`. Passwords are BCrypt
hashes (cost 12, `at.favre.lib:bcrypt`).

Sessions were chosen over JWT specifically so a compromised or offboarded
operator can be revoked *immediately* by deleting one record — an admin
panel wants that, and JWT's stateless advantage doesn't pay off while this
is a single process (ADR-0004). Permissions are loaded once at login and
cached in the session rather than re-read per request.

| Endpoint | Purpose |
|---|---|
| `POST /bo/auth/login` | verify credentials, create session, set cookie |
| `POST /bo/auth/logout` | delete the session server-side, expire the cookie |
| `GET /bo/auth/me` | current operator + accessible screens (drives the Frontend menu) |

**First-operator bootstrap.** BO requires a login to manage operators, so
`Bootstrap` seeds one super admin from `BO_ADMIN_USERNAME` /
`BO_ADMIN_PASSWORD` — but only when no operator exists, and never with a
guessable default: if the variables are unset it logs a warning and seeds
nothing.

### 6.2 Permission model — three tiers

1. **Super admin** (`Operator.superAdmin`) — bypasses all checks.
2. **Per screen** — `Screen` is a code-defined enum (`COMMON_CODE`,
   `DEVICE`, `OPERATOR`), not a table: the screen list changes with
   deployments, not by an operator's action.
3. **Per action within a screen** — four independent flags on
   `OperatorScreenPermission`: `canView` / `canCreate` / `canSave` /
   `canDelete` (조회 / 신규 / 저장 / 삭제).

Each HTTP method maps to exactly one action, which is why CRUD needs
method-aware routing:

| Method | Action | Semantics |
|---|---|---|
| `GET` | `VIEW` | list (optionally filtered) |
| `POST` | `CREATE` | insert; 409 if the key exists |
| `PUT` | `SAVE` | update; 404 if the key doesn't exist |
| `DELETE` | `DELETE` | delete by key in the body |

`create` and `save` are deliberately distinct operations, not an upsert,
because 신규 and 저장 are distinct permissions.

### 6.3 Screens

| Screen | Endpoints | Model |
|---|---|---|
| Common Code | `/bo/common-code` (+`?group=`) | `CommonCode(groupCode, code, name, sortOrder, active)`, PK `(groupCode, code)` |
| Device | `/bo/devices` (+`?type=`) | `Device(deviceId, name, deviceType, location, active)`, PK `deviceId` |

`Device.deviceType` is expected to reference a `DEVICE_TYPE` Common Code
but is **not** a foreign key: Common Codes are runtime-editable, and a
dangling type should not make a device row unreadable.

**Devices are master data only.** Connection state, handshake and
protocol belong to a separate **Device Server** that does not exist yet
(ADR-0004). BO never touches live connections.

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
| `V2` | `operators`, `operator_screen_permissions` | `id`; `(operator_id, screen)` |
| `V3` | `common_codes` | `(group_code, code)` |
| `V4` | `devices` | `device_id` |

Connection settings come from `POSTGRES_JDBC_URL` / `POSTGRES_USER` /
`POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE`.

**None of this has run against a live database.** See §12, gap 2.

## 9. Cross-cutting concerns

| Concern | Mechanism | State |
|---|---|---|
| Configuration | environment variables via `RuntimeConfig` and `*Settings.fromEnv()` | working |
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
| `BO_PORT` | `PORT`, else `8080` | BO listener. PaaS hosts inject `PORT` and require the published service to bind it (ADR-0011) |
| `API_PORT` | `8083` | Order API listener |
| `SOCKET_PORT` | `9090` | raw Socket listener |
| `WORKER_THREADS` | `cores × 4` | pool REST endpoints run on (ADR-0010) |
| `COOKIE_SECURE` | `false` | `Secure` on the BO session cookie — must be `true` behind TLS |
| `BO_ADMIN_USERNAME` / `BO_ADMIN_PASSWORD` | none | first super admin, seeded only if no operator exists |
| `POSTGRES_JDBC_URL` | none | **the switch**: set → real MyBatis/Postgres adapters + Flyway; unset → in-memory fakes |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE` | `app` / `app` / `5` | database credentials and pool size |
| `REDIS_URL` | `redis://localhost:6379` | Redisson (unused — no real `SessionStore`/`CacheClient` adapter is wired) |

## 11. Testing strategy

26 tests, all passing, in two styles:

- **Live HTTP tests.** `CoreRuntimeTransportsTest`, `BoAuthTest`,
  `CommonCodeCrudTest`, `DeviceCrudTest`, `BlockingWorkOffloadTest` start
  the real `CoreRuntime` on test ports and drive it with real clients —
  `java.net.http.HttpClient` (including its WebSocket client and a cookie
  manager) and a raw `java.net.Socket`. Nothing is mocked; these exercise
  the actual Netty pipeline, routing, session cookies, permission checks,
  and the fact that endpoints really do run off the event loop.
- **Unit tests** for the pieces with no transport: the Batch engine
  (directly and through a real Quartz scheduler) and the in-memory
  adapters.

Deliberately absent: any test of the MyBatis/Redisson/Kafka adapters.
Testcontainers would be the natural tool and needs Docker, which this
environment doesn't have (ADR-0003).

## 12. Known gaps

Ordered by what would bite first in production.

1. **No live database verification.** All MyBatis mappers, all four Flyway
   migrations, and `MyBatisConfig` compile but have never executed against
   a real PostgreSQL instance. What *is* verified is that the switch
   engages and fails loudly: running the packaged app with
   `POSTGRES_JDBC_URL` pointing at nothing exits with a HikariCP failure
   rather than silently falling back to fakes. First real exercise is the
   deployment to the Debian server (ADR-0012, `DEPLOYMENT.md`).
2. **`SessionStore` has no real adapter.** Only `InMemorySessionStore`
   exists, so sessions die with the process and cannot be shared across
   instances — meaning the deployed service cannot scale past one
   instance, and every deploy logs everyone out. A `RedisSessionStore` is
   the intended real adapter.
3. **No Operator management screen.** Operators and their permissions can
   only be created by the startup seed or by calling the repository
   directly (`InMemoryOperatorRepository.grantPermission`, test-only). The
   `OPERATOR` screen enum value exists with nothing behind it.
4. **No CORS handling.** Required once a Frontend runs on its own origin
   and calls BO with `credentials: include`.
5. **HTTP pipelining could reorder responses.** Offloading endpoints
   (ADR-0010) means two requests sent on one connection without waiting
   may finish out of order. Normal keep-alive clients wait for each
   response, so this doesn't arise in practice; documented rather than
   fixed.
6. **Socket and WebSocket transports are echo handlers.** They prove the
   transports work; they carry no protocol.
7. **The Dockerfile has never been built** — no Docker here (ADR-0003).
   The packaged `installDist` output it runs *is* verified to boot.
8. **Not deployed.** Everything in the repo is ready (ADR-0011,
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

The deployment runbook is [`DEPLOYMENT.md`](DEPLOYMENT.md).

Career-strategy context for why this repo exists lives in the separate
`Alignment` repository.
