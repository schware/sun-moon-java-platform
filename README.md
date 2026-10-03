# sun-moon-java-platform

매장 주문·주방·매출 시스템(sun-moon)의 **umbrella** 저장소입니다. 애플리케이션 소스는
없고, 서비스 저장소를 submodule 로 묶고 전체에 걸친 결정(ADR)과 문서를 둡니다.

## 읽는 순서

1. [`docs/FLOW.md`](docs/FLOW.md) — **지금 전체가 어떻게 동작하는가** (한 장 그림, 주문의 일생, 켜는 스위치)
2. [`docs/REVIEW-2026-09-29.md`](docs/REVIEW-2026-09-29.md) — 기능 확인표 (어디서 보나, 근거, 상태)
3. [`docs/HANDOFF-2026-10-03.md`](docs/HANDOFF-2026-10-03.md) — 새 서버(Debian 13), 도메인, 최근 정리, 공통화 선택지 (그 앞은 [10-01](docs/HANDOFF-2026-10-01.md) · [09-29](docs/HANDOFF-2026-09-29.md))
4. 설계: [`docs/design/store-app.md`](docs/design/store-app.md) (매장 단말) ·
   [`docs/design/customer-app.md`](docs/design/customer-app.md) (손님 앱)
   · 공통화(10-03 결정, 구현 전): [`terminal-monorepo.md`](docs/design/terminal-monorepo.md) (단말 화면 한 저장소) ·
   [`reprocess-library.md`](docs/design/reprocess-library.md) (재처리 공통 라이브러리)
5. Spring 서비스들의 공통 규칙: [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) — 규칙이 아니라 돌려 보는 명령으로

## 전체 구성 (2026-09-29)

전부 Debian 서버(192.168.0.2)에 서비스마다 Docker 컨테이너 하나로 돕니다. 빌드·배포는
[`Debian-Setting/docs/docker.md`](https://github.com/schware/Debian-Setting/blob/master/docs/docker.md),
비밀의 위치는 같은 저장소의 `docs/secrets.md` (값은 어디에도 적지 않음).

| 구성 | 저장소 | 여기서 | 주소 | 역할 |
|---|---|---|---|---|
| **OMS (Order)** | [sun-moon-java-platform-order](https://github.com/schware/sun-moon-java-platform-order) | [`order/`](order) | `:8083/order/` LAN | 주문·영업일·수령번호 — 사실이 적히는 곳 |
| **APP-SERVER** | [sun-moon-java-platform-app-server](https://github.com/schware/sun-moon-java-platform-app-server) | [`app-server/`](app-server) | `:8086` LAN | 손님 원장·결제·회원·푸시 |
| **channel-order** | [sun-moon-java-platform-channel-order](https://github.com/schware/sun-moon-java-platform-channel-order) | [`channel-order/`](channel-order) | `:8085/channel-order/` 공개 | 손님 창구 — QR 웹, 앱 API, KIOSK 화면 |
| **BO** | [sun-moon-java-platform-bo](https://github.com/schware/sun-moon-java-platform-bo) | [`bo/`](bo) | `:8080/bo/` LAN | 기준정보·장비·매출 조회·관제 (React 콘솔 포함) |
| **Device Server** | 이 GitHub 저장소의 **`master` 브랜치** | — | `:8087` 공개 | 단말 연결(WebSocket)·매출 원본·라벨·운영 (Netty) |
| **POS · KDS · DID 화면** | [sun-moon-terminals](https://github.com/schware/sun-moon-terminals) (private, `pos/` `kds/` `did/` + 공통 `common/`) — 2026-10-03 에 셋을 합침, 옛 저장소는 archive | — | `:8000/pos/` `/kds/` `/did/` | 매장 단말 웹 화면 (nginx 가 번들을 줌) |
| **매장 단말 셸** | [sun-moon-store-app](https://github.com/schware/sun-moon-store-app) (private) | — | — | POS·KIOSK·KDS-MAIN 용 Electron 셸 — 매장에는 아직 없음 |
| 서버 설정 | [Debian-Setting](https://github.com/schware/Debian-Setting) | — | — | 배포·DB·비밀 위치 |
| KDS · Delivery | [kds](https://github.com/schware/sun-moon-java-platform-kds) · [delivery](https://github.com/schware/sun-moon-java-platform-delivery) | [`kds/`](kds) [`delivery/`](delivery) | `:8081` · `:8082` | 처음 MSA 로 나눌 때의 서비스. 컨테이너는 떠 있지만 지금의 주문 흐름(FLOW.md)에는 없음 |

submodule 은 각 저장소의 최신 커밋(= 서버에 배포된 커밋)을 가리킵니다.

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

submodule 이 아닌 것 (Device Server, 단말 화면, 셸):

```
git clone -b master https://github.com/schware/sun-moon-java-platform.git sun-moon-device-server
git clone https://github.com/schware/sun-moon-terminals.git
git clone https://github.com/schware/sun-moon-store-app.git
```

## Where things run

Port numbers come from the server-wide scheme, not from this repo — see
Debian-Setting's `docs/docker.md`. Order moved 8080 → 8083 to free 8080
for BO. 전체 주소는 위의 "전체 구성" 표에 있습니다.

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
- [`docs/adr/0015`](docs/adr/0015-store-terminals-electron-where-the-hardware-is.md)
  — 매장 단말은 하드웨어(원장·프린터)가 있는 곳만 Electron 셸, 나머지는 웹. KDS-MAIN 이
  주방의 중심.

> **지금 어떻게 동작하는지는** [`docs/FLOW.md`](docs/FLOW.md) 한 곳에 있습니다 —
> 단말과 서버, 기기 신원(페어링·토큰), 주문의 일생, KIOSK, 라벨, 셸, 켜는 스위치.
>
> **다음에 이어서 하려면** 가장 최근의 [`docs/HANDOFF-2026-09-29.md`](docs/HANDOFF-2026-09-29.md)
> 부터 읽으십시오 (그 앞은 [09-27](docs/HANDOFF-2026-09-27.md) ·
> [09-24](docs/HANDOFF-2026-09-24.md) · [09-18](docs/HANDOFF-2026-09-18.md) —
> "모든 것을 공통으로" 라는 방향을 어디에 쓰고 어디에 쓰면 안 되는지는 09-18 에).
>
> 매장 단말 셸(Electron)은 별도 저장소 `sun-moon-store-app` 입니다. 설계:
> [`docs/design/store-app.md`](docs/design/store-app.md) · 손님 앱:
> [`docs/design/customer-app.md`](docs/design/customer-app.md).

The archived `-jetty` repos (linked from `docs/adr/0003`) each have their
own `docs/adr/` covering the WAR/Jetty deployment mechanics and a
classpath bug worth knowing about if that setup is ever revisited.
