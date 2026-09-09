# ADR-0010: Run REST endpoints on a bounded worker pool, not on the event loop

- **Status**: Accepted (built and verified)
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

Writing `docs/DESIGN.md` surfaced a defect that had been implied but never
stated: `RestRequestRouter.channelRead0` called `endpoint.handle()`
inline, on the Netty event-loop thread. Endpoints call repositories, and
JDBC is blocking, so wiring the real `MyBatis*Repository` adapters would
have parked an event-loop thread for the duration of every query. With
Netty's default of `2 × cores` event-loop threads, a handful of concurrent
queries would stall *every* connection those threads serve — nowhere near
the 1,000-10,000 concurrent connection target this platform claims.

ADR-0002 recorded the constraint ("Netty's event-loop threads must never
block... hand off to a separate worker thread pool") as a design note when
the persistence layer was still hypothetical. It was never implemented,
and the in-memory fakes hid it: they return in microseconds, so every test
passed and every manual run looked fine.

This is a prerequisite for `docs/adr/0007`'s deployment checklist, not an
optimisation to do later — the first deployment is also the first time
real JDBC runs.

## Decision

**`RestEndpoint.handle()` executes on a bounded pool owned by
`CoreRuntime`, never on an event-loop thread.**

- The router retains the request (`channelRead0` releases it on return),
  submits the call, and releases it in the worker's `finally`.
- Responses go back through `ctx.writeAndFlush`, which is thread-safe and
  hands off to the event loop itself — no explicit hop needed.
- Pool size comes from `WORKER_THREADS`, defaulting to
  `availableProcessors × 4`. The useful ceiling is related to
  `POSTGRES_POOL_SIZE`: threads beyond the connection pool just queue on
  HikariCP instead of on the executor.
- Threads are named `platform-worker-N` — which is what makes the
  behaviour assertable in a test.
- Saturation returns **503** rather than queueing unboundedly, and an
  endpoint that throws now returns **500** instead of dropping the
  connection. Both are new: previously an exception propagated up the
  pipeline with no response written.
- 404s for unrouted paths still answer on the event loop; there's no
  handler to run, so there's nothing to offload.

**Uniform offload, not per-endpoint classification.** Every endpoint is
offloaded, including `/health` and `/metrics`. A thread hop costs
microseconds; deciding per endpoint would mean a classification that has
to stay correct forever, and getting it wrong reintroduces exactly this
bug silently.

## Alternatives considered

- **Virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`) —
  the headline Java 21 feature, and a natural fit for blocking I/O.
  Not chosen: on JDK 21 a virtual thread is *pinned* to its carrier inside
  `synchronized` blocks (fixed only in JDK 24), and both HikariCP and the
  Postgres driver use them on the connection path, so the scaling benefit
  is not something this stack can currently rely on. A bounded pool also
  applies useful backpressure — matching the worker count to the DB
  connection pool means saturation shows up as a fast 503 rather than an
  unbounded pile of threads all waiting on the same five connections.
  Worth revisiting on a newer JDK.
- **Netty's `DefaultEventExecutorGroup`** attached to the handler — the
  idiomatic Netty answer, and it preserves per-channel ordering. Not
  chosen: a plain JDK executor is more transparent for this codebase's
  no-magic ethos, and the ordering property it buys is only relevant for
  pipelined requests (see Consequences).
- **Async repositories all the way down** (R2DBC or similar) — the
  "correct" non-blocking answer, but it means replacing MyBatis, which is
  itself a deliberate choice tied to the owner's production background
  (ADR-0002). Not worth reversing for this.

## Consequences

- The real database adapters can now be wired in without the throughput
  collapse described above. That unblocks `docs/adr/0007` step 8.
- **HTTP pipelining can now reorder responses.** Two requests sent on one
  connection without waiting could complete on different workers out of
  order. Normal keep-alive clients (including browsers and
  `java.net.http.HttpClient`) wait for each response before sending the
  next, so this doesn't arise in practice; pipelining is rare and mostly
  disabled. Recorded here rather than fixed — a per-channel ordering
  queue is real complexity for a case this codebase doesn't have.
- Endpoint exceptions now produce a 500 instead of silently dropping the
  connection, which is a straightforward improvement that came along with
  needing a `catch` in the worker.
- `CoreRuntime` gained a shutdown path for the pool (10s graceful, then
  `shutdownNow`).

**Verified** by `BlockingWorkOffloadTest`: the handler thread is asserted
to be `platform-worker-*` and *not* an event-loop thread; a deliberately
blocked endpoint does not stop a second connection from being served; a
throwing endpoint returns 500. 26 tests total, all passing.

## References

- `docs/adr/0002` — where this constraint was first written down and left
  unimplemented.
- `docs/DESIGN.md` §3 (threading), §12 — writing the design document is
  what surfaced this.
