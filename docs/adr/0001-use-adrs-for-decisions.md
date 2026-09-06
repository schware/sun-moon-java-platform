# ADR-0001: Record architecture/direction decisions as ADRs

- **Status**: Accepted
- **Date**: 2026-09-06
- **Deciders**: Project owner

## Context

This repo (`sun-moon-java-platform`) is the third implementation in the
`sun-moon-*` family, alongside `sun-moon-python-platform` and
`sun-moon-c-server` — both of which already record their design reasoning
as ADRs rather than in prose scattered across READMEs or chat history. The
direction for this repo specifically was worked out over several turns in
`Alignment` (that repo's ADR-0008 through 0010) before any code existed
here; without a record inside this repo itself, a future reader would have
no way to find that reasoning without also having `Alignment` open.

## Decision

Same rule as the other two `sun-moon-*` repos: **any decision that affects
this repo's architecture gets a `docs/adr/NNNN-title.md` file**, following
`0000-template.md`. Decisions that originated in `Alignment` (the DDD
Enterprise Runtime Platform shape, the no-Spring stack, the Netty
multi-transport approach) are restated here as ADR-0002, so this repo is
self-contained — a reader shouldn't have to cross reference `Alignment` to
understand why this codebase looks the way it does.

## Alternatives considered

- **Only keep the reasoning in `Alignment`** — rejected: `Alignment` is
  explicitly the career-strategy layer, not this system's own technical
  record (see that repo's own README on the separation), and its ADRs are
  written for that audience, not for someone reading this codebase cold.
- **No ADRs, rely on commit messages/README** — rejected for the same
  reasons `sun-moon-python-platform`'s ADR-0001 rejected it.

## Consequences

- One file per decision, same discipline as the other two repos.
- This repo's ADR numbering is independent of `Alignment`'s — ADR-0002
  here is not the same decision as `Alignment`'s ADR-0002.

## References

- `sun-moon-python-platform`'s ADR-0001 (same rule, same rationale).
- `Alignment`'s ADR-0008, ADR-0009, ADR-0010 — the origin of this repo's
  initial direction, restated locally in ADR-0002.
