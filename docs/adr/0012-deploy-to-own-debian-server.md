# ADR-0012: Deploy to the owner's Debian server, not Render + Neon

- **Status**: Accepted — supersedes the target choice in ADR-0007 and the `render.yaml` part of ADR-0011
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

ADR-0007 picked Render + Neon under one constraint: permanently free.
That research was sound but incomplete — it assumed no existing
infrastructure. The owner has a **Debian 11 server already running**, and
it is where the sibling Spring Boot services live:

- Intel Core i5 M 480 (2010, 4 cores), 5.6 GB RAM, 824 GB free
- JDK 21 (Temurin), Docker 20.10.5, PostgreSQL and Redis installed on the host
- `~/apps/docker/` holds Order/KDS/Delivery, each its own container run with
  `--network host`, `--memory` capped, `--restart unless-stopped`
- Reachable at `192.168.0.2`, key-based SSH as `schware`

It is *more* free than Render (already paid for), never sleeps, has no
0.5 GB database cap, and — decisively — can publish more than one port.
Render publishes exactly one, which would have left the Socket transport
(9090) unreachable, and a Device Server is precisely what that transport
exists for (ADR-0004).

## Decision

**Deploy to the Debian server, following the conventions already
established there** (`Debian-Setting` repo, `docs/docker.md`): clone into
`~/apps/docker/`, build an image on the server, run one container with
`--network host --memory=... --restart unless-stopped`.

`render.yaml` is deleted rather than kept as an alternative — it would rot
unverified. ADR-0007's *research* stands (Fly.io's free tier is gone,
Render's free Postgres expires in 30 days, Neon is genuinely free); only
its conclusion changes, and it remains the reference if this ever needs a
public URL rather than a LAN one.

### Port allocation

`--network host` means every listener binds a host port, so allocation had
to fit around what is already running (verified live, not assumed):

| Port | Owner | Note |
|---|---|---|
| 80, 8090 | Apache2 default page | pre-existing, purpose unknown, left alone |
| 8000 | landing page (nginx) | |
| 8080 | **Spring** Order | keeps running |
| 8081 / 8082 | KDS / Delivery | |
| **8083** | **this platform — Order API** | |
| **8084** | **this platform — BO** | via `BO_PORT` |
| **9090** | **this platform — Socket** | |

The owner's original instruction was BO on 8080 with Order moving to 8083.
That end state still holds, but not yet: **the Order service here and the
Spring Order service are two implementations of the same thing** (the
owner's framing), and this one is nowhere near replacing it — a single
`POST /orders` against an in-memory fake, versus a Postgres/JSONB service
with full CRUD, Swagger and actuator. Retiring the working one today would
be a regression.

So this platform's Order API takes 8083 **and never moves**; BO sits on
8084 until the Spring Order retires, at which point BO relocates to 8080
once. One deliberate move, timed to a real event, instead of disturbing a
service that has been up for 30 hours.

No code change was needed for any of this: `API_PORT` and `SOCKET_PORT`
defaults already match, and `BO_PORT=8084` is passed at run time. Local
development keeps its 8080 default.

### What the deployment does not include

`COOKIE_SECURE` stays **false**: the server is plain HTTP on a LAN address,
and a `Secure` cookie would never be sent, breaking BO login entirely.
This is the first deployment where that flag would have been wrong in the
other direction — `render.yaml` set it to `true`, correctly, for Render's TLS.

## Alternatives considered

- **Render + Neon** (ADR-0007) — still viable and now documented as the
  fallback for a public URL. Not chosen: cold starts, one published port,
  and an external database when a local one already exists.
- **Retire the Spring Order now and give BO 8080 immediately** — rejected:
  this platform's Order cannot yet do that service's job.
- **Reuse the existing `order_service` database** — rejected: this
  platform's Flyway migrations would create an `orders` table and a Flyway
  history table inside a database another service owns.

## Consequences

- **Two steps need `sudo`, which this session cannot perform** (`schware`
  can sudo but only with a password): creating the PostgreSQL database,
  and opening 8083/8084/9090 in UFW. Both are single commands, listed in
  `DEPLOYMENT.md`.
- The `sunmoon` PostgreSQL role has neither `CREATEDB` nor superuser, so
  the database cannot be created by the application or by an unprivileged
  SSH session — verified, not assumed.
- Deployment is therefore staged: **first with the in-memory fakes** (no
  `POSTGRES_JDBC_URL`), which needs no sudo and proves the Docker build,
  the container, the ports and every BO screen on real hardware; **then**
  with the database once it exists. If something breaks, the stage says
  which half.
- Building on this CPU takes 5-10 minutes per image, and `docker build`
  **must** pass `--network host` — the server's Docker bridge DNS is
  broken (documented in `Debian-Setting/docs/docker.md`).
- Redis is already on the host at `localhost:6379`, which means
  `RedisSessionStore` — the one port in this repo with no real adapter —
  could now actually be built *and verified*. Not done here; noted as the
  natural next unlock.

## A finding worth acting on separately

`sun-moon-java-platform-order`'s `application.yml` carries its database
password in plaintext (`sunmoon_dev_pw`), and that repository is
**public**. It is a LAN-only development database, so the practical risk
is low, but it is a pattern an interviewer reading the portfolio would
notice. Worth moving to an environment variable in that repo — this one
already reads all credentials from the environment.

## References

- `Debian-Setting` repo: `docs/system-info.md`, `docs/docker.md` — the
  server conventions this follows.
- ADR-0007 (superseded target), ADR-0011 (Dockerfile and adapter
  selection, both still used), ADR-0009 (why BO owns a port of its own).
