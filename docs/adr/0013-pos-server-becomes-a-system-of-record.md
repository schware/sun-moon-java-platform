# ADR-0013: POS-Server stops being a relay and becomes a system of record

- **Status**: Accepted
- **Date**: 2026-09-17
- **Deciders**: Project owner

## Context

Until today the Device Server (the Netty runtime on 8087) held terminal
WebSockets and forwarded. It pushed Order's events to the right screens,
proxied `/orders` and `/menus`, and kept a registry of who was connected.
It owned **nothing**. Restart it and no fact was lost, because it held no
facts.

`Business_Requirements_Document.md` §3 says otherwise. POS-Server is
given four jobs, and all four are about holding something:

> POS로부터 수신한 TR 매출 원본을 `TR_SALE_LOG`에 BLOB 형태로 저장한다. (§3.1)

> POS의 개점 또는 마감 이벤트가 발생할 때 IRT 정보를 수신한다. … 현재
> 상태는 판매일자와 POS 식별값을 기준으로 `MERGE` 처리한다. (§3.4)

> WebSocket 연결 상태만으로 정상 영업 여부를 판단하지 않는다. (§3.5)

> 장비별 수신, 적용, 실패 결과와 적용 버전을 관리한다. (§3.6)

The last two are the interesting ones, because they are corrections
rather than additions: we *had* a notion of "is this store staffed" built
entirely from whether a socket was held, and we had no notion at all of
what a device was running.

## Decision

POS-Server gets its own database (`pos_server`) and four areas of record.

### 1. 매출 — receiving and decomposing are separate acts (§3.1–3.2)

`tr_sale_log` holds the transaction **as bytes**, undecoded, keyed by
판매일자 + 점포 + POS번호 + 거래번호 (the sales key of ADR-0009). A batch
decomposes it later into Header / Detail / Payment / ETC.

Three reasons the split is not an optimisation:

1. **Receiving must be fast.** The POS has a customer standing at the
   counter. Decomposition getting slow must not make receipt slow.
2. **A failed decomposition must not lose the transaction.** The original
   is the only ground for reprocessing, and the only answer to "my sale
   never showed up."
3. **Rules change.** Holding only the decomposed result binds the store's
   sales to whatever the rules were that day, forever.

Decomposition refuses an unbalanced transaction rather than storing it.
Header 금액 ≠ Detail 합계 written through is a row nobody can later judge:
at month end the total is wrong and no single transaction is the culprit.
The comparison uses `compareTo`, not `equals` — `12000` and `12000.00`
are the same money, and `equals` would fail every transaction on the day
a POS starts sending decimals.

A duplicate is **not an error**. It is the normal outcome of a POS
retrying or a batch re-uploading, and it has to read as "already have
it" so the POS can safely delete its local copy. An error there makes the
POS resend forever.

### 2. 개점·마감 — the terminal's day, not the store's (§3.4)

This is a *second* 개점/마감, and it is deliberate. Order already has one,
keyed by store: **does this store take orders**. This one is keyed by
(매장, POS번호, 판매일자): **can this terminal ring up a sale**. A store
with three POS, one of which is closed, is still trading. Collapsing them
would make one terminal's 마감 stop the shop.

Current state (`pos_business_state`, MERGE) and history
(`pos_business_hist`, append) are separate tables because the readers are
separate: the operations screen asks "is it open" and wants one row;
settlement and audit ask "when did it open, close and reopen" and want
all of them.

Three rules, all of the form *do not believe what arrived*:

- **재개점 is decided by the server.** The POS only says opened/closed.
  A POS that rebooted, or had its local DB reinstalled, does not know it
  already opened today — it will say 개점, and the reopen disappears from
  the history. The server knows the previous state, so the server names
  the event.
- **An older event does not change current state.** A POS that was
  offline uploads its backlog, and the morning's 개점 can arrive after
  the evening's 마감. Without comparing `event_at`, a closed terminal
  reopens and nobody closes it again.
- **The same message twice is not an error** — same reasoning as a
  duplicate sale.

All three are still **written to the history**, including the ignored
ones. That a message was ignored is itself the answer to a question
someone will ask later ("why did this POS never go to 마감?"). A stream of
IGNORED_STALE rows is also the only visible sign that a terminal's
communications are backing up.

### 3. Live Check — five axes, kept apart (§3.5)

The document's sentence is the whole design:

> WebSocket 연결 상태만으로 정상 영업 여부를 판단하지 않는다.

Connection / Application / Business / Device / Data are answered
separately, because four of them can fail while the socket stays up, and
merging them hides exactly those four:

| POS with | looks like |
| --- | --- |
| socket up, program dead | 연결됨 |
| program up, never did 개점 | 연결됨 |
| everything up, printer dead | 연결됨 |
| everything up, sales not landing | 연결됨 |

Each axis has a **different source**, and that is what keeps them honest:

- **Connection** — this server's own socket registry. Asks nobody.
- **Business** — §3.4's state, read directly. Not reported separately;
  two places holding the same fact will disagree eventually.
- **Application / Device** — the POS's heartbeat.
- **Data** — the heartbeat **and** the count of sales this server failed
  to decompose. A POS cannot know its sale broke after it left; when that
  happens the POS's "정상" is not a lie, it is uninformed, so what the
  server saw wins.

A heartbeat older than `POS_HEARTBEAT_GRACE` becomes `UNKNOWN`, not the
last value reported. A terminal that said "정상" immediately before dying
would otherwise stay green forever. `UNKNOWN` is also deliberately ranked
*below* `DOWN`: calling not-yet-known a fault means every terminal that
has not been switched on yet raises an alarm each morning.

`POS_HEARTBEAT_GRACE` is separate from `TERMINAL_GRACE` because they are
different judgements — the latter is "the screen dropped but staff are
still there," the former is "it may have died right after saying it was
fine." One value for both would be wrong for one of them.

The response also carries `mismatches`: axes that do not agree with each
other. The first entry is the reason the screen exists — socket held,
nothing reported.

### 4. 마스터 IRT — versions and apply results, not content (§3.6)

`pos_master_version` (store × master → version) and
`pos_master_dispatch` (store × device type × device no × master →
applied version, status, attempts, error).

**The master's content is not here.** BO owns it and exposes it through
the view of ADR-0008; APP-SERVER reads the same view as a peer consumer,
which the document states explicitly. A second copy in this server would
be two truths waiting to diverge.

The load-bearing rule: **the applied version rises only when the device
says so.** Recording a send as an apply makes a device that never
received the file look current — it then drops off the retry list and
keeps trading on last month's prices. So `markSent` deliberately does not
touch `applied_version`, and a `FAILED` ack leaves the previous version
in place (the device did not roll back; it failed to move forward).

FULL vs DELTA follows from the same value: no applied version means the
device has nothing to apply a diff to (first install or recovery), so it
gets everything. A device *ahead* of the server — master rolled back, or
hardware moved from another store — also gets FULL, because there is no
diff that goes backwards.

The device key is (매장, 장비유형, 장비번호). Device number alone is not
unique across stores, and without the type a store's POS 01 and KIOSK 01
overwrite each other — the same bug TerminalId already had to fix for
sockets on 2026-09-13.

## Consequences

- POS-Server holds facts, so it now has a database to lose. `pos_server`
  is a separate database for the same reason as every other service's
  (ADR-0001): Flyway will not adopt a history it cannot resolve.
- The decomposition batch is scheduled in-memory by Quartz. A container
  restart re-arms it, and unprocessed originals stay in the table, so
  nothing is lost — but **decomposition stops while the container is
  down**. At this size that is acceptable; the fix, when it stops being
  acceptable, is Quartz's JDBC store.
- Live Check answers per POS, but the Data axis is currently a
  per-**store** signal (failed decompositions are counted by store and
  day). A store with several POS will show the same Data state on all of
  them. Narrowing it needs the failure count keyed by POS번호, which the
  sales log already has.
- `overall` is the worst of the five axes, so a closed shop at night
  reads as 중단 on every terminal. That is correct for the question being
  asked ("is it trading normally") but means the Grafana panel needs a
  business-hours filter before it is worth alerting on.
- `/pos/masters` returns *what and which version*, not the master itself.
  The device fetches the content separately. One round trip more, but the
  store's line does not carry every master on every check.

## Addendum, 2026-09-18: the rest of §3.5, and somewhere to look at it

Three items of §3.5 were listed as open and are now built, plus the BO
screens that were the other half of the same gap.

### System Date 동기화 — measured, not reported

The heartbeat carries `posTime`, the POS's own idea of the clock, and the
server computes the drift. The measuring has to happen server-side for a
reason that sounds circular but is not: a POS that could tell you it is 30
seconds slow would not be 30 seconds slow. The reference is the server; the
terminal only says what time it thinks it is.

The drift rides back on the heartbeat's response, so correction needs no
extra round trip — the call is already periodic. `GET /pos/time` exists
separately for the case that matters most: a POS that has just booted has no
state to report yet, and that is exactly when its clock is furthest off.

**We did not add a sixth axis.** A terminal with a wrong clock can be green
on all five — and its sales still land on the wrong 판매일자 (ADR-0006).
Drift surfaces as a mismatch, which is the slot for precisely that: a fact
the axes do not carry. Tolerance is `POS_CLOCK_TOLERANCE`, 30s by default —
a few seconds on a receipt is invisible, a minute near midnight moves money
to another day.

### 프로그램 배포 — the master rules, with two differences

Same load-bearing rule as §3.6: **the installed version rises only when the
device says so**. Two things differ:

1. **Versions are names, not numbers** (`1.4.2`), so nothing is compared for
   size. Current means *most recently released*, and a device holding
   anything else is a target. Rolling back therefore needs no special code —
   re-releasing 1.4.1 makes the devices on 1.4.2 targets, by the same rule.
2. **There is a middle state.** `DOWNLOADED` exists because failing to fetch
   and failing to install are different faults: the first is the line or the
   file URL, the second is the device. Collapsed into one, you find out which
   by driving to the shop.

A store-specific release beats a global one. Rolling one shop first is the
normal shape of a deployment, and a global release overriding it would make
the trial meaningless.

### 공지 및 긴급 메시지 — write first, then push

The document suggests WebSocket for urgent messages. Push alone is not
enough: a terminal that was off at that moment would never learn of it. So
the order is fixed — **store, then push**. A failed push still leaves the
message, and the device reads it on its next poll. Reversed, a message could
succeed at pushing and fail at storing, which is the one genuinely bad
outcome: seen by someone, known to no one.

Urgent messages do not go through Redis, unlike 결품 and order events. Those
originate in *other services* and have to cross a channel; this one arrives
at this server directly. Routing it out and back would mean losing, on the
day Redis is down, something this server could have done by itself.

Only urgent messages count acknowledgements. Ordinary notices are never
acknowledged, and a `0` next to one reads as a problem.

### BO finally has somewhere to look

Four screens — 개점 현황, Live Check, 배포 현황, 단말 공지 — behind
`/api/pos-ops/*`. **BO does not own any of it.** Every value is read from
POS-Server; only three writes start with a person in BO (bump a master
version, publish a release, post a notice). A cached copy in BO would be
faster and would eventually disagree with the source, and on that day nobody
could say which was right.

Two deliberate choices in those screens:

- **The plan endpoints are read-only from BO.** `/pos/masters` and
  `/pos/programs` take an `issue=true` that records a send; BO never passes
  it. Otherwise opening a monitoring screen would stamp a send time on a
  master nobody sent, and the retry counter would become a count of how many
  times a human opened a page.
- **`SENT` is not green.** The deploy screen colours it amber, because
  "sent" is not a healthy end state — it is *not yet known*. Colouring it
  like success would hide exactly the devices this screen exists to find.

Permissions split four ways rather than one. Bundled, anyone who can post a
notice could also change which file gets installed on shop hardware, and
those are not the same weight of action.

## Addendum, 2026-09-18: 이 상태들은 손님 쪽으로 가지 않는다

문서 §5.4 는 "매장 주문 가능 상태 / 결품 / POS 개점 / POS 마감 / 재개점 /
POS Live 가 변경되면 **APP-Server와 외부 채널에** 전달한다"고 적고 있고,
저는 그것을 다음 작업의 2순위로 올렸습니다. Sun&Moon이 정정했습니다:

> **"APP은 개점,마감 Live 할 필요 없어요. APP은 사용자잖아요."**

맞습니다. 제가 **POS-Server가 아는 것**과 **손님이 알아야 하는 것**을
섞었습니다.

- **APP-SERVER는 우리 플랫폼 안에 있어서 물어볼 수 있습니다.** QR 게이트와
  매장 선택 경로 둘 다 이미 주문 직전에 Order 에 영업 상태를 묻고
  `STORE_CLOSED` / `ENTRY_REFUSED` 로 막습니다. 밀어 주면 같은 사실이
  두 곳에 생기고, 그건 이 ADR이 Live Check 의 영업 축을 §3.4 에서 직접
  읽게 한 이유와 정확히 같은 이유로 나쁩니다.
- **개점·마감·Live 는 매장 운영의 사실입니다.** 손님 앱이 pos-01 의
  프린터 상태를 알 이유가 없습니다. 그리고 단말 단위 개점/마감은 손님이
  묻는 질문("여기서 지금 시킬 수 있나")과 **다른 축**입니다 — 이 ADR이
  Order 의 매장 영업일과 POS-Server 의 단말 개점을 굳이 갈라 둔 그
  구분이, 여기서 한 번 더 쓰입니다.

그러면 §5.4 의 진짜 대상은 **외부 채널**입니다. 배민·쿠팡이츠는 밖에
있어서 물어볼 수가 없고, 자기 앱에서 매장을 회색으로 만들려면 받아야
합니다. 그래서 이 일은 **외부 채널이 실제로 붙는 날**의 일이고, 그전에는
만들 대상이 없습니다.

일반화하면: **밖으로 밀지, 안에서 물을지는 "물어볼 수 있는가"로 정합니다.**
플랫폼 안이면 묻게 두고, 밖이면 밀어 줍니다.

## Open

- **ETC 매출 분해** — originals are received (`etc_sale_log`) but not
  decomposed; the document defers the detail table shape (§3.3).
- **Quartz still schedules in memory.** Decomposition and any future POS
  batch stop while the container is down. Unprocessed originals survive, so
  nothing is lost — but nothing moves either.
- **No thresholds anywhere.** Live Check, the retry lists and the forced-
  pickup count are all screens someone has to open. None of them tells you.
- **BO `devices` still keys on `device_id` alone**, although a terminal is
  identified by (매장, 번호, 종류) — the same shape this ADR just used for
  master dispatch. Fixing it (surrogate id + UNIQUE) is a prerequisite for
  per-store device auth.
