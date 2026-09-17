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

## Addendum, 2026-09-18: 장바구니 — 주문 하나에 메뉴 여럿

The ledger was built with `app_order_detail` from the start, and until
today **nothing could put more than one row in it**. `PlaceAppOrder` took
a single `menuId`; `HttpOmsGateway` sent `lines.get(0).menuName()` and
dropped the rest. A customer ordering two things got two orders, two
pickup numbers and two transaction numbers — one customer in the shop,
two in the system.

The interesting part was not adding a list. It was what the list forced:

**가격이 요청에서 사라졌습니다.** There is no price field in
`PlaceAppOrder.Item` at all — only `menuId` and `quantity`. A price the
browser sends is a price the customer can edit, and the customer had a
whole menu board to choose it from. The server re-reads it from that
store's board for that 판매일자, which also settles the other question:
prices change while someone is choosing, and the right one is the price
at the moment the order is accepted.

**결품이면 어느 줄인지 말합니다.** With five lines in a cart, "주문할 수
없습니다" leaves the customer removing items one at a time to find out
which. And one blocked line refuses the **whole** order — half a cart
arriving at the shop means the customer receives food they did not order.

**같은 메뉴 두 줄은 합치지 않습니다.** They will differ one day (하나는
맵게), and a merge cannot be undone afterwards.

Order gained `OrderLine` in the same change, and `menuName`/`amount` there
changed meaning rather than disappearing: they are now the order's *name*
("짜장면 외 1건") and the *sum*, both derived from the lines. Nothing was
deleted because POS/KDS/DID/BO all draw those fields and pre-2026-09-18
orders have no lines at all.

**옛 주문에 줄을 지어 넣지 않았습니다.** Backfilling one line each would
make "an order that had lines" and "an order that was one line"
indistinguishable forever. Screens fall back to the old field when the
list is empty.

## Addendum, 2026-09-18: 썬앤문페이 — 가상이지만 현금처럼

`app_order_payment` 는 표만 있고 행이 0이었습니다. 플랫폼이 돈을 못 받는
상태였고, 장바구니가 붙은 뒤로는 그게 가장 큰 구멍이었습니다.

Sun&Moon: *"가상의 sunmoon pay라고 해서 무조건 sunmoon pay로 결제 되는
것으로 진행해주세요"* — 그리고 곧바로 중요한 단서를 붙였습니다:
*"가상의 결제라고 해도 **현금과 같은 개념**으로... 고객에게 돌아가는
구조에요."*

그 한 줄이 설계를 바꿨습니다. 처음에 저는 "가상이라 돌려줄 곳이 없다"고
보고 `REFUND_REQUIRED` 라는 **표시만** 남기려 했습니다. 틀렸습니다 —
받을 수 있으면 돌려줄 수 있어야 하고, 돌려주는 길이 없으면 매장이 못
받은 주문의 돈이 어디에도 없는 채로 남습니다.

### 순서가 구현보다 중요합니다

```
주문 저장 → 결제 승인 → OMS 전달
                ↓ 거절
            402 (매장에 안 갑니다)
```

무조건 승인되므로 오늘은 이 순서에 아무 차이가 없습니다. 그런데
**진짜 PG가 붙는 날 이 순서가 이미 맞아 있어야** 합니다: 매장이 만들기
시작한 뒤에 결제가 실패하면 음식은 이미 나가 있습니다. 결제는 "나중에
끼워 넣기"가 제일 비싼 자리라, 쓰이지 않는 거절 경로를 지금 만들어
두는 것이 값을 합니다.

### 돌려주는 것도 한 벌입니다

두 경우에 돌려줍니다 — OMS가 거절/못 닿았을 때(돈은 받았는데 주문이
매장에 못 감), 손님이 취소했을 때(§5.2).

- **두 번 눌러도 한 번만.** 저장소가 `APPROVED` 인 것만 찾아 주므로
  두 번째는 돌려줄 것이 없습니다. 가상이라도 두 번 돌려주면 장부가
  안 맞습니다.
- **환불이 실패하면 결제는 `APPROVED` 로 남습니다.** 그게 맞습니다:
  돈이 아직 우리 쪽에 있다는 뜻이고, 갚을 것이 장부에 남아야 합니다.
  "환불했다"고 적고 실패하면 갚을 것이 조용히 사라집니다.
- **환불번호는 승인번호와 다른 값**입니다(`SMPR-`). 같으면 정산에서
  "받은 것"과 "돌려준 것"이 한 줄로 보이고, 그날 금액이 안 맞는데
  이유를 못 찾습니다.

### enum 과 기준정보는 다른 목록입니다

`PaymentMethod` 는 **이 서버가 다룰 줄 아는 방식**이고, 공통코드
`PAY_METHOD`(adr/0014)는 **이 브랜드가 받는 수단**입니다. 카드사가
늘어난다고 코드를 배포하면 안 됩니다.

## Open

- 외부 채널 주문번호 (`EXTERNAL_ORDER_NO`) — the map table gains a column
  when a channel exists.
- **결제수단 목록이 기준정보에 없습니다.** 공통 CRUD(adr/0014)로 붙일
  자리입니다.
- **승인번호가 메모리 카운터입니다.** 재시작하면 1부터이고 서버가 둘이면
  겹칩니다. 진짜 PG가 붙으면 번호는 PG가 주므로 그날 사라지는 값입니다.
- **한 주문의 메뉴가 서로 다른 주방으로 가는 경우** (중식과 일식을 같이
  파는 매장). 지금은 첫 줄의 주방을 씁니다. 주문 하나에 주방이 하나라는
  전제가 Order 의 `kdsDeviceId` 에도 있어서, 갈라야 하는 날 **주문을
  쪼갤지 줄마다 주방을 달지**부터 정해야 합니다.
- **결제가 없습니다.** `app_order_payment` 는 표만 있고 행이 0입니다.
  장바구니가 붙은 지금, 다음으로 큰 구멍입니다.
