# 0003: Docker (one container per service) replaces the shared Jetty

## Status

Accepted and implemented.

## Context

The initial deployment (`docs/adr/0002`) ran all three services as
separate WARs on **one shared external Jetty instance** — explicitly
flagged there as not real process isolation: all three lived in the same
JVM, and redeploying any one of them meant restarting Jetty (and
therefore all three) at once.

That setup also carried real accidental complexity that existed *only*
because of the sharing: `providedRuntime` dependency packaging traps
(slf4j-api landing in `WEB-INF/lib-provided` instead of `WEB-INF/lib`),
`providedCompile` needed for `jakarta.servlet-api` on services without a
websocket starter, and an external Jetty deployment descriptor per
service just to exclude Logback's servlet-integration hook. All
documented in the archived repos' ADR 0005 — worth reading once, not
worth carrying forward.

Docker was found to already be installed on the homelab host (unrelated
prior use, unregistered from a years-old SonarQube experiment) — with
schware's user added to the `docker` group, using it needed no new
software install.

## Decision

Each service is now a **plain Spring Boot executable JAR with embedded
Jetty**, one per Docker container, instead of a WAR deployed into a
shared external Jetty. The old repos are archived with a `-jetty` suffix:

| Old (archived) | New |
|---|---|
| [sun-moon-java-platform-order-jetty](https://github.com/schware/sun-moon-java-platform-order-jetty) | [sun-moon-java-platform-order](https://github.com/schware/sun-moon-java-platform-order) |
| [sun-moon-java-platform-kds-jetty](https://github.com/schware/sun-moon-java-platform-kds-jetty) | [sun-moon-java-platform-kds](https://github.com/schware/sun-moon-java-platform-kds) |
| [sun-moon-java-platform-delivery-jetty](https://github.com/schware/sun-moon-java-platform-delivery-jetty) | [sun-moon-java-platform-delivery](https://github.com/schware/sun-moon-java-platform-delivery) |

Same pattern as the earlier Netty → Spring/Jetty pivot
(`sun-moon-java-platform-netty`, also archived): rename-and-freeze the
superseded approach rather than deleting its history, start clean at the
same repo name for the new one.

Each container runs `--network host` (Linux) rather than a Docker bridge
network, so the app can reach Postgres/Redis at `localhost` on the host
exactly like a non-containerized process would — no need to containerize
Postgres/Redis too, or configure `host.docker.internal`. This means each
service still needs its own port (Order 8080, KDS 8081, Delivery 8082) —
host networking doesn't give containers their own network namespace, so
they'd collide on 8080 otherwise.

## Consequences

- Real process isolation: one service crashing or OOMing doesn't take
  the others down. Each container can also get its own memory/CPU limit
  (`docker-compose` `mem_limit`/`cpus`) — see
  [`Debian-Setting/docs/docker.md`](https://github.com/schware/Debian-Setting/blob/master/docs/docker.md).
- Each service redeploys independently (`docker compose up -d --build
  <service>`) without restarting the others — the exact limitation
  `docs/adr/0002` flagged is resolved.
- Lost the shared landing page at one URL — each service is now its own
  host:port. Revisit with a reverse proxy (nginx/Traefik) if a unified
  entry point is wanted later.
- The messaging bus between services (`OrderCreated` → `OrderReady` →
  `OrderDelivered`, `docs/adr/0002`) is still not implemented. Unaffected
  by this change either way.
