# ADR-0008: 실행면은 BO 마스터를 뷰로 읽는다

- **Status**: Accepted
- **Date**: 2026-09-16
- **Deciders**: Project owner

## Context

Table QR codes (ADR-0007) need something this platform has never had: a
service on the execution plane reading 기준 정보 that BO owns, **at
runtime, on the order path**. OMS has to reject an order whose table it
does not recognise, and APP-SERVER has to build a menu from BO's master
instead of the hardcoded list `channel-order` carries today.

This is not a new problem, only the first time it has to be solved. The
Device Server has had the same gap since it was built: it checks only
that a device id is *well-formed* (`AcceptKnownFormatDirectory`) because
it has no way to ask BO whether that device exists. BO's 단말 현황 screen
(2026-09-15) made the gap visible — an unregistered terminal now shows up
as `registered: false` — but nothing refuses it.

On 2026-09-10, while deciding whether to fold the Device Server into BO,
the conclusion was already that this is *data access, not a process
boundary*, and that the fix was read-only access to `bo_service`. Tables
are the first case that forces it.

The owner set the shape:

> BO와 OMS는 각각 마스터를 가지고 있을 거에요.
> BO 등록/수정/삭제 가능하다. OMS 은 그 데이터를 V 형태나 SELECT 를 해서
> 주문이 들어왔을때 정보가 없다면 거절 한다. OMS는 따로 등록/수정/삭제가
> 없고 조회 만한다.

## Decision

**BO writes. The execution plane reads, through a view, and only reads.**

### postgres_fdw, not a second datasource

`bo_service` and `order_service` are **separate databases** on the same
Postgres instance, and Postgres cannot query across databases the way
MySQL can. Two options were weighed:

|  |  | OMS 코드에서는 |
|---|---|---|
| 가 | 두 번째 DataSource (bo_service 읽기 전용) | 별도 커넥션으로 SELECT |
| **나** | **postgres_fdw + 로컬 VIEW** | **로컬 뷰를 SELECT** — BO의 존재를 모름 |

The owner chose **나**. The consuming service's code contains no notion
of BO at all: it selects from a local view, and where that view gets its
rows is a database concern.

Both need one `sudo -u postgres` — the extension in one case, a read-only
role in the other. `sunmoon` has neither superuser nor createrole.

### This removes the availability coupling, rather than adding one

An earlier draft worried that OMS reading BO would stop orders whenever
BO was redeployed — and BO's process goes down on every deploy. Reading
through the **database** rather than the service dissolves that: BO's
container can be down and its data is still there. The only shared fate
left is Postgres itself, which everything already depends on.

### 조회 전용은 권한으로 강제한다

"OMS는 등록/수정/삭제가 없다" is a rule that a convention will eventually
break. The mapped role is read-only, so the database refuses the write
rather than a code review catching it.

### 같은 패턴을 세 곳이 쓴다

- **OMS** — 테이블 마스터를 읽어, 모르는 테이블의 주문을 거절
- **APP-SERVER** — 메뉴·결품·공통코드를 읽어 카탈로그를 구성
- **Device Server** — 장비 목록을 읽어 handshake를 검증 *(아직)*

The third is the gap that has been open since the Device Server was
built. It is not in scope here, but it is the same shape and should be
closed the same way.

## Consequences

- **This is a boundary change.** Until now every cross-service read in
  this family went over HTTP and was visible in the calling service's
  code — BO reads Order through `UpstreamClients`, terminals reach Order
  through the Device Server. A service reading another service's database
  is a new kind of coupling here, chosen deliberately.
- **The dependency is invisible in the consuming repository.** Nothing in
  OMS's source says it depends on BO; the wiring is in the database. This
  must be recorded in `Debian-Setting`'s deploy docs, or a future rebuild
  of the server will produce a service that starts and then fails on its
  first table lookup.
- **BO's schema becomes an interface.** Renaming a column in
  `bo_service` now breaks a foreign table in another service. BO's
  `schema.sql` stops being purely BO's business.
- **`channel-order`'s hardcoded catalog goes away.** Its `Catalog.java`
  — ten stores and ten menus fixed in code — is replaced by a read
  through this path, which closes one of its own "Not built yet" items.
- **Master changes still need a notification.** A view keeps the data
  fresh on read, but a POS holding a local copy (ADR-0010) does not
  re-read on its own. That is what the `BO → Redis → Device Server → 단말`
  announcement in ADR-0007 is for. The two mechanisms are not
  alternatives: the view is how a server reads, the announcement is how a
  terminal learns it should.

---

## Addendum, 2026-09-24: 두 번째 독자 — Device Server가 장비 명단을 읽습니다

이 ADR의 방식이 APP-SERVER 말고 **Device Server**에도 쓰였습니다.
`pos_server` 에 `v_bo_devices` 뷰를 걸고 BO의 장비 명단을 읽습니다.

### 무엇이 막혀 있었나

Device Server는 단말 id의 **모양만** 보고 있었습니다
(`AcceptKnownFormatDirectory`). 형식만 맞으면 아무 단말이나 붙었고,
이 서버는 공인 IP로 열려 있습니다. BO의 장비 화면이 명단을 이미 갖고
있는데도 물어볼 길이 없었습니다 — **BO의 API는 운영자 세션을 요구하고,
서비스 사이의 자격 증명은 정한 적이 없습니다.**

이 ADR이 그 문제를 이미 푼 방식이었는데, 2026-09-16에는 Sun-Moon-Document
를 기다리며 일부러 멈춰 둔 자리였습니다.

### 신원이 넓어졌습니다

`DeviceDirectory.isRegistered(deviceId)` → `(storeId, deviceId, type)`.

단말의 신원은 원래 **(매장, 번호, 종류)**이고 그게 `TerminalId` 가 이미
쓰는 규칙입니다 — 같은 매장의 POS와 KDS가 둘 다 `01` 일 수 있어서
번호만으로는 못 가립니다. 확인하는 자리만 그걸 몰랐습니다.

핸드셰이크에서 검사 순서도 바뀌었습니다: **매장과 종류를 안 뒤에**
묻습니다.

### 캐시가 이 결정의 핵심입니다

명단을 붙을 때마다 읽지 않고 **1분마다 받아 둡니다.** 이유는 이
ADR이 HTTP 대신 데이터베이스를 고른 이유와 같은 것의 한 단계
더입니다:

- **BO는 재배포마다 내려갑니다.** 그때 전 매장 단말이 끊기면
  기준정보를 고치는 일이 장사를 멈추게 합니다.
- 명단은 거의 안 바뀝니다. 장비를 새로 놓을 때 1분 늦는 것은
  아무 문제가 아닙니다.
- 재접속이 몰리는 순간(서버 재시작 직후)에 가장 느려지면 안 됩니다.

갱신에 실패하면 **들고 있던 명단으로 계속 갑니다.**

### 모르는 것과 없는 것

**명단을 한 번도 못 읽었으면 막지 않습니다.** 대신 크게 경고합니다.

뒤집으면 실제로 일어나는 일: fdw 뷰가 아직 안 걸린 서버에 이 버전이
올라가고, **전 매장의 단말이 조용히 못 붙습니다.** 증상은 "이 단말은
등록되지 않았습니다"로 보여서, 원인을 BO의 장비 화면에서 찾게 됩니다 —
거기에는 멀쩡히 등록돼 있는데도요.

같은 이유로 **기본값이 꺼짐**입니다. `DEVICE_DIRECTORY=bo` 를 줘야
켜집니다. 순서는 배포 → SQL → 재기동이고, 그 SQL은 superuser가
필요해서 사람 손을 탑니다.

### 매장이 비어 있는 줄

BO의 `devices` 에는 `store_id` 가 비어 있는 줄이 있습니다 — 그 컬럼이
생기기 전에 등록된 것들입니다. 그것들은 **매장 무관**으로 받습니다.
막으면 지금 붙어 있는 단말이 다음 재접속에서 떨어집니다.

### 남은 것

BO `devices` 의 PK가 여전히 `device_id` 단독입니다. 그래서 **두 매장이
둘 다 `01` 번 POS를 가질 수 없습니다.** 지금 명단 대조는 이름이 전역에서
유일하다는 전제 위에서 동작하고 있고, 그 전제가 깨지는 날 PK를 (매장,
번호, 종류)로 다시 잡아야 합니다. 살아 있는 표의 PK를 바꾸는 일이라
따로 결정할 자리입니다.
