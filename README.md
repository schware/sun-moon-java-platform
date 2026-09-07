# sun-moon-java-platform

A Java, DDD-based **Enterprise Runtime Platform**, rebuilt on **Spring Boot**,
deployed as a **WAR** to a standalone **Jetty** servlet container.

This replaces the earlier hand-rolled-Netty version of this project, now
archived at
[`sun-moon-java-platform-netty`](https://github.com/schware/sun-moon-java-platform-netty).
See [`docs/adr/0004`](docs/adr/0004-spring-was-jetty-replaces-netty.md) for
why.

This is the Java counterpart to `sun-moon-python-platform` and
`sun-moon-c-server` — same `sun-moon-*` family.

## Status

**Working scaffold, not a finished system.** Verified live-deployed to
Jetty on the Debian host (see [`docs/adr/0005`](docs/adr/0005-war-slf4j-classpath-bug.md)
for a WAR/classpath bug hit and fixed along the way):

- **REST**: `POST /orders` (Jakarta Bean Validation + Resilience4j around
  the event-publish call)
- **WebSocket**: `/ws` (echo)
- **Actuator**: `/actuator/health`, `/actuator/prometheus` (disk-path
  details redacted to the server's home directory — see
  `config/RedactedDiskSpaceHealthIndicator` and `config/MetricsConfig`)
- **API docs**: OpenAPI 3 / Swagger UI via springdoc — see
  [`docs/swagger.md`](docs/swagger.md) for how to annotate new endpoints
- **Batch**: a single startup pass (`OrderSummaryStartupRunner`) that sums
  seeded in-memory orders and logs the report

**No real infrastructure adapters yet** — persistence, cache, and messaging
are all in-memory fakes (`InMemoryOrderRepository`,
`InMemoryEventPublisher`). Wiring real Oracle/MyBatis, Redis, and Kafka
adapters behind Spring profiles is follow-up work, not done here.

**Dropped from the Netty version:** the raw Socket transport (port 9090).
There's no Servlet-API equivalent — see the ADR.

**Planned (not started):** splitting into separate Order / KDS / Delivery
services once better hardware is available — direction, messaging choice,
and per-service NoSQL data strategy are documented in
[`docs/adr/0006`](docs/adr/0006-future-msa-split.md) so it isn't
re-litigated later.

## Stack

| Concern | Choice |
|---|---|
| Web / REST | Spring MVC (Spring Boot) |
| WebSocket | Spring WebSocket |
| Container | Jetty (external, WAR deployment) |
| Validation | Jakarta Bean Validation (Hibernate Validator via Spring Boot) |
| Resilience | Resilience4j (Spring Boot starter) |
| Monitoring | Micrometer → Prometheus, via Spring Boot Actuator |
| Testing | JUnit5, Mockito, Spring Boot Test (`@WebMvcTest`) |

## Build & run

Requires JDK 21+. The Gradle wrapper is committed, so no local Gradle
install is needed.

```
./gradlew test
./gradlew bootWar
```

`bootWar` produces `build/libs/sun-moon-java-platform-0.1.0.war`. Deploy it
by copying to the Jetty `webapps/` autodeploy directory:

```
cp build/libs/sun-moon-java-platform-0.1.0.war ~/apps/java-war/webapps/sun-moon-java-platform.war
```

Jetty also needs an external deployment descriptor alongside the WAR
(`~/apps/java-war/base/webapps/sun-moon-java-platform.xml` on the Debian
host) — see [`docs/adr/0005`](docs/adr/0005-war-slf4j-classpath-bug.md) for
what it does and why it has to be external rather than the WAR's own
`WEB-INF/jetty-web.xml`. It isn't checked into this repo since it's
host-specific deployment config, not application source.

Once both are in place, Jetty picks the WAR up automatically. Then:

```
curl http://localhost:8080/sun-moon-java-platform/actuator/health
curl -X POST http://localhost:8080/sun-moon-java-platform/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust-1","amount":42.50}'
```

For local development without deploying to Jetty, `./gradlew bootRun`
starts an embedded Jetty on port 8080 (no context path prefix in that
mode).

## Structure

```
src/main/java/com/sunmoon/platform/
  SunMoonApplication.java      Spring Boot entry point + WAR servlet initializer
  domain/order/                 Order aggregate, OrderRepository port, OrderService
  transport/http/               REST controllers
  transport/ws/                 WebSocket handler + config
  batch/                        Startup order-summary pass
  infrastructure/persistence/   In-memory OrderRepository (fake; real adapter TBD)
  infrastructure/messaging/     In-memory EventPublisher (fake; real adapter TBD)
  config/                       OpenAPI/Swagger setup, Actuator path-redaction
```

See `docs/adr/` for the reasoning behind each architectural decision.
