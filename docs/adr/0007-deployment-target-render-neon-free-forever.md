# ADR-0007: Deployment target — Render (app) + Neon (Postgres), permanently free

- **Status**: Accepted (target choice); actual deployment is future work, gated on Common Code + Device CRUD existing first
- **Date**: 2026-09-09
- **Deciders**: Project owner (the free-forever requirement and final pick); Claude (researched current 2026 free-tier policies)

## Context

The owner set deploying BO as the next goal, with one hard constraint:
**everything has to be continuously/permanently free** ("전부 지속적인
무료로만") — not a trial credit, not a free period that expires. Free-tier
terms change often enough that this needed checking against current
(2026-09) reality rather than assumption:

- **Fly.io**: free tier ended October 2024. New accounts get a 2-VM-hour/
  7-day trial, then the cheapest plan is $5/month. Disqualified outright.
- **Render**: the free *web service* is still genuinely free (no expiry) —
  750 instance-hours/workspace/month, spins down after 15 minutes idle
  (~1 minute cold start on the next request). But its free *Postgres* now
  expires 30 days after creation (recently shortened from 90) — not
  "continuously free."
- **Oracle Cloud "Always Free"**: compute is genuinely permanent (no
  expiry), but as of June 2026 the flagship Ampere A1 allowance shrank
  (4 OCPU/24GB → 2 OCPU/12GB), idle instances risk being reclaimed
  ("evaluates CPU/memory/network utilization over time"), and there's no
  PaaS convenience — Docker, Postgres, patching, all self-managed.
- **Neon**: genuinely permanent free Postgres, no credit card, 0.5GB
  storage + 100 compute-hours/project/month, scales to zero after 5
  minutes idle (so a low-traffic app effectively never hits the hour cap).

## Decision

**Render (Docker-deployed web service) for the app, Neon for PostgreSQL.**
Both are permanently free under current (2026-09) policy, not trial
allowances, and both are PaaS (git-push-to-deploy, no VM/OS to patch) —
matching this project's minimal-ops-burden pattern elsewhere (in-memory
fakes by default, real infra deferred, `docs/adr/0003`/`0005`).

BO's `/health`, `/metrics`, `/orders`, `/bo/*` are all plain HTTP —
nothing here needs the raw Socket transport (port 9090) exposed publicly,
which Render's free web service tier isn't built for anyway (HTTP(S)
reverse-proxied services, not arbitrary TCP).

**Sequencing** (per the owner's explicit choice — build before deploying,
not deploy-then-iterate): Common Code CRUD → Device CRUD → *then*
everything below.

## Deployment checklist (future work, once Common Code + Device CRUD exist)

1. Build Common Code CRUD (`/bo/common-code/*`) — `docs/adr/0006`'s
   named next step.
2. Build Device CRUD (`/bo/devices/*`).
3. Write a `Dockerfile` (multi-stage: JDK 21 build stage running
   `./gradlew build`, then a slim JRE 21 runtime stage — nothing here
   yet).
4. Create a Neon project; capture its connection string.
5. Run the Flyway migrations (`V1`, `V2`, plus whatever Common
   Code/Device need) against that Neon database.
6. Create a Render web service from `github.com/schware/sun-moon-java-platform`
   (the `master` branch specifically — `main` is the unrelated Spring
   Boot project, `docs/adr/0006`'s memory note applies here too), Docker
   runtime.
7. Set Render environment variables: `POSTGRES_JDBC_URL`/`POSTGRES_USER`/
   `POSTGRES_PASSWORD` (from Neon), `BO_ADMIN_USERNAME`/`BO_ADMIN_PASSWORD`
   (real values — not anything committed to the repo).
8. Give `Bootstrap` a way to pick real adapters over the in-memory fakes
   in production — likely auto-detected from whether `POSTGRES_JDBC_URL`
   is set, rather than a separate mode flag, so local dev (`./gradlew run`
   with no env vars) keeps working unchanged. Not implemented yet.
9. Enable the `Secure` cookie attribute in `LoginEndpoint` once actually
   served over Render's HTTPS (it's commented out today — dev here is
   plain HTTP, see that file).
10. Verify live: the real HTTPS URL responds on `/health`, and BO login
    actually authenticates against Neon-backed Operator data (not the
    in-memory fake).
11. Record the live URL and what was verified in `README.md`.

## Alternatives considered

- **Fly.io** — rejected, no free tier exists anymore for new accounts.
- **Render's own free Postgres** — rejected, 30-day expiry fails the
  "continuously free" requirement outright.
- **Oracle Cloud Always Free VPS** (self-hosted app + Postgres) —
  deferred, not rejected. Genuinely more permanent and a stronger
  DevOps/SRE signal (`Alignment` ADR-0003's third axis) than a PaaS
  click-to-deploy, but self-managed (Docker/Postgres/OS patching), recently
  shrunk, and carries idle-reclaim risk. Worth revisiting later as a
  *second*, more ops-heavy deployment target once the PaaS one works —
  not chosen as the first, lowest-friction path.

## Consequences

- Cold starts (~1 min after 15 min idle on Render, Neon's own scale-to-zero
  on top) are acceptable for a portfolio demo, not for an "always warm"
  requirement — worth stating plainly if this ever comes up in an
  interview context.
- Neon's 0.5GB/100 compute-hour monthly caps are ample for BO's data
  volume (Operators, Common Code, Devices — no user-generated bulk data),
  but would need revisiting if this ever carried real traffic.
- Item 8 above (real-vs-fake adapter selection in `Bootstrap`) is new
  design work this ADR surfaces but doesn't resolve — the existing
  hexagonal ports (`OrderRepository`, `OperatorRepository`, `SessionStore`)
  make swapping trivial once *when* to swap is decided.

## References

- `docs/adr/0003`, `docs/adr/0005`, `docs/adr/0006` (this repo) — the
  fakes-by-default and build-order discipline this ADR extends into deployment.
- `Alignment`'s ROADMAP.md Phase 3 (deployment targets) — this repo now
  has a concrete answer where that phase only named candidates.
