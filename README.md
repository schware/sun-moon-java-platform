# sun-moon-java-platform

DDD-based order/kitchen/delivery system plus its Back Office, split into
four independently deployable services. This repo is an **umbrella** tying
them together as git submodules — there's no application source here, just
the cross-cutting architecture decisions and links.

New here? [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) is what the four
services agree on, written as commands you run rather than rules you take
on trust.

| Service | Repo | Role | Data store |
|---|---|---|---|
| **Order** | [`order/`](order) → [sun-moon-java-platform-order](https://github.com/schware/sun-moon-java-platform-order) | Order creation, publishes `OrderCreated` | Postgres (JSONB) |
| **KDS** | [`kds/`](kds) → [sun-moon-java-platform-kds](https://github.com/schware/sun-moon-java-platform-kds) | Kitchen ticket queue, publishes `OrderReady` | Redis |
| **Delivery** | [`delivery/`](delivery) → [sun-moon-java-platform-delivery](https://github.com/schware/sun-moon-java-platform-delivery) | Courier assignment/tracking, publishes `OrderDelivered` | Postgres (JSONB) |
| **BO** | [`bo/`](bo) → [sun-moon-java-platform-bo](https://github.com/schware/sun-moon-java-platform-bo) |  Back Office — 운영자, 공지사항, 공통코드, 장비 (React 콘솔 포함) | Postgres |

Archived predecessors:
[sun-moon-java-platform-netty](https://github.com/schware/sun-moon-java-platform-netty)
(single-service, Netty, no Spring, pre-split) and the WAR/shared-Jetty
version of each service —
[order-jetty](https://github.com/schware/sun-moon-java-platform-order-jetty),
[kds-jetty](https://github.com/schware/sun-moon-java-platform-kds-jetty),
[delivery-jetty](https://github.com/schware/sun-moon-java-platform-delivery-jetty)
— see `docs/adr/0003` for why those were replaced with Docker. BO's
Spring-free Netty implementation is archived as
[sun-moon-platform-bo-netty](https://github.com/schware/sun-moon-platform-bo-netty)
— see `docs/adr/0004`.

## Cloning

```
git clone --recurse-submodules https://github.com/schware/sun-moon-java-platform.git
```

Already cloned without `--recurse-submodules`?

```
git submodule update --init --recursive
```

## Where things run

Each service runs as its own Docker container on the Debian homelab host
(`--network host`, one port each) — real process isolation, independent
redeploy, per-container memory limits. See
[`Debian-Setting/docs/docker.md`](https://github.com/schware/Debian-Setting/blob/master/docs/docker.md)
for how to build/run/redeploy.

| | URL | |
|---|---|---|
| BO | `http://<host>:8080/bo/` | LAN only |
| KDS | `http://<host>:8081/kds/` | |
| Delivery | `http://<host>:8082/delivery/` | |
| Order | `http://<host>:8083/order/` | |

Port numbers come from the server-wide scheme, not from this repo — see
Debian-Setting's `docs/docker.md`. Order moved 8080 → 8083 to free 8080
for BO.

Previously all three ran as WARs on one shared Jetty instance — see
`docs/adr/0003` for why that changed. That setup's docs/scripts are
preserved in the now-archived `-jetty` repos (linked there) in case the
Docker approach ever needs to be un-done.

## Architecture decisions (this repo)

- [`docs/adr/0001`](docs/adr/0001-per-service-database-choice.md) — final
  per-service database choice (Order/Delivery: Postgres+JSONB, KDS:
  Redis) and the hardware discovery that changed the plan mid-flight
  (MongoDB doesn't run on this box's CPU).
- [`docs/adr/0002`](docs/adr/0002-msa-split-direction.md) — why the
  system is split into Order/KDS/Delivery at all and the event flow
  between them (messaging bus not implemented yet).
- [`docs/adr/0003`](docs/adr/0003-docker-replaces-shared-jetty.md) — why
  the shared-Jetty deployment was replaced with one Docker container per
  service, and where the old WAR/Jetty setup's docs live now.
- [`docs/adr/0004`](docs/adr/0004-bo-joins-this-family-as-a-spring-service.md)
  — why BO was rebuilt on Spring and joined this family, rather than
  staying the one service built on raw Netty.
- [`docs/adr/0005`](docs/adr/0005-bo-ships-its-own-react-console.md) — why
  BO's React/TypeScript console ships inside its own jar, and why that
  forced the API under `/api`.
- [`docs/adr/0006`](docs/adr/0006-business-day-and-the-sales-key.md) —
  why 영업일 lives in Order rather than BO, why nothing closes a store on
  a timer, and the four-part sales key everything else hangs off.
- [`docs/adr/0007`](docs/adr/0007-a-table-is-a-destination-and-qr-codes-are-not-one-thing.md)
  — a table is where an order *goes*, not a session or a bill, and why
  테이블 QR and 주문 QR get separate code spaces instead of one abstraction.
- [`docs/adr/0008`](docs/adr/0008-execution-plane-services-read-bo-master-through-a-view.md)
  — how OMS and APP-SERVER read BO's master at runtime (postgres_fdw view,
  read-only), and why that is a deliberate boundary change.
- [`docs/adr/0009`](docs/adr/0009-the-pos-terminal-mints-the-transaction-number.md)
  — 거래번호 is minted by the POS terminal, not the server, and the unique
  constraint that makes re-sending a transaction safe.
- [`docs/adr/0010`](docs/adr/0010-the-pos-terminal-holds-state-without-a-store-server.md)
  — no store server; the POS keeps its own SQLite, becomes the origin of
  its transactions, and uploads them in batches.
- [`docs/adr/0011`](docs/adr/0011-sold-out-and-the-pickup-number.md)
  — 결품 is keyed by 판매일자 so 개점 clears it with no batch to forget, and
  수령번호 is two values with two jobs (부르는 번호 / 증명하는 번호).
- [`docs/adr/0012`](docs/adr/0012-app-server-owns-the-customer-order-ledger.md)
  — APP-SERVER keeps its own order ledger because "what the customer asked
  for" and "what the store was given to make" are different records; the
  screen mints the idempotency key, and 거절 is not 못 닿음.
- [`docs/adr/0014`](docs/adr/0014-master-screens-are-definitions-not-code.md)
  — 기준정보 화면은 코드가 아니라 정의다. 정의 한 줄이 화면 하나이고,
  감사로그는 한 곳을 지나가므로 한 번만 만들면 전부에 붙는다. 무엇에 쓰고
  **무엇에 안 쓰는가**가 이 구조의 실제 결정이다.
- [`docs/adr/0013`](docs/adr/0013-pos-server-becomes-a-system-of-record.md)
  — POS-Server starts holding facts: 매출 원본과 분해, 단말 단위 개점·마감
  (재개점은 서버가 판단하고 지나간 전문은 상태를 못 바꾼다), Live Check 5종,
  마스터 배포(적용 버전은 장비가 말해야 올라간다). 추가분(09-18): §3.5 의 나머지
  — 시계는 서버가 재고, 프로그램 버전은 이름이라 되돌리기가 그냥 새 배포이며,
  긴급 메시지는 먼저 적고 그다음에 민다. 그리고 그것을 볼 BO 화면 넷.

> **다음에 이어서 하려면** [`docs/HANDOFF-2026-09-18.md`](docs/HANDOFF-2026-09-18.md)
> 부터 읽으십시오 — 지금 어디까지 왔고 다음에 무엇을 집을지, 그리고
> "모든 것을 공통으로"라는 방향을 어디에 쓰고 어디에 쓰면 안 되는지가
> 정리돼 있습니다.

The archived `-jetty` repos (linked from `docs/adr/0003`) each have their
own `docs/adr/` covering the WAR/Jetty deployment mechanics and a
classpath bug worth knowing about if that setup is ever revisited.
