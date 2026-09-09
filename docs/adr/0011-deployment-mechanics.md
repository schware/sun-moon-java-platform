# ADR-0011: Deployment mechanics — Dockerfile, PORT, adapter selection, Secure cookie

- **Status**: Accepted (built; the deployment itself is not done — it needs accounts only the owner can create)
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

`docs/adr/0007` chose Render + Neon and listed an 11-step checklist. With
Common Code and Device CRUD built (`0008`, `0009`) and the event-loop
blocking defect fixed (`0010`), the remaining work is the mechanics of
actually shipping: packaging, port binding, choosing real adapters over
fakes, and the cookie flag TLS requires.

## Decision

### Packaging

A multi-stage `Dockerfile`: `eclipse-temurin:21-jdk` builds via
`./gradlew installDist`, `eclipse-temurin:21-jre` runs the resulting
`bin/`+`lib/` layout as a non-root `platform` user. Build scripts are
copied before sources so dependency resolution caches independently of
code edits. Tests are skipped in the image build (`-x test`) — the live
HTTP tests bind real ports, which is a poor fit for a sandboxed build
step; they run locally and would run in CI.

`gradlew`'s executable bit was missing from the git index (committed 644
from Windows) and is now 755. The Dockerfile also `chmod +x`es it, because
a build context copied from a Windows working tree loses the bit again
regardless of what the index says.

### Port binding

Render injects `PORT` and requires the published service to bind exactly
it. `RuntimeConfig` therefore resolves the BO listener as
`BO_PORT ?? PORT ?? 8080`. BO is the published surface (`docs/adr/0009`),
so `PORT` lands there; the Order API and Socket listeners keep their own
ports and simply aren't published.

### Fakes vs. real adapters

**Presence of `POSTGRES_JDBC_URL` is the switch** — no separate mode flag
to keep in sync. When set, `Bootstrap` builds the HikariCP `DataSource`,
runs Flyway, and constructs the four `MyBatis*Repository` adapters; when
unset it constructs the in-memory fakes and says so in the log. A machine
with only a JDK still runs the whole platform.

Migrations run *before* any repository is handed out, so the schema exists
before the super-admin seed touches it — which makes first boot against an
empty Neon database a single step rather than a manual migration plus a
restart.

**Failure is loud.** Verified by running the packaged distribution with
`POSTGRES_JDBC_URL` pointed at nothing: HikariCP fails fast and the
process exits with the stack trace through `Bootstrap.buildRepositories`.
There is no silent fallback to fakes — that failure mode would look
healthy while quietly discarding every write on restart.

### Secure cookie

`COOKIE_SECURE` (default `false`) drives `Secure` on the BO session
cookie. It stays off locally because plain-HTTP development would
otherwise never receive the cookie at all, and `render.yaml` sets it to
`true` where TLS terminates.

### Service definition as code

`render.yaml` (Render Blueprint) declares the service, health check path,
and environment variables — the five secrets as `sync: false`, so Render
prompts for them once in its dashboard and nothing sensitive is
committed. Deliberately **no database block**: Render's own free Postgres
expires after 30 days, which is exactly why Neon was chosen (`0007`).

## Alternatives considered

- **A fat/shadow JAR** instead of `installDist` — fewer files, but the
  `application` plugin's layout already works, keeps dependencies as
  separate layers, and needs no extra plugin.
- **A `PERSISTENCE_MODE=memory|postgres` flag** — rejected as a second
  source of truth that can disagree with the URL actually configured.
- **Defaulting `COOKIE_SECURE` to true when `PORT` is set** (i.e. "looks
  like a PaaS") — too implicit; an explicit variable set in `render.yaml`
  is easier to reason about.

## Consequences

- Steps 1-2 of `docs/DEPLOYMENT.md` need a Neon account and a Render
  account. Those are the owner's to create — this repo cannot, and should
  not, do it.
- **The Dockerfile has never been built.** No Docker in this environment
  (`docs/adr/0003`). What *is* verified is the part most likely to be
  wrong: `installDist` produces `bin/sun-moon-java-platform` + `lib/`, and
  running that start script directly boots the app and takes the Postgres
  branch correctly. The image build itself is first exercised by Render.
- First deployment is therefore also the first execution of: the
  Dockerfile, every MyBatis mapper, all four Flyway migrations, and the
  `Secure` cookie over real TLS. `docs/DEPLOYMENT.md` §4 lists what to
  check, including the log line that distinguishes real adapters from
  fakes.

## References

- `docs/adr/0007` (target and checklist), `docs/adr/0009` (why BO owns the
  published port), `docs/adr/0010` (the prerequisite this follows).
- `docs/DEPLOYMENT.md` — the runbook these mechanics exist to serve.
