# ADR-0010: POS가 상태를 갖는다 — 점서버 없이

- **Status**: Accepted
- **Date**: 2026-09-16
- **Deciders**: Project owner

## Context

ADR-0009 put transaction numbering on the POS terminal. A terminal that
mints its own sequence has to remember where it got to, and today's POS
cannot: it is a static React bundle served by nginx, with no process and
no storage worth trusting. `sessionStorage` is gone when the tab closes —
which would restart the sequence at 1 and collide. `localStorage`
survives, but is wiped whenever browser data is cleared.

So the owner raised the next step:

> 지금 POS에 DB 간단한 파일 DB나 postgresql 를 설치 할 계획이에요.

and then, decisively:

> 사실 점서버는 만들 수 없어요. 요즘 추세가 점서버 없는 방향성이에요.

That second sentence settles the first. Postgres on a POS is not really
"a database on the terminal" — it is a store server, because the only
reason to run Postgres instead of a file is to let several terminals
share it.

## Decision

### 점서버는 만들지 않는다

A store server puts **one single point of failure in every shop**, and
multiplies installation, replacement and upgrade cost by the number of
shops. The platform stays 클라우드 + 단말 로컬 저장.

### 파일 DB — SQLite

With no store server, the choice collapses to one: a per-terminal file
database. Each terminal owns its own, and a backup is a file copy.

### 진실의 순서가 바뀐다 — 이것이 실제 결정이다

The standing rule has been "**OMS is where facts settle**; terminals hold
no authoritative state". Local transactions change that:

```
지금    POS는 OMS에 요청 → OMS가 사실을 만듦
앞으로  POS가 거래를 만듦(채번 포함) → OMS로 올라감
```

The new contract:

- **POS의 로컬 DB는 그 단말 거래의 원본이다.** It is authoritative.
- **OMS는 올라온 거래가 모이는 곳이다.** It does not edit what it
  receives.
- 어긋나면 **POS가 원본**이다.

Writing this down now is the point. Left implicit, the first discrepancy
becomes an argument about which side is right.

### 주문 흐름과 거래 전송은 다른 경로다

The owner intends the upload to be a batch:

> 그 DB에서 주기적으로 POS-SERVER 거래를 올리는 작업을 BATCH로 할거에요

These must not be conflated:

| | |
|---|---|
| **실시간** | 수락 / 거절 / 조리완료 — KDS와 DID가 이것으로 움직인다 |
| **배치** | 거래(TR) 원장 — 매출 데이터 |

If acceptance only travelled with the batch, the kitchen would not see a
ticket until the next run. One action emits both: the ticket immediately,
the ledger later. That is how real POS behaves.

### POS-SERVER는 받아서 넘기기만 한다

The batch goes to POS-SERVER, like everything else a terminal touches,
and POS-SERVER forwards it to OMS **without storing it**. Buffering there
would create a third place a transaction lives. It is unnecessary
anyway: the terminal already has the queue, so a failed upload is simply
retried on the next run. POS-SERVER keeps owning connections and nothing
else.

### 마감이 전송을 검증하는 지점이 된다

Today 마감 just closes the business day. Once uploads are asynchronous it
must also answer **"내 거래가 전부 올라갔는가"**. A shop that closes with
unsent transactions has takings that do not add up, and finds out the
next morning.

So: force an upload on 마감, and refuse — or loudly warn — if it fails.
This requires each transaction to record `전송여부 / 전송시각`, which is
also what lets a terminal say how many are outstanding.

### 미전송 건수는 화면에 보여야 한다

This platform has already lost time to a silent failure: `EVENTS_REDIS`
was unset for days, so real-time push quietly did nothing while every
screen kept working off its 15-second poll. A queue that fills up
invisibly is the same failure mode. **The count goes on the POS screen.**

### 단계

```
0  지금     무상태, 온라인 전제
1  큐       IndexedDB 미전송 큐 + 주기 재전송 + 로컬 채번
2  로컬 DB  SQLite — 거래 원본이 단말로, 오프라인 거래 완결
```

Stage 1 works with today's static bundle and already removes the worst
case — "수락을 눌렀는데 네트워크가 끊겨 사라짐". It is also the batch in
miniature: queue, then push periodically.

**Stage 2 does not have to mean an installed app.** A PWA with a service
worker keeps the current deployment exactly as it is — static files, tar
over ssh, a refresh is the update — where Electron or Tauri would
introduce an install-and-update story this project does not have. The
trade is storage durability: IndexedDB is adequate but can be cleared,
SQLite in a real app cannot.

## Consequences

- **오프라인의 범위가 좁다, 그리고 그것은 이 결정의 대가다.** Without a
  store server, a disconnected POS can keep taking money but **cannot get
  a ticket to the kitchen** — that path runs through the centre. Linking
  terminals directly on the shop LAN would rebuild a store server in
  miniature. The answer is not to design around the outage but to make it
  rare (network redundancy) and let POS alone carry those minutes.
- **로컬 마스터 캐시가 필수가 된다.** With nothing in the shop to lean
  on, a terminal must hold its own copy of menus, 결품 and tables to
  survive an outage. The `BO → Redis → POS-SERVER → 단말` announcement of
  ADR-0007 therefore updates a **local master**, not just a screen.
- **POS-SERVER의 연결 안정성이 더 중요해진다.** It is the only thing a
  terminal can lean on. The existing `TERMINAL_GRACE=PT3M` — which keeps
  a brief disconnection from being read as an unstaffed counter — matters
  more, not less.
- **KDS와 DID는 무상태로 남는다.** They display, and KDS reports 조리완료;
  neither mints a key. Nothing here applies to them yet.
- **The unique constraint from ADR-0009 is what makes retries safe.**
  Without it, every batch design would need its own deduplication.

---

## Addendum, 2026-09-24: 1단계가 실제로 들어갔습니다

위 §단계 표의 **1 — IndexedDB 큐 + 로컬 채번**이 `sun-moon-terminal-pos`
에 구현됐습니다. 2단계(SQLite)는 그대로 남아 있습니다.

### 고친 것은 기능이 아니라 조용한 사고였습니다

거래번호가 `localStorage` 에 있었습니다. 읽고-더하고-쓰는 세 걸음인데,
`localStorage` 에는 그 셋과 **거래 기록을 함께 묶을 방법이 없습니다**.
그래서 두 가지가 가능했습니다:

- 번호를 올린 직후 브라우저가 죽으면 → 번호만 오르고 거래가 안 남습니다.
  영수증 번호에 구멍이 납니다.
- 번호를 못 올린 채 거래가 나가면 → **같은 번호가 두 번** 올라가고,
  서버의 유일 제약(ADR-0009)에 부딪혀 **손님 앞에서 수락이 실패**합니다.

IndexedDB 의 트랜잭션은 object store 여럿을 한 번에 묶습니다. 이제
번호를 올리는 것과 거래를 적는 것이 한 트랜잭션입니다 — 둘 다 되거나
둘 다 안 됩니다.

### 네 가지 판단

**번호는 돌려주지 않습니다.** 수락이 실패하면 그 줄을 `VOID` 로
남기고 번호는 태웁니다. 되돌리면 다음 거래가 같은 번호를 쓰는데, 그
사이에 이 번호가 이미 올라갔을 수 있습니다. **영수증 번호의 구멍이
매출의 중복보다 낫습니다** — 구멍은 설명할 수 있고, 중복은 두 거래 중
어느 쪽이 진짜인지 아무도 모릅니다.

**모르는 것은 지우지 않습니다.** 번호만 끊고 화면이 죽으면 `MINTED` 로
남습니다. 그 주문이 실제로 그 번호로 수락됐는지는 서버만 아니까, 시작할
때 물어보고 닫습니다. **못 물어보면 그대로 둡니다** — 다음에 다시
시도합니다.

**옛 `localStorage` 채번을 한 번 옮겨옵니다.** 안 옮기면 오늘 번호가
1번부터 다시 시작해서, 이미 올라간 번호와 부딪힙니다. 배포가 사고가
되는 가장 쉬운 길입니다.

**마감이 전송을 검증합니다.** 위 "마감이 전송을 검증하는 지점이 된다"
그대로입니다. 다만 **막지는 않습니다** — 서버가 죽어 있는데 매장을 못
닫게 하면 그게 더 나쁩니다. 올려 보고, 남으면 몇 건인지 말하고,
그래도 닫겠냐고 묻습니다.

### 올리는 곳은 이미 있었습니다

`POST /sales` 가 배열을 받고 중복을 실패로 보지 않습니다 — 이 ADR을
예상해서 그렇게 만들어 둔 자리였고, 단말 쪽만 붙이면 됐습니다.

### 이 ADR에서 한 줄이 낡았습니다

> **POS-SERVER는 받아서 넘기기만 한다** … POS-SERVER는 연결을 쥐고
> 나머지는 아무것도 하지 않습니다.

**더 이상 사실이 아닙니다.** ADR-0013(2026-09-17)이 POS-Server를 기록
서버로 바꿨고, 매출 원본은 `tr_sale_log` 에 남아 분해됩니다. 그때
"세 번째 장소"를 걱정한 것은 **버퍼**였는데, 지금 있는 것은 버퍼가
아니라 **원본 보관소**입니다 — 단말이 올린 것을 그대로 두고, 분해가
실패해도 원본으로 다시 풀 수 있습니다. 걱정의 방향은 맞았고 결론만
바뀌었습니다.

---

## Addendum, 2026-09-27: 2단계는 PWA 가 아니라 Electron 입니다 — ADR-0015

위 §단계에서 "2단계가 꼭 설치형 앱일 필요는 없다, PWA 가 지금 배포 방식을
그대로 둔다" 고 적었습니다. **POS 는 Electron 으로 가기로 했습니다**
(ADR-0015). VAN 카드 단말·영수증 프린터·돈통을 브라우저가 붙잡을 수 없고,
IndexedDB 원장은 브라우저 데이터 삭제 한 번에 지워집니다.

"KDS 와 DID 는 무상태로 남는다" 도 조금 바뀝니다. **KDS SUB 와 DID 는
그대로**이고, 주방의 중심이 된 **KDS-MAIN 이 라벨 출력 기록**을 갖습니다.

**"점서버 없음" 은 그대로입니다.** KDS-MAIN 은 KDS SUB 와 DID 를 서버를 통해
관리하고, 매장 LAN 에서 직접 품지 않습니다.
