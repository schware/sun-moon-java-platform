# ADR-0011: 결품은 판매일자에 걸고, 수령번호는 두 조각이다

- **Status**: Accepted
- **Date**: 2026-09-17
- **Deciders**: Project owner

## Context

Two things the owner asked for, built in the same pass because they turn
out to share one question — *what is true today, at this store?*

> 결품은 BO에서 결품을 하면 그것을 POS가 가져가는 것… TCP로 SOCKET…
> WEBSOCKET EVENT -> POS SOCKET

> KDS에서 QR에 대한 테이블 용도는 고객이 DID를 보고 가지러 왔을때
> 증명할 수 있는 번호를 서로 정의 하는 것이죠 / 테이블 번호 + SEQ 도 있고
> 테이블 번호 + 임의의 번호 (4자리)

> 매장 + 테이블 번호 + SEQ, 매장 + 테이블 번호 + 임의 번호 이렇게 할것이에요

## Decision

### 결품의 키에 판매일자가 들어간다

`menu_sold_out (store_id, menu_code, business_date)`.

The alternative was a boolean on the menu, cleared by somebody at 마감.
That version has a failure mode with no recovery: a 결품 nobody cleared
is still on the customer's screen the next morning, and the only way to
find out is a customer asking why you don't sell it any more.

Putting 판매일자 in the key removes the clearing step entirely. 개점 rolls
the day; yesterday's rows simply stop matching. There is no batch at
midnight, no step in the 마감 checklist, and therefore no step to forget.

BO does not know 영업일자 — that is Order's, per ADR-0006. So BO *takes*
the date and stores it; the reader brings its own. Only the BO screen's
default is computed, as Asia/Seoul today.

**A row means sold out.** No `sold_out = false` rows: keeping them makes
"nobody decided" and "we sell this" the same shape, and nothing uses the
distinction.

### 결품인 메뉴는 목록에서 빼지 않는다

Every layer shows it and refuses it, rather than hiding it. Hiding is
cheaper to build and worse to live with: the customer concludes the shop
never sold it, and the shop gets "왜 메뉴가 없어졌냐" instead of
"오늘 안 파나요". The same reasoning already governs an unpriced menu
(ADR-0008's LEFT JOIN).

### 알림은 바뀌었을 때만, 내용 없이

BO publishes `menu-events` to Redis; the Device Server pushes it to POS
and KDS at that store; the terminal re-reads `/menus`.

Two constraints fell out of building it:

- **Only on a real change.** `ON CONFLICT DO NOTHING` returns whether the
  row appeared; the event is published only then. Without this, an
  operator tapping the toggle twice makes every terminal in the shop
  re-read its menu master — the kitchen screen starts flickering.
- **No payload.** The frame says *something changed*, not *what*. A
  terminal that missed a frame still converges, because it re-reads on
  connect and on a 30s poll. Deltas would make a missed frame permanent.

### 수령번호는 두 조각이고, 이름이 다르다

The owner named two shapes (테이블번호+SEQ, 테이블번호+임의번호). They are
not alternatives — they do different jobs, so we built both:

| | 부르는 번호 `pickupNo` | 증명하는 번호 `pickupPin` |
|---|---|---|
| 모양 | 자리 + 일련번호 (`7-12`) | 4자리 난수 (`0042`) |
| 어디에 | DID에 크게, POS·KDS 티켓에 | 손님 화면에, KDS 확인 창에 |
| 하는 일 | 손님을 부른다 | 옆 사람이 먼저 집어가는 것을 막는다 |

`pickupNo` carries the seat because the kitchen and counter already talk
that way ("7번 나왔어요"). A bare serial would split the screen's language
from the staff's.

**층은 2층부터 붙는다** (`2-7-12`). BO's table uniqueness is
(매장, 층, 번호); a 수령번호 of 매장+번호 alone gives 1층 7번 and 2층 7번
the same number, and two customers arrive holding it. Dropping floor 1
keeps the common case short — most shops are one floor — and lengthens
only the case that actually collides. Same rule the terminals' existing
`tableLabel` uses.

`pickupPin` is four digits because it guards against *mix-ups*, not
guessing. A longer secret would be worse: the customer reads it aloud to
staff.

Both are minted **when the order is placed**, not when cooking finishes.
The customer has to see it on the confirmation screen, and it has to be
on the receipt; deciding it later leaves nothing to show in between.

### 일련번호는 UPSERT ... RETURNING 으로 올린다

`count(*) + 1` gives two simultaneous customers the same number. One
statement that increments and reads closes that window. Per
(store, business_date), so numbering restarts at 1 every day — the shop
never calls "312번", and yesterday's numbers never mix into today's.

### 수령 확인은 상태 전이와 한 덩어리다

`POST /orders/{id}/pickup`, not the generic status endpoint. The check
and the move must be in one method, or some future caller skips the
check. `PRODUCED → COMPLETED` was opened for this: a dine-in order has no
delivery leg, and routing it through `DELIVERING` would be a lie.

Three answers, deliberately distinct:

- **403** — 번호가 맞지 않습니다 ("당신 것이 아닙니다")
- **409** — 아직 조리 중 / 이미 수령
- **200** — 넘어갔습니다

Staff say different things to a customer in each case, so the codes
differ too.

**An order from before 수령번호 existed is not refused.** There is no way
to check it, which is not the same as it being someone else's. Refusing
would leave those orders permanently uncloseable.

### 예상시간 = 기준 조리시간 × (대기열 + 1)

기준 조리시간 is BO's (`menus.cook_minutes`) — an operator's figure, not
a measured average. 대기열 is OMS's (`GET /orders/queue`, ACCEPTED only).

The `+ 1` is the customer's own order. Without it an empty queue gives
"0분", which reads as "instant".

`null` means *unknown* and is never drawn: BO has not set a cook time, or
OMS was unreachable, or the item is sold out so there is nothing to wait
for. Filling unknowns with 0 would be the one failure mode that misleads
rather than merely omits.

**대기열을 못 읽어도 메뉴판은 나온다.** Unlike 영업 상태 — which decides
whether an order can be taken at all, and therefore throws — the queue
only decorates. A menu board that disappears because a decoration is
unavailable costs more than a missing estimate.

## Consequences

- BO gains a Redis dependency (`BO_EVENTS_REDIS`). Off by default, so a
  machine without Redis still runs BO; **on the server it must be true**,
  or 결품 lands in the database and reaches terminals only on their next
  poll.
- `v_bo_menu_sold_out` joins the fdw views (ADR-0008). Re-running
  `app-server-fdw.sql` now drops and re-imports the foreign tables:
  `IMPORT FOREIGN SCHEMA` skips tables that already exist, so without the
  drop a re-run silently keeps the old shape and the new column is
  invisible.
- APP-SERVER's `/menus` now requires `storeId`. The menu *list* is still
  shop-independent, but 결품 and 대기열 are not.
- `Screen.SOLD_OUT` is separate from `Screen.MENU`: editing the menu
  master is rare and 결품 happens several times a shift, so a floor
  manager can be given one without the other. The operator matrix grew
  `SCREEN_ACTIONS` to express that 결품 has no 저장 — you hang it or you
  release it, which is CREATE and DELETE.

## Addendum, 2026-09-18: the number gets a marked way around it

Sun&Moon, from the shop floor: **"수령하기 부분은 강제 진행이라는 버튼이
필요할것같아요. 매장 입장에서 필수 입력이 많으면 불만이 가득해요."**

That is correct, and the reason is worth writing down rather than just the
change. Customers who cannot produce the number are a real population, not
an edge case — the phone screen went to sleep, a friend came to collect, the
slip went in the bin. A counter that physically cannot hand the food over in
those cases stops with a queue in front of it.

### What we did not do: make the check optional

The obvious fix — drop the PIN field, or accept a blank one — is worse than
no check at all, and this is the load-bearing judgement of the addendum.

A required field that gets in the way does not stay required; it stays
*recorded*. Staff find the way around it, and the ways are always the same:
a fixed number everyone on the shift knows, or reading the number off the
screen in front of them without asking the customer. The system then holds a
log saying "번호 확인함" for handovers where nobody checked anything. That is
not a weaker guarantee than having no check — it is a **false record**, and a
false record is worse than an absent one, because the next person reads it
and believes it.

### What we did: leave the door, and mark it

- The default path is unchanged: type the number, and a wrong one is still
  403.
- 강제 진행 is a second button, on its own row, in its own colour — amber,
  not the green of 내주기 and not the red of danger. It is not an accident
  and the shop should not feel guilty pressing it; it is simply the lesser
  path. It is **deliberately not adjacent** to 내주기: side by side, the
  nearer button wins during a rush, and within a day the number is
  decoration.
- Orders carry `pickup_override`, and BO's 매출 조회 shows a "번호 확인 없음"
  badge on exactly those rows. **Recording it with nowhere to look is the
  same as not recording it.** Normal handovers stay quiet — a green badge on
  every row would bury the amber one.

That count is now a signal with two readings, and both are things we want to
know: customers genuinely cannot produce the number (fix how it is shown), or
the shop has stopped asking (the number is already decoration).

### And a gap it exposed

`handedOver(deviceId)` took a device id **and threw it away**. Nothing
recorded who handed an order over, or when. A dispute about a collected order
had nothing to go back to. `pickup_by` / `pickup_at` land in the same change,
which is how the override could be recorded at all.

## Addendum, 2026-09-18: DID flows, and KDS can call again

Second correction from the same conversation: **"DID는 흐르는 것으로 해주시는
것이 더 좋아보여요. 대신 KDS에서 재 호출 기능만 있었으면해요."**

The board had briefly been changed to hold a number until it was collected —
reasonable, since 수령 확인 now moves PRODUCED → COMPLETED and the number
does eventually leave. But a board that holds everything becomes a *list*,
and in a list the number that just came out is buried among the ones nobody
picked up. A display customers glance at from across a room has to flow.

So `called_at`: set when cooking finishes, and pushed forward by 재호출. The
DID draws what was called in the last ~90 seconds and lets the rest slide off.

Three things fell out of that shape:

- **Calling is not a state.** An order called five times is still PRODUCED.
  Modelling it as a status would have put a self-transition in the state
  machine, which is not a transition.
- **The screen does not remember.** The earlier implementation kept a
  `shownOrders` set in `sessionStorage`, which meant a reloaded board
  replayed the day and two boards side by side disagreed. Asking the server
  *when it was last called* removes both problems and the storage with them.
- **The button is on the KDS, not the DID.** Whether a customer has turned
  up is known in the kitchen, not by the screen. The display never decides
  to re-call on its own.

The countdown bar came back for a reason that is not decoration: a number
that vanishes with no warning reads as *gone* and sends the customer to the
counter. One that visibly drains reads as *passed*, and the ask becomes "다시
불러 주세요".

## Open

- 수령번호 as a scannable QR (the owner's "QR을 찍으면 고객에게 상품이
  나오도록") — the number proves it today; automation reads it later.
- 결품 by quantity rather than a flag ("남은 3개") — the flag is what was
  asked for, and quantity needs stock it does not have yet.
- **강제 진행 건수에 임계가 없습니다.** BO shows them per order; nobody is
  told when the rate climbs. That is the alarm this record was written to
  make possible, and it is not built yet.
