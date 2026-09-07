# 0004: Spring + WAS (Jetty) replaces the hand-rolled Netty runtime

## Status

Accepted.

## Context

The original `sun-moon-java-platform` (now archived at
[`sun-moon-java-platform-netty`](https://github.com/schware/sun-moon-java-platform-netty))
deliberately avoided Spring and hosted REST, WebSocket, and a raw Socket
transport inside one hand-rolled Netty runtime (see its ADR 0002).

That choice trades ecosystem leverage for raw control: no free APM/monitoring
agent integration (most agents instrument the Servlet Filter chain, not a
custom Netty pipeline), no container-provided session clustering, no
accumulated WAS troubleshooting playbooks, and a much smaller pool of
engineers who can safely operate a hand-rolled Netty pipeline versus a
Spring/Servlet stack. For a project meant to demonstrate conventional
enterprise Java practice, those costs outweigh the raw-throughput benefit
Netty offers at very high connection counts.

## Decision

Rebuild on Spring Boot, packaged as a WAR, deployed to a standalone Jetty
instance (already provisioned on the Debian host under `/apps/java-war/`)
instead of an embedded/executable JAR.

## Consequences

- REST (`OrderController`) and WebSocket (`EchoWebSocketHandler`) now run
  as Servlet-API components, dispatched by Jetty — not by a custom Netty
  pipeline.
- Health and metrics are exposed by Spring Boot Actuator
  (`/actuator/health`, `/actuator/prometheus`) instead of hand-rolled
  endpoints.
- The raw Socket transport (port 9090 in the Netty version) is **dropped**.
  There's no Servlet-API equivalent, and keeping it would mean running a
  manually-managed socket listener alongside the container anyway — the
  exact hybrid this rewrite is meant to avoid. It only ever served as an
  echo demo, so the loss is not material.
- The batch job is a single `ApplicationRunner` pass on startup rather than
  a hand-rolled Job/Step/Chunk engine triggered by Quartz — full Spring
  Batch (with its own `JobRepository` schema) was judged as more
  infrastructure than this scaffold's one demo job warrants. Revisit if
  the batch surface grows.
- Persistence, cache, and messaging still have no real adapters wired
  (Oracle/MyBatis, Redis, Kafka) — same "fakes by default" posture as the
  Netty version's ADR 0003, now expressed as Spring `@Repository`/
  `@Component` beans instead of manual `Bootstrap` wiring.
