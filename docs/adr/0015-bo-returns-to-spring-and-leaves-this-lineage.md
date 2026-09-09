# ADR-0015: BO returns to Spring and leaves this lineage

- **Status**: Accepted — supersedes the BO half of ADR-0014, and ends
  ADR-0004/0006/0008/0009's line of BO work in this repository
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

ADR-0014, earlier the same day, split this codebase into a kernel and two
services and gave BO its own repository, `sun-moon-platform-bo`. That
answered "how many containers" but left a question it did not think to
ask: *should BO have been Spring-free at all?*

Spring was dropped platform-wide on 2026-09-06 (ADR-0002, `Alignment`
ADR-0010) — two days before BO was proposed. BO never got its own
decision; it inherited one made when it did not exist.

At the time the inheritance was harmless, because there was no choice to
make. BO was a set of `/bo/*` routes inside a single Netty runtime, and
Spring MVC cannot share a process with raw Netty channel pipelines. **The
split is what created the choice** — and made the comparison plain:

- Every mechanism BO hand-wrote — `SessionStore`, cookie parsing,
  `PasswordHasher`, the `AuthorizedEndpoint` decorator — is what
  `spring-boot-starter-security` exists to provide.
- Every gap BO still had — no Operator screen, no CORS, no
  `RedisSessionStore` — is configuration or one controller in Spring.
- BO has **no throughput requirement**. Its users are a handful of
  operators. Netty's value is event loops and ten thousand connections;
  BO collects none of it while paying the full cost of having no
  framework.
- BO was also the only service on that server not built like its
  siblings, which is a cost that shows up every time someone reads it.

## Decision

**BO is rebuilt on Spring Boot as `sun-moon-java-platform-bo`, the fourth
service in the Spring MSA family on the `main` branch**, and this
repository's BO lineage ends here.

- `sun-moon-platform-bo` is renamed **`sun-moon-platform-bo-netty`** and
  archived, following the `-jetty` convention already used for superseded
  implementations.
- The design it encodes — session auth over JWT, the three permission
  tiers, `create`/`save` as separate operations, devices as master data
  only — is preserved verbatim in the rewrite. **What changed is the
  machinery, not the model.** ADR-0004, 0006, 0008 and 0009 in this
  repository remain the record of *why the model is what it is*.
- The full decision, and the conventions the rewrite follows, live in the
  `main` branch's `docs/adr/0004` and `docs/CONVENTIONS.md`.

**This runtime does not follow BO to Spring.** It exists because it needs
raw sockets, a WebSocket transport and a 1,000-10,000 connection target;
those are the things Netty is for and the things Spring would take away.
ADR-0002 stands for this repository.

## Alternatives considered

- **Keep the Netty BO** — rejected. It was a working, tested service, and
  archiving working code is a real cost; but the cost of keeping it was
  paid continuously, in every future BO screen, by a service that gains
  nothing from the runtime it sits on.
- **Move this runtime to Spring as well** — not chosen. The owner's
  "unify on Spring" was about BO; a Spring-based device transport would
  discard the entire reason this repository exists.
- **Wait until BO grows before deciding** — rejected. The rewrite gets
  more expensive with each screen added, and the screens not yet built are
  precisely the ones Spring makes cheap.

## Consequences

- **ADR-0014's kernel now has one consumer, and is archived.**
  `sun-moon-platform-core` was extracted so BO and this runtime could
  share it without depending on each other; with BO gone, only this
  runtime remains, so the repository is now **read-only on GitHub**
  (2026-09-09, at the owner's instruction).

  This freezes the kernel without dissolving it. The `core/` submodule
  still clones and still builds — archiving blocks pushes, not reads — so
  nothing about building or deploying this runtime changes. What it costs
  is that **changing the kernel now needs the repository unarchived
  first**, which is a deliberate speed bump on a boundary that is supposed
  to hold still: endpoints never run on an event-loop thread, and the
  kernel imports nothing downward. Those two rules are worth keeping even
  at one consumer.

  The alternative — folding the 15 classes back into this repository and
  deleting the submodule — was not taken. It would undo ADR-0014's
  verified boundary work to save a `--recurse-submodules`, and it remains
  available if a kernel change is ever actually needed.
- **The portfolio claim changes and should be stated as it is.** The
  hand-built version demonstrated understanding the mechanisms; it still
  exists, archived, with its reasoning intact. What this decision
  demonstrates is different and rarer: noticing that a component carried
  the wrong amount of machinery for its job, and reversing a decision made
  two days earlier rather than defending it.
- Documents in this repository that describe BO as a sibling built on this
  kernel are now wrong and have been corrected to point at the Spring
  service and the archived repository.

## References

- ADR-0014 (the split whose BO half this supersedes), ADR-0002 (Spring
  dropped platform-wide — unchanged for this runtime), ADR-0004 (BO's
  scope and session auth), ADR-0006, ADR-0008, ADR-0009.
- `main` branch: `docs/adr/0004`, `docs/CONVENTIONS.md`.
- `Alignment` ADR-0010 (the platform-wide Spring drop this partially
  walks back).
