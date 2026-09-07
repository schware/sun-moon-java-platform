# 0001: Per-service database choice — and a hardware discovery that changed it

## Status

Accepted and implemented.

## Context

Per the MSA split direction (`docs/adr/0002`), each service owns its own
database — no shared schema, no cross-service joins. The original plan
(decided in conversation, direction-only at the time):

- **Order**: MongoDB — orders map naturally onto documents.
- **KDS**: Redis — small, hot, constantly-updated working set (active
  kitchen queue), no need for a durable document store.
- **Delivery**: MongoDB — deliveries map onto documents too, and
  courier-assignment could later use geospatial (`$near`) queries.

## What actually happened

Redis installed and worked immediately. **MongoDB did not.**

`mongod` (7.0, then 4.4) crashed on start with `SIGILL` — an illegal
instruction, not a config problem. The homelab host's CPU is an Intel
Core i5 **M 480** (2010, Arrandale generation). MongoDB 5.0+ requires
**AVX**, which wasn't introduced until Sandy Bridge (2011) — this CPU
predates it entirely and cannot run MongoDB 5.0+ at all, under any
configuration.

The fallback — MongoDB 4.4, the last pre-AVX version — turned out to be a
dead end too: 4.4 reached EOL in February 2024, and by the time this was
attempted, MongoDB had removed the actual `mongod` server packages from
its apt repository for that version entirely (only client tools like
`mongosh`/`mongocli` remained under `bullseye/mongodb-org/4.4`). Official
MongoDB is not installable on this box, full stop.

## Decision

**Order and Delivery: Postgres + JSONB, not MongoDB.**

Both services store their aggregate as a single JSONB column keyed by an
id (`orders`/`deliveries` tables), with one plain indexed column pulled
out for lookups (`customer_id`, `order_id` respectively) — see
`JdbcOrderRepository` / `JdbcDeliveryRepository` in each service repo.
This gives the same "whole object as one blob" access pattern a document
store would, on hardware that actually runs it. Postgres has no AVX
requirement.

**KDS: Redis, as originally planned** — unaffected by this, since Redis
installed and ran without issue.

## Consequences

- If a *future* geospatial requirement for Delivery genuinely needs
  MongoDB-grade `$near` queries, Postgres's PostGIS extension is the
  fallback to evaluate — not MongoDB, given the hardware constraint is
  about the CPU, not the specific database product.
- If/when the family moves to new hardware with AVX support (already
  planned per `docs/adr/0002`), MongoDB becomes viable again — but by
  then, Postgres+JSONB may simply be working well enough that there's no
  real reason to migrate. Revisit only if a concrete need shows up, not
  by default.
- This is a good example of why `docs/adr/0002` explicitly deferred
  broker/DB *specifics* to implementation time rather than locking them
  in on paper: the actual hardware had a hard veto that no amount of
  up-front planning would have caught without just trying it.
