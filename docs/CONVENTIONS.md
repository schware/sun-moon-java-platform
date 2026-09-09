# Family conventions — and how to check them in one command

Four services live in this family: **order**, **kds**, **delivery**, **bo**.
They agree on more than a reader would guess, and the agreements are not
written down in any build file — they exist because each service was
copied from the last one.

This document exists so that "what are the conventions?" is a question you
**run**, not a question you ask. Every rule below comes with the command
that proves it, and every command loops over all four services so a
disagreement shows up as an odd row rather than as something you have to
notice.

Run all of these from a checkout of this umbrella repo with submodules
initialised:

```bash
git clone --recurse-submodules https://github.com/schware/sun-moon-java-platform.git
cd sun-moon-java-platform
```

Throughout, `SERVICES` is the loop:

```bash
SERVICES="order kds delivery bo"
```

---

## 1. The fastest check: diff a new service against an existing one

Before reading any rule below, this is the single highest-value command.
A new service should differ from a sibling only in the lines that are
genuinely about *that service*:

```bash
diff bo/build.gradle.kts delivery/build.gradle.kts
```

If a line differs that is not about the service's own job — a Spring Boot
version, a starter everyone else has, a plugin id — that is a convention
drift, not a design choice.

Same for configuration:

```bash
diff bo/src/main/resources/application.yml delivery/src/main/resources/application.yml
```

---

## 2. Build — same Spring Boot, same plugins, same pins

```bash
for s in $SERVICES; do
  printf '%-9s ' "$s"
  grep -h 'springframework.boot") version\|springdoc-openapi-starter' $s/build.gradle.kts | tr -d ' ' | tr '\n' ' '
  echo
done
```

Expected: identical on every row.

| Rule | Value |
|---|---|
| Spring Boot | `3.3.4` |
| `io.spring.dependency-management` | `1.1.6` |
| springdoc | pinned to `2.6.0` — newer releases target Boot 3.4/3.5 and won't compile here |
| Java toolchain | 21 |
| Gradle | wrapper committed, 8.11 |
| `group` | `com.sunmoon.<service>` — see §4 |
| `rootProject.name` | the repository name |

**Jetty, not Tomcat.** Every service excludes Tomcat and adds Jetty. This
is a leftover of the shared-Jetty era that was kept deliberately:

```bash
for s in $SERVICES; do
  printf '%-9s tomcat-excluded=%s jetty=%s\n' "$s" \
    "$(grep -c 'starter-tomcat' $s/build.gradle.kts)" \
    "$(grep -c 'starter-jetty' $s/build.gradle.kts)"
done
```

Expected: `1 1` on every row.

**What is per-service, not family:** the persistence starter
(`starter-jdbc` for order/delivery/bo, `starter-data-redis` for kds),
`starter-security` (bo only), and resilience4j (order only). Those are
choices, not drift.

### BO's deliberate differences

BO is the only service here with a user interface, and three things follow
from that. They are decisions (ADR-0005), not drift — but check them
against this list before "fixing" them:

| | BO | the others |
|---|---|---|
| API base path | `/api/*` | at the context-path root |
| `/` | serves the React app | redirects to Swagger UI |
| `frontend/` | Vite + React + TypeScript, built into `src/main/resources/static` | none |
| Dockerfile | extra `node:20-alpine` stage | two stages |

The API prefix is the one to understand: BO's SPA route `/board` and its
endpoint `GET /board` were the same URL, so refreshing a screen returned
JSON. Any future service that grows a UI will hit this the same way.

```bash
for s in $SERVICES; do
  frontend=$( [ -d $s/frontend ] && echo yes || echo no )
  prefix=$(grep -rho 'RequestMapping("[^"]*")' $s/src/main/java --include='*Controller.java' | head -1 | cut -d'"' -f2)
  echo "$s  frontend=$frontend  first-mapping=$prefix"
done
```

---

## 3. Ports and context-paths — one line each

```bash
for s in $SERVICES; do
  printf '%-9s ' "$s"
  grep -A3 '^server:' $s/src/main/resources/application.yml \
    | grep -E 'port|context-path' | sed 's/[[:space:]]//g' | paste -sd' ' -
done
```

| Service | Port | context-path |
|---|---|---|
| bo | 8080 | `/bo` |
| kds | 8081 | `/kds` |
| delivery | 8082 | `/delivery` |
| order | 8083 | `/order` |

Ports come from the server-wide scheme, not from this repo — see
`Debian-Setting`'s `docs/docker.md`. **Order's `application.yml` still
says 8080** and is overridden by `-e SERVER_PORT=8083` at run time, so
source and reality disagree there; that is a known wart, not the rule.

**The context-path must appear in three places, and they drift apart
silently.** This command shows all three at once:

```bash
for s in $SERVICES; do
  cfg=$(find $s/src/main/java -type d -name config)
  printf '%-9s yml=%-11s openapi=%-11s redactor=%s\n' "$s" \
    "$(grep -A3 '^server:' $s/src/main/resources/application.yml | grep context-path | sed 's/[[:space:]]//g' | cut -d: -f2)" \
    "$(grep -ho 'Server().url("[^"]*")' $cfg/OpenApiConfig.java | cut -d'"' -f2)" \
    "$(grep -ho 'SERVICE_NAME = "[^"]*"' $cfg/PathRedactor.java | cut -d'"' -f2)"
done
```

All three must name the same service. The OpenAPI one matters because it
drives the "Servers" dropdown in Swagger UI, and it must **never** carry
the server's IP.

---

## 4. Package layout

**The root package is `com.sunmoon.<service>`, and so is `group`, and the
main class is `<Service>Application`.**

```bash
for s in $SERVICES; do
  printf '%-9s group=%-24s main=%s\n' "$s" \
    "$(grep -h '^group' $s/build.gradle.kts | cut -d'"' -f2)" \
    "$(find $s/src/main/java -name '*Application.java' | sed 's|.*/||')"
done
```

⚠️ **`order` is the exception, not the rule.** It is
`com.sunmoon.platform` / `SunMoonApplication` because it was the whole
system before the split, and kept the name. kds and delivery — the two
written after — are `com.sunmoon.kds` / `KdsApplication` and
`com.sunmoon.delivery` / `DeliveryApplication`. Copy those, not order.
(BO was first written by copying order, and this command is what caught
it.)

Inside the root package, all four agree:

```bash
for s in $SERVICES; do
  echo "== $s"
  (cd $s && find src/main/java/com/sunmoon/*/ -type d | sed 's|.*/com/sunmoon/[a-z]*||' | grep . | sort)
done
```

```
<Service>Application.java   @SpringBootApplication, at the package root
config/                     OpenApiConfig, MetricsConfig, PathRedactor,
                            RedactedDiskSpaceHealthIndicator
domain/<aggregate>/         the record, the Request DTO, the Repository
                            interface, the Service
infrastructure/persistence/ Jdbc<Aggregate>Repository — the interface's
                            only implementation
transport/http/             <Aggregate>Controller, RootRedirectController
```

Rules that hold across all four:

- Domain models are **records**. Request DTOs are separate records with
  Jakarta Bean Validation annotations, never the domain record itself.
- The repository **interface** lives in `domain/`, its JDBC implementation
  in `infrastructure/persistence/`. Controllers depend on the interface.
- Constructor injection only — no `@Autowired` fields.
- No ORM anywhere. `JdbcTemplate` with SQL written out.

---

## 5. Operational endpoints

```bash
for s in $SERVICES; do
  printf '%-9s ' "$s"
  grep -A5 '^management:' $s/src/main/resources/application.yml | grep -A2 'include' | tr -d ' \n'
  echo
done
```

Every service exposes exactly `health,prometheus,info` with
`show-details: always`, and every service has:

- `RootRedirectController` — `/` redirects to Swagger UI, so the
  context-path root isn't a Whitelabel 404. **BO is the exception**: it
  has a React console, so its `/` serves the app and it has no
  `RootRedirectController` (ADR-0005).
- `RedactedDiskSpaceHealthIndicator` + `MetricsConfig` + `PathRedactor` —
  both `/actuator/health` and `/actuator/prometheus` would otherwise leak
  the container's absolute working directory. **Copying these three files
  into a new service means changing `SERVICE_NAME` in `PathRedactor`**;
  forgetting is invisible until someone reads a health response.

```bash
for s in $SERVICES; do
  printf '%-9s %s\n' "$s" "$(ls $(find $s/src/main/java -type d -name config) | tr '\n' ' ')"
done
```

---

## 6. Database

```bash
for s in $SERVICES; do
  printf '%-9s ' "$s"
  grep -E 'url: jdbc|redis' $s/src/main/resources/application.yml | head -1 | sed 's/^ *//'
  echo
done
```

- **One database per service**, named `<service>_service`
  (`order_service`, `delivery_service`, `bo_service`). KDS uses Redis.
- Schema: **Flyway is where this family is going**, and `order` is the
  first service there. The others still use
  `src/main/resources/schema.sql` with `spring.sql.init.mode: always`.

  It changed because the schema.sql approach hurt exactly as predicted.
  An idempotent `CREATE` can add a column; it cannot touch the rows
  already in the table. When Order gained a life cycle, two rows written
  before it deserialized with a null timestamp and stopped the expiry
  sweep for every other order — once every ten seconds, in a stack trace
  that named the sweep rather than the data.

  A migration fixed it in one file. `schema.sql` could not have.

  ```bash
  for s in $SERVICES; do
    printf '%-9s %s
' "$s"       "$( [ -d $s/src/main/resources/db/migration ] && echo flyway || echo schema.sql )"
  done
  ```

  Moving a service across: add `flyway-core` and
  `flyway-database-postgresql`, move `schema.sql` into
  `db/migration/V1__*.sql`, drop `spring.sql.init.mode`, and set
  `baseline-on-migrate: true` with `baseline-version: 1` — the deployed
  database already has the tables, and baseline adopts them instead of
  refusing to start on a non-empty schema.
- Order and Delivery store a JSONB blob because they were designed as
  document stores before MongoDB turned out to need AVX this CPU lacks
  (ADR-0001). BO uses plain columns. **The storage shape is per-service**;
  do not copy JSONB into a new service just because two siblings have it.

---

## 7. Docker

```bash
for s in $SERVICES; do echo "== $s"; cat $s/Dockerfile; done
```

Identical in all four: two stages, `eclipse-temurin:21-jdk` to build a
`bootJar`, `eclipse-temurin:21-jre` to run it, `WORKDIR /app`,
`EXPOSE 8080`, `ENTRYPOINT ["java","-jar","app.jar"]`.

Two things that live outside the Dockerfile and bite:

- Build with **`docker build --network host`** on the server — the bridge
  network's DNS fails there.
- Run with **`--network host`**, which is how a container reaches the
  host's Postgres and Redis at `localhost`. It also means UFW actually
  governs the port; with `-p`, Docker writes iptables directly and
  **bypasses UFW**.

---

## 8. Tests

```bash
for s in $SERVICES; do
  printf '%-9s %s test file(s)\n' "$s" "$(find $s/src/test -name '*Test.java' | wc -l)"
done
```

`@WebMvcTest` slices with `@MockBean` on the service layer — no database,
so `./gradlew build` works on a machine with only a JDK. BO additionally
imports the real `SecurityConfig` so its authorization rules are exercised
rather than switched off.

---

## 9. Adding a fifth service — the checklist

1. Copy an existing service's `build.gradle.kts`, `Dockerfile`,
   `.gitignore` and Gradle wrapper. Change `rootProject.name`.
2. Swap the persistence starter if this service stores differently.
3. `application.yml`: port from the server scheme, context-path,
   `spring.application.name`, datasource, the same `management:` block.
4. Copy `config/`. **Change `SERVICE_NAME` in `PathRedactor`** and the
   `Server().url(...)` in `OpenApiConfig`.
5. Write `domain/` → `infrastructure/persistence/` → `transport/http/` in
   that order.
6. `schema.sql`, and `createdb -O sunmoon <service>_service`.
7. Add it as a submodule here, and to `Debian-Setting`'s port table.
8. Run every command in this document and look for the odd row.
