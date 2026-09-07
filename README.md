# sun-moon-java-platform

DDD-based order/kitchen/delivery system, split into three independently
deployable services. This repo is an **umbrella** tying them together as
git submodules — there's no application source here, just the
cross-cutting architecture decisions and links.

| Service | Repo | Role | Data store |
|---|---|---|---|
| **Order** | [`order/`](order) → [sun-moon-java-platform-order](https://github.com/schware/sun-moon-java-platform-order) | Order creation, publishes `OrderCreated` | Postgres (JSONB) |
| **KDS** | [`kds/`](kds) → [sun-moon-java-platform-kds](https://github.com/schware/sun-moon-java-platform-kds) | Kitchen ticket queue, publishes `OrderReady` | Redis |
| **Delivery** | [`delivery/`](delivery) → [sun-moon-java-platform-delivery](https://github.com/schware/sun-moon-java-platform-delivery) | Courier assignment/tracking, publishes `OrderDelivered` | Postgres (JSONB) |

Archived predecessor (single-service, Netty, no Spring, pre-split):
[sun-moon-java-platform-netty](https://github.com/schware/sun-moon-java-platform-netty).

## Cloning

```
git clone --recurse-submodules https://github.com/schware/sun-moon-java-platform.git
```

Already cloned without `--recurse-submodules`?

```
git submodule update --init --recursive
```

## Where things run

All three deploy as separate WARs to one shared Jetty instance on the
Debian homelab host — not the eventual per-service-process isolation
described in `docs/adr/0002`, but what the current single-box hardware
can actually run. See
[`Debian-Setting/docs/jetty.md`](https://github.com/schware/Debian-Setting/blob/master/docs/jetty.md)
for how to start/stop/restart it and deploy new WARs.

| | URL |
|---|---|
| Landing page (all 3 services) | `http://<host>:8080/` |
| Order | `http://<host>:8080/sun-moon-java-platform/` |
| KDS | `http://<host>:8080/kds/` |
| Delivery | `http://<host>:8080/delivery/` |

## Architecture decisions (this repo)

- [`docs/adr/0001`](docs/adr/0001-per-service-database-choice.md) — final
  per-service database choice (Order/Delivery: Postgres+JSONB, KDS:
  Redis) and the hardware discovery that changed the plan mid-flight
  (MongoDB doesn't run on this box's CPU).
- [`docs/adr/0002`](docs/adr/0002-msa-split-direction.md) — why the
  system is split into Order/KDS/Delivery at all, the event flow between
  them, and the messaging broker direction (lightweight now, Kafka once
  hardware allows — carried over from the pre-split project).

Each service repo also has its own `docs/adr/` for decisions scoped to
that service alone (e.g. the WAR/Jetty deployment mechanics, a classpath
bug and fix worth knowing before touching any of these repos' build
files — see `order/docs/adr/0004` and `0005`).
