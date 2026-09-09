# ADR-0005: BO ships its own React/TypeScript console, served from its own jar

- **Status**: Accepted
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

BO went live as an API with Swagger UI standing in for screens. That is
fine for proving endpoints and useless as an admin console: it cannot show
an operator what they are allowed to do, and it asks them to compose JSON.

Two questions had to be answered together.

**What to build it in.** The owner chose **TypeScript + React**. The
alternative on the table was plain HTML/JS with no build step, which I had
started and which would have been adequate — BO's screens are forms and
tables. What decided it was outside this repository: `Alignment`'s Phase 4
is a *TypeScript Dashboard*, listed as the most likely thing to be cut
because it proves a skill with no prior production exposure. Building BO's
console in TypeScript turns that phase from a plan into an artifact.

**Where to serve it from.** This is the constraint that actually shaped
the design: **BO has no CORS configuration.** A frontend on its own origin
— a second container, a separate port — cannot send the session cookie or
the CSRF token until CORS-with-credentials is configured on both ends.
Same-origin removes the whole class of problem before it starts.

## Decision

**The console is a Vite + React + TypeScript app in `bo/frontend/`, built
into `src/main/resources/static`, and shipped inside the BO jar.**

- One container, one deploy, one origin. No CORS, no second nginx, no
  cookie configuration to get wrong.
- **Node appears only in a Docker build stage** (`node:20-alpine`), which
  copies the built assets forward into the JDK stage. The deploy host — a
  2010 CPU running Debian 11 — has no Node and needs none.
- `tsc --noEmit` runs as part of `npm run build`, so a type error fails
  the image build rather than reaching a browser.

**The API moved to `/bo/api/*`.** This was forced, not stylistic: the SPA
route `/bo/board` and the endpoint `GET /bo/board` were the same URL, so
refreshing the board screen returned JSON. Splitting them lets the client
routes forward to `index.html` while genuine API 404s stay JSON. The
forwards are enumerated one path at a time rather than matched by
wildcard, because a catch-all would answer "no such endpoint" with a page
of HTML.

**The menu is permission-aware, and that is presentation only.** The left
menu lists screens the operator holds some permission on; buttons that
would return 403 are disabled. Every controller method still carries its
own `@PreAuthorize`, and the server is what refuses. The UI's job is to
stop offering doors nobody can open.

A **공지사항 board** was added at the owner's request as the first screen
built end to end this way — domain, JDBC repository, controller with the
same three permission tiers, `posts` table, and screen. Its author comes
from the session rather than the request body, which a test pins.

## Alternatives considered

- **Plain HTML/CSS/JS, no build.** Started, then dropped. Genuinely
  simpler and would have shipped sooner; it earns nothing toward Phase 4
  and gives the growing screen count no type safety.
- **A separate frontend container behind nginx.** Rejected: it buys
  independent deploys BO does not need, and costs a CORS configuration BO
  does not have. Reconsider if a second client ever consumes this API.
- **Server-rendered templates (Thymeleaf).** Would also be same-origin and
  need no build step, and is arguably the most conventional Spring answer.
  Rejected for the same Phase 4 reason, and because the screens are
  interactive tables rather than documents.
- **Keeping the API at `/bo/*` and routing the SPA under `/bo/ui/*`.**
  Equivalent technically; `/api` is the more common convention and reads
  better in the Swagger tag list.

## Consequences

- **The image build is slower** — `npm ci` plus a Vite build now precede
  the Gradle build. On the deploy host that is minutes, not seconds.
- **BO diverges from its siblings**, deliberately: it is the only service
  here with a frontend, the only one without `RootRedirectController` (its
  `/` serves the app), and the only one whose API sits under `/api`.
  `docs/CONVENTIONS.md` records these as intended differences rather than
  drift.
- **Phase 4's evidence now lives inside a working system** rather than a
  standalone demo, which is a stronger artifact and also means it is no
  longer separately cuttable.
- The console is verified only as far as the login screen rendering and
  every route resolving; the screens behind the login were not clicked
  through by an automated session, because entering a password is not
  something it does.

## References

- ADR-0004 (BO joins this family as a Spring service), ADR-0001
  (per-service database choice), [`../CONVENTIONS.md`](../CONVENTIONS.md).
- `Alignment` ROADMAP — Phase 4, the TypeScript track this feeds.
