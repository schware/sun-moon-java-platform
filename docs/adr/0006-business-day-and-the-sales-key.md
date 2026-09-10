# ADR-0006: 영업일 lives in Order, and the sales key is (매출일자, 매장, 단말기번호, 거래번호)

- **Status**: Accepted
- **Date**: 2026-09-10
- **Deciders**: Project owner

## Context

The owner's 설계서 introduced a rule the system had no place for: **모든
매출은 일 단위로 관리된다**, keyed by
`(매출일자, 매장, 단말기번호, 거래번호)`, with 거래번호 minted when an
order is **accepted**.

Three things were missing before any of that could be built.

**There was no 매장.** `store-01` was a bare string repeated in every
service and a hardcoded list of ten in the order channel. Nothing owned
it, so nothing could join on it. BO owns 기준 정보 — 장비, 메뉴, 공통코드 —
so 매장 belongs there, and that part was not controversial.

**There was no business day.** Orders carried `placedAt`, a UTC instant.
A calendar date derived from it is wrong for exactly the case that
matters: a shop trading past midnight is still working its own evening,
and splitting that evening across two 매출일자 would make every daily
total wrong by whatever was sold after 00:00.

**The owner rejected a fixed cutoff.** Asked whether 매출일자 should be a
calendar date or a business date with a cutoff hour, the answer was
neither: *"개점 + 마감 버튼이 필요하다"* — the day is opened and closed by
a person, and BO shows the resulting state.

## Decision

**매장 기준 정보 in BO. 영업 상태 in Order.**

BO gets a `stores` table — id, name, phone, address, active. It does not
get 개점/마감.

Order gets `store_business_days` — one row per (store, 영업일자), open
from 개점 until 마감 — and every order is stamped with the 영업일자 that
was open when it arrived. An order for a store that has not 개점'd is
**refused with 409**, which is also what finally answers the older
question of how the order channel should show a shop as closed: it is a
business state, not a guess from whether a terminal's WebSocket happens
to be connected.

**Why Order and not BO.** This state is read on the write path. Every
order stamps a 매출일자 and every acceptance will mint a 거래번호 against
it. Putting a cross-service call to BO inside the sale would mean a shop
stops selling when the admin console is redeployed, and it would put a
network hop in the hottest path in the system. Master data changes
rarely and is read by humans; business-day state changes constantly and
is read by machines. They are different kinds of data and they sit in
different services.

**No scheduled job closes anything.** There is no timer that closes a
store at midnight. A shop still serving at 00:30 has not finished its
day. The day rolls only when a person acts:

- 마감 closes it.
- 개점 on a day that has already gone stale closes the stale one first
  and opens today, reporting `rolled: true` and which date it closed.
  This is the 설계서's *"개점이 되어 있는 상태에서 날짜가 지나면 마감"*,
  carried out by the person opening up rather than by a timer nobody was
  watching.
- Until then `GET /business-days/{storeId}` reports `needsClosing: true`,
  which is what the POS shows a prompt from.

**The store's calendar, not the server's.** 영업일자 is computed in
`Asia/Seoul`, not in the host's UTC. A shop opening at 09:00 KST on the
10th must not be recorded as the 9th because the server disagrees about
what day it is.

**One open day per store, enforced by the database.** A partial unique
index (`WHERE closed_at IS NULL`) rather than a read-then-write in the
service: two terminals pressing 개점 at the same moment is precisely the
race a check-then-insert would let through.

## Consequences

- **Orders now fail for a closed store.** This is a real behaviour
  change: before this, any store id was orderable at any time. The ten
  demo stores have to be 개점'd for the channel to work, which is the
  point — but it means a deploy that forgets to open them looks broken.
- **`business_date` is a real column on `orders`**, not part of the JSONB
  blob, for the same reason `store_id` became one in V4: it is queried
  on. `매출은 일 단위로 관리된다` is a statement about how this data will
  be read.
- **거래번호 is not built yet.** Acceptance still only records
  `acceptedBy`. The 4-part key is settled and 매출일자 and 매장 exist;
  단말기번호 is `acceptedBy`, and 거래번호 arrives with the
  `Order_header/Detail/Pay/Etc` + `Tr_*` restructure, which will reverse
  the JSONB shape ADR-0001 chose for this service and deserves its own
  ADR.
- **POS reaches this through the Device Server**, like everything else a
  terminal does — the terminal never learns where Order lives.
- **BO will read business-day state back for display.** That is a
  cross-service read on a screen a human is looking at, which is the
  acceptable direction; it is not wired yet.
