# ADR-0016: This runtime becomes the Device Server

- **Status**: Accepted
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

ADR-0014 split this runtime out of the original monolith and left it with
a stated but unfulfilled purpose: "8083 reserved, business undecided."
`docs/DESIGN.md` §12 listed that plainly as a known gap — a Netty runtime
with a WebSocket echo handler and a `POST /orders` endpoint that created
records nobody consumed.

The owner then walked through the actual order flow this platform is
meant to demonstrate: a customer places an order through a channel, a POS
terminal in a shop accepts or rejects it, the kitchen (KDS) produces it, a
store display (DID) shows it, and a rider delivers it. Every step after
the first needs to reach a physical screen in a specific shop — and that
is exactly what raw Netty, hand-rolled WebSocket handling and a
10,000-connection target were built for in ADR-0002. The business this
runtime lacked was sitting in its own founding rationale.

Two decisions came out of that walkthrough:

- **`sun-moon-java-platform-order`** (the Spring service, `main` branch)
  keeps orders. It already had the database, the JSONB storage shape and
  the family's conventions; giving it a life cycle (ADR-recorded in that
  repo) was less work than building an order service here from nothing.
- **This runtime becomes the Device Server**: it holds terminal
  connections and is the one thing between Order's events and a screen
  in a shop.

## Decision

**This runtime is the Device Server.** It does three things:

1. **Holds terminal WebSocket connections.** A terminal connects to
   `/ws?deviceId=pos-01&storeId=store-01&type=POS` and is identified
   *before* the WebSocket upgrade completes — a client that gets the
   query string wrong is answered with an HTTP 400 it can read, not left
   holding a socket that silently never delivers anything
   (`TerminalHandshakeHandler`).
2. **Subscribes to Order's Redis channel** (`order-events`) and turns
   each event into a push to the terminals that should see it
   (`OrderEventSubscriber`). Which terminals is a routing decision made
   here, not by Order — Order announces a state change and should not
   need to know that POS screens exist.
3. **Proxies Order for terminals** (`OrderProxyEndpoints`,
   `OrderClient`), so a terminal fetches and accepts through this
   server's own origin rather than needing to know where Order lives.

**Terminals are identified by `(storeId, deviceId)`, never `deviceId`
alone.** Every branch has a `pos-01`; keying on the device id by itself
made two shops' first counters the same terminal, so each connection
displaced the other in a loop. `TerminalId(storeId, deviceId)` is the key
for one connection, `TerminalGroup(storeId, type)` is the key for
routing and for the presence check described next.

**A store's staffing has a grace period.** An order placed with no
terminal connected is refused immediately (the customer should not wait
for a timeout to find out nobody is there) — but a terminal that
disconnected within the last few minutes still counts as present, because
a dropped wifi connection and a closed shop are different situations, and
the naive rule punished the common one (`TerminalRegistry.
isPresentOrRecentlySeen`, default 3 minutes, `TERMINAL_GRACE`).

**A terminal that is genuinely displaced (same store, same device id,
new connection) is closed with WebSocket code 4001** rather than
silently, so the losing client stops retrying instead of fighting
forever for an id it no longer holds.

**Ports moved.** `API_PORT` defaults to **8087**, not 8083 — 8083 belongs
to the Spring Order service permanently, and this runtime was only ever
borrowing it under the "business undecided" framing ADR-0014 left open.
`SOCKET_PORT` (9011) is unchanged.

**The legacy `/orders/legacy` endpoint and `domain/order` stay for now,
marked as what they are.** They predate this decision and are what the
runtime built to prove the transport shape before there was a real flow
to plug into. Nothing here depends on them anymore — the real order flow
runs entirely through the Spring Order service and this server's proxy.
Removing them is in `docs/DESIGN.md` §12 as a next step rather than done
silently in this pass.

## Alternatives considered

- **Give this runtime its own order domain, fully replacing Spring
  Order** — rejected. It would duplicate a working service and its
  database for no benefit; the Device Server's job is terminals, not
  orders.
- **Route terminal audience selection in Order** — rejected. It would
  mean Order knowing about POS/KDS/DID, which is exactly the kind of
  downward knowledge ADR-0014 exists to prevent between services built on
  the same kernel.
- **Key terminals by device id alone, and rely on operators choosing
  distinct ids across shops** — tried, in effect, before this ADR. It
  failed the first time two shops were opened side by side: their
  `pos-01`s displaced each other in a reconnect loop that burned a CPU
  core until stopped by hand.

## Consequences

- **Verified end-to-end, live**: a real WebSocket client connecting as
  `store-01/pos-01`, an order placed with no terminal present being
  auto-rejected, an order placed with the terminal connected staying
  `PLACED` and being pushed to that terminal, and an accept call through
  this server's proxy landing in Order as `ACCEPTED` with `acceptedBy`
  set — confirmed by querying Order directly, not only through this
  server's own account of it.
- **Device identity is still format-only.** `AcceptKnownFormatDirectory`
  accepts any well-formed id; it does not ask BO whether that device
  actually exists or belongs to that store. The real check needs
  service-to-service credentials between this server and BO, which have
  not been decided.
- **"One order-receiving terminal per store" is not enforced.** The
  owner's rule — a store may have several terminals, but only one
  receives orders — has no home yet. It needs a `receivesOrders` flag on
  BO's Device record and this server's `DeviceDirectory` widened from a
  yes/no check to an actual lookup. Blocked on the same
  service-to-service credential gap as device authentication; solving
  one is likely to solve both.
- **This server's own tests exercise the real transport.**
  `TerminalRegistryTest` pins the store-isolation property directly;
  `DeviceServerTest` boots the real `CoreRuntime` and drives it with real
  WebSocket and HTTP clients.

## References

- ADR-0002 (why this runtime is Netty at all), ADR-0013/ADR-0014 (the
  port scheme and kernel split that left "business undecided"), ADR-0010
  (off-event-loop execution, which is what makes the REST proxy safe to
  call synchronously from a worker thread).
- The Order service's own ADRs for its life cycle, Redis publishing and
  acceptance timeout.
- `Alignment` ROADMAP — the 2026-09-09 entries recording this pivot and
  the defects found while building it.
