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

Archived predecessors:
[sun-moon-java-platform-netty](https://github.com/schware/sun-moon-java-platform-netty)
(single-service, Netty, no Spring, pre-split) and the WAR/shared-Jetty
version of each service —
[order-jetty](https://github.com/schware/sun-moon-java-platform-order-jetty),
[kds-jetty](https://github.com/schware/sun-moon-java-platform-kds-jetty),
[delivery-jetty](https://github.com/schware/sun-moon-java-platform-delivery-jetty)
— see `docs/adr/0003` for why those were replaced with Docker.

## Cloning

```
git clone --recurse-submodules https://github.com/schware/sun-moon-java-platform.git
```

Already cloned without `--recurse-submodules`?

```
git submodule update --init --recursive
```

## Where things run

Each service runs as its own Docker container on the Debian homelab host
(`--network host`, one port each) — real process isolation, independent
redeploy, per-container memory limits. See
[`Debian-Setting/docs/docker.md`](https://github.com/schware/Debian-Setting/blob/master/docs/docker.md)
for how to build/run/redeploy.

| | URL |
|---|---|
| Order | `http://<host>:8080/` |
| KDS | `http://<host>:8081/` |
| Delivery | `http://<host>:8082/` |

Previously all three ran as WARs on one shared Jetty instance — see
`docs/adr/0003` for why that changed. That setup's docs/scripts are
preserved in the now-archived `-jetty` repos (linked there) in case the
Docker approach ever needs to be un-done.

## Architecture decisions (this repo)

- [`docs/adr/0001`](docs/adr/0001-per-service-database-choice.md) — final
  per-service database choice (Order/Delivery: Postgres+JSONB, KDS:
  Redis) and the hardware discovery that changed the plan mid-flight
  (MongoDB doesn't run on this box's CPU).
- [`docs/adr/0002`](docs/adr/0002-msa-split-direction.md) — why the
  system is split into Order/KDS/Delivery at all and the event flow
  between them (messaging bus not implemented yet).
- [`docs/adr/0003`](docs/adr/0003-docker-replaces-shared-jetty.md) — why
  the shared-Jetty deployment was replaced with one Docker container per
  service, and where the old WAR/Jetty setup's docs live now.

The archived `-jetty` repos (linked from `docs/adr/0003`) each have their
own `docs/adr/` covering the WAR/Jetty deployment mechanics and a
classpath bug worth knowing about if that setup is ever revisited.
