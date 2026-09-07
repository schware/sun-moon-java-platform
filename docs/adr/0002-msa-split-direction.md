# 0002: MSA split (Order / KDS / Delivery)

## Status

Accepted and implemented (as of this repo's restructure into
git-submodule-linked services). Originally recorded as a *deferred*
direction in the pre-split project
([`sun-moon-java-platform-order`'s ADR 0006](https://github.com/schware/sun-moon-java-platform-order/blob/main/docs/adr/0006-future-msa-split.md))
— implemented sooner than planned, on the same hardware it was meant to
wait for, once real deployment turned out to fit.

## Context

The system was originally one deployable (`sun-moon-java-platform`, now
archived as `-netty`, then rebuilt on Spring/Jetty as a single WAR). Order
/ Kitchen Display System (KDS) / Delivery are three domains with
genuinely different lifecycles and scaling characteristics:

- **Order** — customer-facing, bursty traffic, needs to respond fast.
- **KDS** — kitchen-facing, needs near-real-time updates, low traffic volume.
- **Delivery** — courier/logistics-facing, potentially calls external
  delivery APIs, independent failure domain from the other two.

## Decision

### Service boundaries

Three services, communicating by domain events rather than in-process
calls:

```
Order service  --OrderCreated-->  KDS service  --OrderReady-->  Delivery service
                                                                       |
                                                                  OrderDelivered
```

- **Order**: owns order creation/validation, publishes `OrderCreated`.
- **KDS**: subscribes to `OrderCreated`, tracks prep status
  (received → cooking → ready), publishes `OrderReady`.
- **Delivery**: subscribes to `OrderReady`, assigns/tracks a courier,
  publishes `OrderDelivered`.

**Not yet wired**: each service currently exposes one REST endpoint
(`POST /orders`, `POST /tickets`, `POST /deliveries`) and persists to its
own database. The event bus connecting them (`OrderCreated` →
`OrderReady` → `OrderDelivered`) is designed but not implemented — that's
the next slice of work, not this one.

### Repository structure: one umbrella, three submodules

Each service is its own GitHub repo (independently versioned, built,
deployed), linked from this umbrella repo as a git submodule. Decided
against a single monorepo with multiple Gradle subprojects — full
repository separation was preferred over module separation within one
repo.

### Deployment: same Jetty, not (yet) separate processes

All three currently deploy as separate WARs to **one shared Jetty
instance** on the single homelab box — see
[`Debian-Setting/docs/jetty.md`](https://github.com/schware/Debian-Setting/blob/master/docs/jetty.md).
This is **not** true process isolation: all three JVMs... actually run
inside the *same* JVM as separate webapp contexts, sharing heap and
failure domain. Real per-service process isolation (separate JVMs at
minimum, containers ideally) is follow-up work once hardware allows it —
tracked here so it isn't forgotten, not abandoned.

### Data: per-service, no shared schema

Each service owns its database — see
[`docs/adr/0001`](0001-per-service-database-choice.md) for the specific
choice per service and a hardware constraint that changed the original
plan mid-flight (MongoDB doesn't run on this box's CPU; Order and
Delivery ended up on Postgres+JSONB instead, KDS stayed on Redis as
planned).

### Messaging: lightweight broker planned, Kafka deferred (not abandoned)

Not implemented yet (see "Not yet wired" above). When it is: a
lightweight broker — **Redis Streams or RabbitMQ**, not Kafka. Kafka
(+Zookeeper/KRaft) has real memory overhead that doesn't fit alongside
three service JVMs on this hardware. Leaning toward **Redis Streams**
given Redis is already running for KDS — reusing one Redis instance for
both cache/store and messaging avoids running a second broker process.
Not a final decision; revisit when actually implementing.

**Once hardware allows it, migrate the messaging layer to Kafka.** The
event-driven design (services react to published domain events, not to
each other directly) doesn't change — only the broker underneath does.

## Consequences

- The three services are independently deployable in principle (separate
  repos, separate builds) but not yet independently *restartable*
  in practice — Jetty's hot-redeploy is off, so updating any one WAR
  currently means restarting the whole Jetty process, taking down all
  three momentarily. Acceptable for a homelab; not acceptable once this
  matters for real availability.
- Revisit process isolation, the messaging broker, and Kafka migration at
  their own implementation time — hardware, ecosystem maturity, and
  actual traffic patterns may look different by then.
