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
