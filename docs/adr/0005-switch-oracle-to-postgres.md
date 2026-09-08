# ADR-0005: Switch Persistence from Oracle to PostgreSQL platform-wide; no local DB install, verify at deploy time

- **Status**: Accepted (the switch and the "no local install" scope); live verification is deferred, not done
- **Date**: 2026-09-08
- **Deciders**: Project owner

## Context

ADR-0002 chose Oracle (matching the owner's 8 years of production experience,
`Alignment` ADR-0005). ADR-0003 flagged that Oracle was never live-verified
here (no Docker in this dev environment). While scoping BO's persistence
layer, the owner proposed PostgreSQL instead, reasoning:

- Portable/deployable on the free-tier targets `Alignment`'s ROADMAP Phase 3
  already names (Fly.io/Render/a small VPS) — Oracle realistically isn't.
- Available natively via `winget` on this machine (`PostgreSQL.PostgreSQL.17`),
  unlike Oracle which needs Docker.
- MyBatis + Flyway stay exactly as planned — both are DB-neutral; only the
  driver/dialect changes.

Claude installed PostgreSQL 17 locally via `winget` to close ADR-0003's
"not live-verified" gap immediately. The owner reconsidered: a database
installed on this dev machine isn't obviously the right place for it —
"나중에 소스를 올리는 곳에 설치하면 되는 것 아닌가요" (isn't it something
to install wherever the source actually gets deployed?). Also, the local
install registered as an always-running, auto-start Windows Service —
a materially different footprint than the JDK/Gradle installs from
ADR-0002, and not flagged as such before installing (see
[[feedback-think-before-executing]] in Claude's memory). It was uninstalled
back out (service, binaries, and registry entry removed cleanly; one inert
empty folder was left behind, blocked by this session's lack of elevated
permissions — harmless, no service, no files).

## Decision

**PostgreSQL replaces Oracle everywhere in the stack** — this isn't a
BO-only change, it's a platform-wide swap of ADR-0002's persistence choice.

**No local database instance in this dev environment.** Code is written
and compiled against PostgreSQL (driver, Flyway dialect, connection
settings), following the exact same discipline ADR-0003 already
established for Oracle/Redis/Kafka: a real adapter that compiles but
isn't exercised against a live instance, with the in-memory fake wired in
by default. The difference from ADR-0003's original items: this is a
deliberate choice to *not* stand up local infra at all (not just "can't,
no Docker") — live verification is explicitly deferred to wherever this
gets deployed (`Alignment` ROADMAP Phase 3), where a managed/hosted
Postgres instance will exist as part of that deployment, not as a
separate local-dev concern.

**Concrete changes**:

- `build.gradle.kts`: `org.postgresql:postgresql` replaces
  `com.oracle.database.jdbc:ojdbc11`; `flyway-database-postgresql`
  replaces `flyway-database-oracle`.
- `OracleConnectionSettings` → `PostgresConnectionSettings`
  (`POSTGRES_JDBC_URL`/`POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_POOL_SIZE`
  env vars; default JDBC URL `jdbc:postgresql://localhost:5432/sunmoon`).
- `MyBatisConfig`: driver class `org.postgresql.Driver`.
- `V1__create_orders_table.sql`: `NUMBER(19)`→`BIGINT`,
  `VARCHAR2(64)`→`VARCHAR(64)`, `NUMBER(12,2)`→`NUMERIC(12,2)`.
- `OrderMapper`'s `OFFSET ... ROWS FETCH NEXT ... ROWS ONLY` pagination is
  ANSI SQL:2008 — valid in PostgreSQL unchanged, no rewrite needed.
- `docker-compose.yml`: the `oracle` service (`gvenzl/oracle-free`)
  becomes a `postgres` service (`postgres:17-alpine`) — still there for
  whenever local/CI containerized testing is wanted, just not run today.

## Alternatives considered

- **Keep Oracle** — rejected: not realistically deployable on the free/cheap
  targets Phase 3 names, and installing it locally needs Docker (a bigger
  ask, already declined once in ADR-0003's own reasoning).
- **Install Postgres locally via winget** — tried, then reverted. Real
  benefit (live verification today) was outweighed by an undisclosed
  persistent-service footprint the owner didn't sign up for. Revisit if
  local live verification becomes worth that tradeoff later — reinstalling
  is cheap.
- **Install Docker for a disposable local Postgres container** — not
  taken; same "bigger ask, not done unprompted" reasoning as ADR-0003.

## Consequences

- BO's persistence layer (Operator/Screen/Permission — see `docs/adr/0004`)
  is designed against Postgres from the start, not Oracle-then-migrated.
- Still **zero live database verification** of any MyBatis/Flyway code in
  this environment — same epistemic status as ADR-0003, just against a
  different target DB. Don't claim otherwise until it's actually run
  against a live instance (local, CI, or the eventual deploy target).
- `Alignment`'s ADR-0005 (8 years of Oracle production experience) stops
  being a reason to expect this portfolio repo's DB choice to match it —
  worth a brief note there if this ever needs explaining ("why Postgres if
  you know Oracle") — the answer is portability/deployability, not a gap
  in Oracle knowledge.

## References

- `docs/adr/0002`, `docs/adr/0003` (this repo).
- `Alignment`'s ROADMAP.md Phase 3 (deployment targets).
- [[feedback-think-before-executing]] (Claude's memory) — the
  "flag the footprint before installing" lesson from this ADR's own Context.
