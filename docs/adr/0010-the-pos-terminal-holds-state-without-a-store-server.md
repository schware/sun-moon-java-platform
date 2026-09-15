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
