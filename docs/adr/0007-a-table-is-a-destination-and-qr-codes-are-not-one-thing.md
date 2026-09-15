# ADR-0007: 테이블은 배송지이고, QR은 한 종류가 아니다

- **Status**: Accepted
- **Date**: 2026-09-16
- **Deciders**: Project owner

## Context

The owner proposed table QR codes: a customer scans the code on their
table and gets the order channel's menu screen, with the store already
baked into the code.

The obvious reading is "the QR saves a tap by skipping the store picker."
That is true and it is the least interesting part. What the proposal
actually introduces is **테이블**, a concept this platform has never had,
and the shape given to it decides several other things.

I first proposed a **table session** — open on arrival, accumulate
orders, close on payment — because that is what restaurant POS usually
does, and because the 설계서's `Tr_header/Detail` structure would fit it.
The owner rejected that framing:

> 테이블 개념이라고 해서 꼭 테이블 넣을 필요는 없어요. 그 메뉴를 테이블에
> 가져다 주세요 라는 방향성은 좋은 것 같아요. 나중에 로봇을 이용해서
> 옮기는 효과도 있고, 결제 부분을 분리 할때 장점이 있으니까요.

Separately, on QR codes generally:

> QR을 QR이야 하나로 접근 하는 식의 구조는 안 좋은 것 같아요.

## Decision

### 테이블은 배송지다 — 신원도, 세션도, 계산서도 아니다

A table is **where an order goes**, the same kind of fact as a delivery
address or a pickup counter. It is not who the customer is and it is not
a bill.

|  | 테이블 = 세션 | **테이블 = 배송지** |
|---|---|---|
| 결제 | 테이블에 묶임 | **주문에 묶임 — 테이블과 무관** |
| 주문 하나 | 세션의 일부 | 그 자체로 완결 |
| 로봇 | 할 일 없음 | **목적지를 받음** |
| 포장·배달 | 세션 없는 예외 | 같은 자리, 도착지만 다름 |

**This is what lets payment be separated.** A table that is not a bill
puts no constraint on when or how payment happens — per order, bundled,
prepaid, postpaid — none of it touches the table model. Under a session
model, "close the session to settle" welds the two together.

It also stops 배달 and 포장 from being special cases. All three answer
one question: **어디로 가져가나** — 3번 테이블, 픽업대, or 고객 주소.

### BO owns the table master, including its position

테이블 is 기준 정보, like 매장 and 메뉴 and 장비, so it lives in BO. BO
also generates the QR image, because the QR is printed once and stuck to
a table — registration and printing are one act.

The master carries **position**, not just a number, because a robot that
carries food to a table needs somewhere to go. Three things are settled
now to avoid redrawing the map later:

- **층 from the start.** Adding a floor afterwards means re-placing every
  table. It also meets the earlier, deliberately unbuilt idea of
  splitting DID by floor.
- **Coordinates stay an abstract grid**, with room for a per-store
  "1칸 = N cm" scale. Pretending the numbers are metres today would be a
  lie; making them unconvertible would be a trap.
- **테이블 번호 ≠ 위치.** The number is what a person calls the table;
  the position is where a machine goes. Keeping them separate lets a
  table move without being renumbered.

### QR codes are not one thing

테이블 QR and 주문 QR have **opposite requirements**, so they get
separate code spaces and separate handling. Building one generic "QR"
abstraction would take the worst of both.

|  | 테이블 QR | 주문 QR |
|---|---|---|
| 가리키는 것 | **장소** — 매장의 한 자리 | **주문 한 건** |
| 수명 | 영구. 인쇄해서 붙임 | 일회성 |
| 바뀌면 | 스티커 재인쇄 (비쌈) | 그냥 다음 것 |
| 핵심 성질 | **안정성** | **추측 불가** |

**테이블 QR** resolves to (매장, 테이블) through a short opaque code
(`/t/{code}`) rather than plain query parameters. Not for security — for
indirection. A printed sticker cannot be edited; a database row can.

**주문 QR** identifies a placed order. It is **printed on the POS
receipt** and **shown on KDS**, and its purpose is 수령 확인: a customer
who saw the DID comes to collect and proves which order is theirs. See
ADR-0009 for the number it carries and how it relates to the sales key.

### 수령번호 = 매장 + 테이블번호 + (SEQ | 임의번호)

The store is part of it deliberately. A **multi-store DID** — the
food-court board built on 2026-09-13 — shows orders from several shops
at once, and 테이블+번호 alone would let two shops both produce `3-7241`.

Both a sequence and a random suffix are allowed, chosen per store. A
random suffix is unguessable, which is what a proof-of-collection wants;
a sequence is shorter and ordered. Collisions inside a business day are
resolved by re-drawing against the orders not yet collected — a set of
at most a few dozen.

### 손님 채널은 APP-SERVER 역할로 자란다

The owner named a fourth plane:

> APP-SERVER 는 POS-SERVER만큼의 작업을 하는 서버가 될거에요.
> APP-SERVER는 QR 관리, APP관리가 될거에요.

The symmetry is exact: POS-SERVER (the Device Server) is the one door
terminals come through, and APP-SERVER is the one door customers come
through. Both mediate to OMS and neither owns the order.

**What this is not.** It is not a new layer in the order path. The flow
the owner wrote —

```
APP → OMS → POS 수락 → KDS 표시
```

— does not change, and the customer's browser already never learns where
OMS lives (it talks only to `channel-order`, which proxies). An earlier
draft of this ADR claimed hiding OMS as a benefit of the new design; it
is not, it is the existing state.

What APP-SERVER actually adds is work `channel-order` cannot do today:
read the menu from BO's master instead of a hardcoded list, filter out
결품, attach 예상 시간, manage and resolve QR codes, and build the
catalog from 공통코드.

**APP-SERVER is Spring, and holds no connections.** The owner:

> 몇만명이 동시에 접속하는 app도 많아요. 그것을 socket으로 가는 방식은
> 아닌 것 같아요.

Terminals and customers are opposites: terminals are few, fixed and
always on, so POS-SERVER holds their sockets; customers are many,
transient, and go dark when a screen turns off, so holding their sockets
would cost a great deal and still fail at the moment it mattered.
Customer-side status is **polled**, and if real notification is ever
needed it is a push notification, not a WebSocket.

### 인증 handshake는 없다

There is no connection handshake for QR or app, because there is no
persistent connection to register an identity on. The checks at QR entry
are an ordinary stateless request:

```
QR 코드가 유효한가        → 잘못된 QR입니다
그 테이블이 살아있는가     → 사용중지
매장이 존재하는가
매장이 영업 중인가         → 지금은 주문을 받지 않습니다
```

All four happen **before the menu is shown**. Today the equivalent check
fires as a 409 when the order is placed — after the customer has already
chosen. Moving it to entry is a straight improvement.

## Consequences

- **A 배송지 abstraction is deliberately not built.** Only one case
  exists. An abstraction drawn from a single case is usually wrong;
  it gets extracted when 포장 and 배달 make it two. This is the owner's
  own no-premature-unification principle, applied to the destination as
  well as to QR codes.
- **DID must show the 수령번호**, not the order id, or a customer has
  nothing to compare against.
- **결품 is master data in BO that has to reach terminals.** The
  existing `OMS → Redis → Device Server → 단말` pipe carries it: BO
  announces *that the master changed* — a notification, not a command, so
  the control-plane rule holds. A new TCP protocol on the idle 9011
  socket was considered and rejected; that port currently runs an echo
  handler and would need a protocol invented for it. 예상 시간 is
  기준 조리시간 (BO) × 대기열 (OMS knows it), and neither is built yet.
- **No domain name yet.** A printed QR should point at a stable address,
  and a home server's public IP is not one. The owner deferred this:
  while the codes are printed on paper for a demo, a changed IP costs a
  reprint. It becomes blocking before real stickers, and before payment,
  which needs TLS anyway.
- **Open: whether `channel-order` grows into APP-SERVER or a new service
  is created.** Growing it keeps the port, container and deploy story
  unchanged and its README's "no database" line simply stops being true.
