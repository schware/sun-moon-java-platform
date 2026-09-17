# ADR-0012: APP-SERVER가 손님 주문의 원장을 갖는다

- **Status**: Accepted
- **Date**: 2026-09-17
- **Deciders**: Project owner

## Context

Sun&Moon wrote the業務 definition for the five systems and put it in a
repo of its own — `schware/Sun-Moon-Document`, `Business_Requirements_
Document.md`. It is the authority on what each server is for; these ADRs
stay the record of *why* a decision was made.

Three of its claims contradicted what we had built:

> APP-Server DB는 POS의 TR 매출 구조와 유사하게 주문 Header, Detail,
> Payment를 분리 관리한다. (§4.4.3)

> APP-Server는 APP 및 QR 주문을 식별할 수 있는 고유 주문번호를 생성하거나
> 관리한다. (§4.4.1)

> OMS 연계 식별자 → OMS_ORDER_NO: OMS-Server에서 생성하거나 반환한 주문번호

Until now `channel-order` validated the store, the menu and 결품, then
posted straight to OMS. APP-SERVER only assembled the menu board and
guarded the QR gate. Nobody held a record of *what the customer asked
for* — only of *what the store was given to make*.

## Decision

### APP-SERVER owns an order ledger, and OMS's order is not the same thing

`app_order_log` / `_header` / `_detail` / `_payment` / `_status_hist` /
`app_oms_order_map` / `app_order_seq`.

The two orders answer different questions. OMS's is **the work the store
is doing**; this one is **what the customer asked for**. The distinction
is not academic — an order OMS *rejected* still happened to the customer,
and it is exactly the kind you have to be able to find later. Under the
old shape it existed nowhere.

The customer's 주문내역 therefore reads this ledger, not OMS.

### The log is written before anything is validated

`app_order_log` takes the raw request first, decomposition second. If
validation refuses it or OMS rejects it, what the customer sent is still
there. That is the only basis for answering "my order didn't go through",
and §4.4.6's re-processing has nothing to re-process without it.

### `request_id` is the idempotency key, and the *screen* mints it

A unique constraint on `app_order_log.request_id` is what stops a double
tap or a network retry from becoming two orders. Checking "does it exist
already" in application code leaves a window between the check and the
write; the constraint does not.

It has to come from the browser. A server-generated id is new on every
retry and therefore blocks nothing — so `Checkout` makes one when it
opens and every retry inside it sends the same value.

### 업무 주문번호 is separate from the primary key

`APP_ORDER_NO` = 점포 + 판매일자 + 일련번호, e.g.
`store-01-20260917-0001`. The PK stays a plain bigserial.

This is the direction the document recommends (§4.4.1), and the reason is
concrete: a business number that embeds the store reads well to an
operator, but a store code change or a length problem must not reach the
key that everything else references.

**브랜드 is not in it yet.** The document puts it first, but BO has no
brand master. A placeholder segment would be printed on receipts and read
back by customers before it ever meant anything; when brands arrive the
segment joins numbers issued from that day, and the ones already out stay
as they are.

### 거절과 못 닿음은 반드시 갈라진다

`REJECTED` (OMS answered no) and `SEND_FAILED` (OMS never answered) are
different states, and 409 vs 503 to the customer. The first has a reason
worth showing; the second is a re-send candidate and might just work on
the next tap. Collapsing them loses both the customer's next action and
the operator's re-processing list.

The gateway's return type enforces it: `Answer` carries `reachable`
separately from `accepted`, so a caller cannot accidentally treat the two
as one.

### order_channel and order_type are different columns

**Where it came in** (APP / QR / EXTERNAL) and **how it goes out**
(IN_STORE / DELIVERY). A QR delivery order and an in-app dine-in order
are both possible, so one column cannot hold both. BO's APP 매출 조회
currently infers "QR order" from "has a table number" — that is the
destination, not the entry point, and it breaks the day a QR without a
table exists.

### channel-order becomes a pass-through

Validation moved to the side that owns the ledger. Had it stayed on the
screen, adding a second screen would mean two copies of the rules — and
two copies diverge. `channel-order` now forwards the request and passes
the answer back with its status code intact.

The browser still does not call APP-SERVER directly: APP-SERVER is
LAN-only and the customer screen talks only to what served it. That
indirection goes when the screen itself moves.

### Flyway, not schema.sql

`app_server` already held fdw foreign tables and views created by
`server/db/app-server-fdw.sql` (superuser work the application cannot
do). Flyway baselines at 0 and manages only what it created; the fdw
objects stay that script's, and re-running it does not touch the ledger.

Transaction data grows columns, and a `schema.sql` that re-runs on every
boot cannot say what changed when. Order already uses Flyway for the same
reason.

### Payment exists with no rows

There is no payment yet. The table is created anyway because altering an
empty table is free, while *not* having it means bolting payment columns
onto the header the day a PG is chosen. Its columns are provisional — the
shape of an approval reference is the payment provider's to decide.

### Detail is one row per order, today

There is no cart. Splitting it out regardless means the header does not
have to be torn open when one arrives.

## Consequences

- APP-SERVER now writes, not just reads. `bo_reader` still governs the
  BO side; the ledger is this service's own.
- 수령번호 lives in `app_oms_order_map` rather than the header — OMS
  mints it, APP copies it back so the customer screen can show it without
  the browser reaching into a LAN-only service.
- `channel-order` no longer imports `Catalog` for order placement. It
  still serves the menu list from its own fixed catalog; that is the next
  thing to move.
- The customer-visible response keeps `menuName`/`amount` alongside the
  new shape, so the screen did not have to change in the same deploy.

## Addendum, 2026-09-17: 조회·취소 and the channel filter shipped

Both open items above are done, and one of them turned into a decision
worth recording.

**Tracking shows two axes side by side.** `GET /orders/{id}` answers with
the ledger's own status *and* OMS's, because they answer different
questions and are routinely different: `ledger=SENT, omsStatus=PLACED`
means we handed it over and the store has not accepted yet. Collapsing
them into one word would have to pick which of the two to lie about.

**Cancellation is OMS's decision, not APP-SERVER's.** The obvious
shortcut — refuse locally when the ledger says the order is past a
certain point — is wrong, because acceptance and cancellation overlap in
time. In that window APP-SERVER's guess is a guess, and if it is wrong
the two systems believe different things about a real order in a real
kitchen. So APP-SERVER asks, and follows the answer: 수락된 주문 comes back
409 `CANCEL_REFUSED` with OMS's own sentence. The one case it decides
alone is an order that never reached OMS — there is no work in the store
to cancel, so it closes the ledger row locally.

**BO's APP 매출 조회 now filters on the recorded channel**, not on a guess.
It previously inferred QR from the presence of a table number, which is a
destination, not an origin. Live check afterwards: QR 2 / APP 0, and
rejected and send-failed orders became visible for the first time — they
had never appeared in a 매출 view at all.

## Open

- 외부 채널 주문번호 (`EXTERNAL_ORDER_NO`) — the map table gains a column
  when a channel exists.
