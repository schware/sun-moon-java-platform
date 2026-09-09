# sun-moon-java-platform

DDD-based order/kitchen/delivery system plus its Back Office, split into
four independently deployable services. This repo is an **umbrella** tying
them together as git submodules — there's no application source here, just
the cross-cutting architecture decisions and links.

New here? [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) is what the four
services agree on, written as commands you run rather than rules you take
on trust.

| Service | Repo | Role | Data store |
|---|---|---|---|
| **Order** | [`order/`](order) → [sun-moon-java-platform-order](https://github.com/schware/sun-moon-java-platform-order) | Order creation, publishes `OrderCreated` | Postgres (JSONB) |
| **KDS** | [`kds/`](kds) → [sun-moon-java-platform-kds](https://github.com/schware/sun-moon-java-platform-kds) | Kitchen ticket queue, publishes `OrderReady` | Redis |
| **Delivery** | [`delivery/`](delivery) → [sun-moon-java-platform-delivery](https://github.com/schware/sun-moon-java-platform-delivery) | Courier assignment/tracking, publishes `OrderDelivered` | Postgres (JSONB) |
| **BO** | [`bo/`](bo) → [sun-moon-java-platform-bo](https://github.com/schware/sun-moon-java-platform-bo) |  Back Office — 운영자, 공지사항, 공통코드, 장비 (React 콘솔 포함) | Postgres |

Archived predecessors:
[sun-moon-java-platform-netty](https://github.com/schware/sun-moon-java-platform-netty)
(single-service, Netty, no Spring, pre-split) and the WAR/shared-Jetty
version of each service —
[order-jetty](https://github.com/schware/sun-moon-java-platform-order-jetty),
[kds-jetty](https://github.com/schware/sun-moon-java-platform-kds-jetty),
[delivery-jetty](https://github.com/schware/sun-moon-java-platform-delivery-jetty)
— see `docs/adr/0003` for why those were replaced with Docker. BO's
Spring-free Netty implementation is archived as
[sun-moon-platform-bo-netty](https://github.com/schware/sun-moon-platform-bo-netty)
— see `docs/adr/0004`.

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

| | URL | |
|---|---|---|
| BO | `http://<host>:8080/bo/` | LAN only |
| KDS | `http://<host>:8081/kds/` | |
| Delivery | `http://<host>:8082/delivery/` | |
| Order | `http://<host>:8083/order/` | |

Port numbers come from the server-wide scheme, not from this repo — see
Debian-Setting's `docs/docker.md`. Order moved 8080 → 8083 to free 8080
for BO.

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
- [`docs/adr/0004`](docs/adr/0004-bo-joins-this-family-as-a-spring-service.md)
  — why BO was rebuilt on Spring and joined this family, rather than
  staying the one service built on raw Netty.
- [`docs/adr/0005`](docs/adr/0005-bo-ships-its-own-react-console.md) — why
  BO's React/TypeScript console ships inside its own jar, and why that
  forced the API under `/api`.

The archived `-jetty` repos (linked from `docs/adr/0003`) each have their
own `docs/adr/` covering the WAR/Jetty deployment mechanics and a
classpath bug worth knowing about if that setup is ever revisited.
