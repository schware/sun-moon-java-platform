# ADR-0009: 거래번호는 POS가 채번한다

- **Status**: Accepted
- **Date**: 2026-09-16
- **Deciders**: Project owner

## Context

ADR-0006 settled the sales key as
`(매출일자, 매장, 단말기번호, 거래번호)` and recorded that 거래번호 was
not built. Nothing has needed it since — until the receipt.

The owner specified what a receipt carries:

> TR의 기준에서 판매 일자 + 점포 코드 + POS 번호 + TRAN 번호 는 KEY 값이고,
> QR 번호는 추가적으로 관리 하는 값으로 이해 하시면 됩니다.

The receipt is the sales key, in human-readable form. Three of its four
parts already exist on every order — `storeId`, `businessDate`, and
`acceptedBy` (the POS that took it). Only 거래번호 is missing.

I proposed that OMS mint it, reasoning that the sales key is OMS's
concern. The owner corrected this:

> 거래 번호는 채번은 OMS가 아닌 POS가 하는 것입니다.

## Decision

### TRAN 번호는 단말의 것이고, 단말이 매긴다

Two reasons, and the second is the real one.

**It is a per-terminal sequence.** 단말기번호 is an axis of the key
precisely because a transaction belongs to a terminal. "3번 POS의 오늘
47번째 거래" is one fact, not two joined ones.

**Offline.** A POS that asks a server for its number cannot complete a
sale when the server is unreachable. Local minting is the thing that
makes store-and-forward possible at all — see ADR-0010, which is the
other half of this decision.

### 채번 단위는 (판매일자 + 점포 + POS)

Each terminal starts at 1 each business day. This mirrors the key
exactly, so a number is never ambiguous about which sequence it belongs
to.

### 중복 방지는 데이터베이스에서

A unique constraint on `(판매일자, 점포, POS, TRAN번호)`. If a terminal
sends a number that is already taken, OMS refuses and the terminal
retries with the next one.

This constraint does more work than it looks like. It is also **what
makes re-sending safe**: a batch that uploads the same transaction twice
has its second copy rejected by the database. Idempotency for
store-and-forward comes free, from a constraint that had to exist anyway.

### QR번호는 키가 아니다

It is an attribute of the transaction. Practically this means a QR number
can be **reissued without touching the transaction's identity** — a
customer who lost their receipt gets a new number against the same
transaction. It needs a secondary index, because a transaction will be
looked up by it.

The QR number and the 수령번호 of ADR-0007 should be **the same value**:
the QR is for a scanner and the printed number is for a person, and one
value with two representations means one comparison at the counter — and
means a broken scanner degrades to reading the number aloud rather than
stopping the queue.

### 거래번호는 수락 호출에 실려 온다

`POST /orders/status` already carries `deviceId`. `tranNo` rides along
with it. No new endpoint, no new path.

## Consequences

- **Rejected and expired orders carry a 거래번호 too.** A transaction
  number marks an attempt; whether it became 매출 is decided by status.
  The sales aggregate already counts only accepted orders
  (`JdbcSalesRepository.ACCEPTED_STATUSES`), so this needs no change.
- **Open: exactly when the number is minted.** The owner described it as
  "OMS에서 POS로 전달되면", which reads as hand-off rather than
  acceptance. The two differ once a store has more than one POS, and a
  technical finding narrows it: **OMS does not know which terminals
  exist** — the Device Server does, and OMS only publishes an event that
  gets broadcast to every POS at the store. Minting at hand-off would
  make OMS ask POS-SERVER which terminal to use, inverting a dependency
  that currently runs the other way. Minting when a POS takes the order
  needs nothing new, because the accept call already names the terminal.
  With one POS per store the two are indistinguishable, so this is not
  blocking; it must be settled before a store has two.
- **Receipt printing does not exist.** POS is a browser tab that knows
  nothing about printers. First pass is `window.print()` with a print
  stylesheet; ESC/POS thermal is a later subsystem, and BO's device
  master already anticipates it — a 장비 with a null `terminalType` and an
  IP/PORT, which is exactly what `SocketConnectionChecker` describes.
- **This supersedes part of ADR-0006's last bullet.** That ADR expected
  거래번호 to arrive with the `Order_*`/`Tr_*` restructure. It arrives
  earlier and separately, because the receipt needs it and the restructure
  is still unscheduled.
