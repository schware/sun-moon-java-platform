# ADR-0009: Device CRUD built; BO and the Order API split onto separate ports

- **Status**: Accepted (built and live-verified)
- **Date**: 2026-09-09
- **Deciders**: Project owner (the port split); Claude (the listener abstraction that implements it)

## Context

Two things landed together:

1. **Device CRUD** was the last BO screen outstanding (`docs/adr/0007`'s
   deployment checklist item 2, `docs/adr/0008`'s named next step).
2. The owner asked to **re-organise ports**: BO should own `8080`, and the
   Order API — which had been sharing `8080` with everything else — moves
   to `8083`.

Until now a single HTTP listener on `8080` served everything: `/health`,
`/metrics`, `/orders`, `/ws`, and all of `/bo/*`.

## Decision

### Ports

| Port | Listener | Serves |
|---|---|---|
| 8080 | BO | `/health`, `/metrics`, `/bo/auth/*`, `/bo/common-code`, `/bo/devices` |
| 8083 | Order API | `/health`, `/orders`, WebSocket `/ws` |
| 9090 | Socket | raw TCP transport |

Overridable via `BO_PORT` / `API_PORT` / `SOCKET_PORT`.

**This splits exposure boundaries, not processes.** It's still one JVM and
one shared pair of Netty event-loop groups (`docs/adr/0002`'s "single
runtime" premise is unchanged). The practical payoff is deployment
(`docs/adr/0007`): Render publishes one port per service, so BO can be
published without also exposing the Order API. `/health` is deliberately
on both — each listener needs to be independently probeable.

Worth noting the tension with `Alignment`'s ADR-0007 ("prefer process
isolation over port-splitting"), which pushed `sun-moon-c-server` *away*
from one-process-many-ports. That ADR was about a process that juggled
unrelated *modes* via config; this is one platform runtime deliberately
publishing different surfaces on different ports. Same mechanism,
different reason — but if the Order API ever needs to scale or fail
independently of BO, that ADR's argument applies and this becomes two
deployables.

Implementation: `HttpListenerSpec(name, port, routes, webSocketEnabled)`,
with `CoreRuntime` taking a `List<HttpListenerSpec>` plus the socket port.
`HttpServerInitializer` now takes a `webSocketEnabled` flag — BO is REST
only; the WebSocket transport stays with the Order API listener.
`RuntimeConfig` no longer reaches into `CoreRuntime` at all (its
`forTest` factory is gone; tests construct listeners directly).

### Device CRUD

Same shape as Common Code (`docs/adr/0008`), as that ADR predicted:
`Device(deviceId, name, deviceType, location, active)` + `DeviceRepository`
port, `InMemoryDeviceRepository` (default) + `MyBatisDeviceRepository`
(PostgreSQL, unverified per `docs/adr/0005`), `V4__create_device_table.sql`,
and four `AuthorizedEndpoint`-wrapped endpoints on `/bo/devices`
(`GET` list — all or `?type=X` — `POST` 신규 → 409 on duplicate, `PUT`
저장 → 404 if absent, `DELETE` by body key).

**Master data only.** No connection state, no handshake — those belong to
the Device Server (`docs/adr/0004`), still unbuilt. `deviceType` is
expected to reference a `DEVICE_TYPE` Common Code but isn't a foreign key:
Common Codes are runtime-editable, and a dangling type shouldn't make a
device row unreadable.

**Live-verified**: `DeviceCrudTest` (full CRUD cycle, 409 duplicate, 404
save-of-missing, blank `deviceId` → 400, and an operator with
view+create-but-not-save/delete getting 201/200 on those and 403 on the
other two — proving the four action bits are independent), plus a manual
run confirming BO on 8080 and the Order API on 8083 serve their own routes
and 404 on each other's. 23 tests total, all passing.

## Alternatives considered

- **Keep one listener, separate by path prefix only** — rejected by the
  owner's instruction, and it would defeat the deployment payoff: a single
  port means publishing BO publishes `/orders` too.
- **Split into two processes now** — not taken; that's `Alignment`
  ADR-0007's endpoint, but it contradicts this repo's single-runtime
  premise and adds deployment cost for no current benefit. Noted above as
  the trigger condition if independent scaling ever matters.

## Consequences

- All BO screens named in `docs/adr/0004` now exist. The remaining BO gaps
  are an Operator-management CRUD screen (permissions are still only
  settable via direct repository calls) and `RedisSessionStore`.
- `docs/adr/0007`'s checklist items 1-2 are done; the next item is the
  `Dockerfile`, and Render should publish `BO_PORT`.
- Anything that assumed "the HTTP port" (docs, scripts, the README's curl
  examples) needed updating — `/orders` examples now use 8083.

## References

- `docs/adr/0004` (BO scope, Device Server split), `docs/adr/0007`
  (deployment checklist this unblocks), `docs/adr/0008` (the CRUD +
  routing shape this reuses).
- `Alignment`'s ADR-0007 — the process-isolation-vs-port-splitting
  argument this decision deliberately sits opposite to, and why.
