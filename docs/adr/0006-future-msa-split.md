# 0006: Planned MSA split (Order / KDS / Delivery) — deferred, direction only

## Status

Accepted as future direction. **Not implemented yet** — deferred until
better hardware is available (current homelab box: 4 cores / 5.6GB RAM,
shared with everything else described in `Debian-Setting`).

## Context

`sun-moon-java-platform` is currently a single deployable WAR: one Spring
context, one JVM, one Jetty webapp. That's fine at the current scale, but
Order / Kitchen Display System (KDS) / Delivery are three domains with
genuinely different lifecycles and scaling characteristics:

- **Order** — customer-facing, bursty traffic, needs to respond fast.
- **KDS** — kitchen-facing, needs near-real-time updates, low traffic volume.
- **Delivery** — courier/logistics-facing, potentially calls external
  delivery APIs, independent failure domain from the other two.

Splitting these into separate services only pays off once they can
actually run as separate processes with separate resource budgets —
which the current single box can't comfortably give them alongside
everything else already running there (Jetty, the sun-moon-java-platform
JVM, Gradle, etc.).

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

The existing `EventPublisher` port (`InMemoryEventPublisher` today,
`KafkaEventPublisher` stubbed but unused per the Netty-era ADR 0003) is
exactly the seam this split would use — it just needs a real broker
behind it and to actually cross process boundaries instead of staying
in-process.

### Messaging: lightweight broker now, Kafka later — explicitly deferred, not abandoned

**Near-term (when the split is actually implemented):** a lightweight
broker — **Redis Streams or RabbitMQ** — not Kafka. Kafka (+ typically
Zookeeper/KRaft) has real memory overhead that doesn't fit alongside
three service JVMs on constrained hardware. Between the two:

| | Redis Streams | RabbitMQ |
|---|---|---|
| Extra process to run | None, if Redis is already deployed for caching (see below) | Yes, a separate broker |
| Delivery guarantees / routing maturity | Simpler (consumer groups) | More mature (exchanges, bindings, retry/DLQ patterns) |
| Resource footprint | Lower (shares the cache Redis instance) | Higher |

Leaning toward **Redis Streams** given this project already has Redis
planned as the cache layer (`RedissonCacheClient`, currently unused —
see the archived Netty repo's ADR 0003) — reusing one Redis instance for
both cache and messaging avoids running a second broker process on this
hardware. Not a final decision; revisit when actually implementing.

**Explicitly documented, not silently dropped: once hardware allows it
(a dedicated box or enough headroom for Kafka's footprint), migrate the
messaging layer to Kafka.** The event-driven design (services react to
published domain events, not to each other directly) doesn't change —
only the broker underneath the `EventPublisher` port does. This is the
whole point of that port existing.

### Data: NoSQL, one store per service

Each service gets its **own** database — no cross-service joins, no
shared schema. Direction is NoSQL per service (exact choice — e.g.
MongoDB vs a key-value store — not decided yet; pick per service based on
actual access patterns when each is built, not up front).

## Consequences

- Nothing changes in the codebase today. This ADR exists so the direction
  isn't lost/re-litigated later, and so "why not just use Kafka" and "why
  not share one DB" both have a documented answer.
- When this is picked up: each service becomes its own Spring Boot
  project (own `build.gradle.kts`, own deploy to its own Jetty
  instance/port or container — see [`Debian-Setting/docs/jetty.md`](https://github.com/schware/Debian-Setting/blob/master/docs/jetty.md)
  for the current single-instance deploy pattern this would extend from).
- Revisit this ADR's broker/DB choices at implementation time — hardware,
  Redis/RabbitMQ ecosystem maturity, and this project's actual traffic
  patterns may look different by then.
