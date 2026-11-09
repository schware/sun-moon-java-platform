# sun-moon-java-platform

**매장 주문·주방·매출 시스템 sun-moon** 의 전체를 소개하는 저장소입니다.

손님이 QR·앱·키오스크로 주문하면, 매장의 POS·주방 화면(KDS)·호출 화면(DID)을 거쳐 매출로 남기까지의
흐름을 서비스 여러 개로 나누어 만들었습니다. 이 저장소에는 실행 코드가 없고, 서비스 저장소를
submodule 로 묶어 **전체 구조와 설계 결정(ADR)** 을 둡니다(umbrella 저장소).

## 한눈에

- **서비스**: Spring Boot 서비스 6개 + Netty Device Server + 단말 화면 + Electron 매장 셸
- **기술**: Java 21 · Spring Boot 3.3 · Netty · PostgreSQL(JSONB, postgres_fdw) · Redis · React 18 + TypeScript · Electron · Docker
- **설계 기록**: ADR 15편 — 무엇을 왜 그렇게 정했는지, 무엇을 버렸는지
- **운영**: 서비스마다 Docker 컨테이너 하나. 빌드는 개발 서버, 실행은 배포 서버에서 ([배포 절차](docs/DEPLOY-MACPRO.md))

## 구조

```
  손님 ── QR 웹 · 앱 ──→ channel-order ──→ APP-SERVER ──┐
                       (손님 창구)        (손님 원장·결제)  ↓
                                                    OMS (Order) ── Redis order-events
                                                    (사실이 적히는 곳)      │
                                                         ↑                  │
                           BO ──→ Device Server ─────────┘ ←────────────────┘
                     (기준정보·관제)  (단말 연결·인증·매출 원본)
                                         ↑ HTTP + WebSocket
  매장 ── POS · KIOSK · KDS-MAIN (Electron 셸) / KDS SUB · DID (웹)
```

**매장 안에는 서버가 없습니다**([ADR-0010](docs/adr/0010-the-pos-terminal-holds-state-without-a-store-server.md)).
모든 단말은 중앙의 Device Server 하나에 붙고, POS 는 자기 원장(SQLite)을 들고 매출을 올립니다.
주문 한 건이 어떻게 흘러가는지는 [`docs/FLOW.md`](docs/FLOW.md) 에 있습니다.

## 저장소

| 구성 | 저장소 | 역할 |
|---|---|---|
| **OMS (Order)** | [sun-moon-java-platform-order](https://github.com/schware/sun-moon-java-platform-order) | 주문·영업일·수령번호 — 사실이 적히는 곳 |
| **APP-SERVER** | [sun-moon-java-platform-app-server](https://github.com/schware/sun-moon-java-platform-app-server) | 손님 원장·결제·회원·푸시 |
| **channel-order** | [sun-moon-java-platform-channel-order](https://github.com/schware/sun-moon-java-platform-channel-order) | 손님 창구 — QR 웹, 앱 API, KIOSK 화면 |
| **BO** | [sun-moon-java-platform-bo](https://github.com/schware/sun-moon-java-platform-bo) | 기준정보·장비·매출 조회·관제 (React 콘솔 포함) |
| **Device Server** | 이 저장소의 [`master` branch](https://github.com/schware/sun-moon-java-platform/tree/master) | 단말 연결(WebSocket)·기기 인증·매출 원본·라벨·운영 (Netty) |
| **POS · KDS · DID 화면** | [sun-moon-terminals](https://github.com/schware/sun-moon-terminals) | 매장 단말 웹 화면 (`pos/` `kds/` `did/` + 공통 `common/`) |
| **매장 단말 셸** | [sun-moon-store-app](https://github.com/schware/sun-moon-store-app) | POS·KIOSK·KDS-MAIN 용 Electron 셸 — 원장·프린터·업데이트 |
| Java 공통 틀 | [sun-moon-java-common](https://github.com/schware/sun-moon-java-common) | 실패 재처리의 틀(`reprocess`) — [설계](docs/design/reprocess-library.md) |
| Netty 커널 | [sun-moon-platform-core](https://github.com/schware/sun-moon-platform-core) | Device Server 가 쓰는 Netty listener·REST routing 라이브러리 |
| KDS · Delivery | [kds](https://github.com/schware/sun-moon-java-platform-kds) · [delivery](https://github.com/schware/sun-moon-java-platform-delivery) | 처음 MSA 로 나눌 때의 서비스. 지금의 주문 흐름에는 없음 |

## 핵심 설계 결정

ADR 15편 중 이 시스템의 성격을 가장 잘 보여 주는 다섯 편입니다. 전체 목록은 [아래](#설계-결정-전체-adr)에 있습니다.

- [**0010**](docs/adr/0010-the-pos-terminal-holds-state-without-a-store-server.md) 매장 서버 없이 — POS 가 자기 원장을 들고, 거래의 출처가 되어 묶음으로 올린다
- [**0009**](docs/adr/0009-the-pos-terminal-mints-the-transaction-number.md) 거래번호는 서버가 아니라 POS 가 만든다 — 다시 보내도 안전하게 만드는 unique 제약
- [**0012**](docs/adr/0012-app-server-owns-the-customer-order-ledger.md) "손님이 요청한 것"과 "매장이 받은 것"은 다른 기록이다 — 멱등 key, 거절과 못 닿음의 구분
- [**0006**](docs/adr/0006-business-day-and-the-sales-key.md) 영업일은 Order 에 두고, 타이머로 매장을 닫지 않는다 — 네 부분짜리 매출 key
- [**0014**](docs/adr/0014-master-screens-are-definitions-not-code.md) 기준정보 화면은 코드가 아니라 정의다 — 무엇에 쓰고 무엇에 안 쓰는가

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/FLOW.md`](docs/FLOW.md) | 지금 전체가 어떻게 동작하는가 — 한 장 그림, 주문의 일생, 켜는 스위치 |
| [`docs/design/store-app.md`](docs/design/store-app.md) · [`customer-app.md`](docs/design/customer-app.md) | 매장 단말 앱 · 손님 앱 설계 |
| [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) | Spring 서비스들의 공통 규칙 — 규칙이 아니라 돌려 보는 명령으로 |
| [`docs/DEPLOY-MACPRO.md`](docs/DEPLOY-MACPRO.md) | 배포 절차와 한 줄 배포 script(`scripts/ship.sh`) |
| [`docs/REVIEW-2026-09-29.md`](docs/REVIEW-2026-09-29.md) | 기능 확인표 — 어디서 보나, 근거, 상태 |

## 설계 결정 전체 (ADR)

- [`0001`](docs/adr/0001-per-service-database-choice.md) — 서비스별 DB 선택(Order/Delivery: Postgres+JSONB, KDS: Redis)과, 도중에 계획을 바꾼 하드웨어 발견(이 서버 CPU 에서 MongoDB 가 돌지 않음)
- [`0002`](docs/adr/0002-msa-split-direction.md) — 왜 Order/KDS/Delivery 로 나누었는가, 그 사이의 event 흐름
- [`0003`](docs/adr/0003-docker-replaces-shared-jetty.md) — 공유 Jetty 배포를 서비스마다 Docker 컨테이너로 바꾼 이유
- [`0004`](docs/adr/0004-bo-joins-this-family-as-a-spring-service.md) — BO 를 raw Netty 에서 Spring 으로 다시 만들어 이 계열에 합친 이유
- [`0005`](docs/adr/0005-bo-ships-its-own-react-console.md) — BO 의 React 콘솔을 jar 안에 함께 싣는 이유, 그래서 API 가 `/api` 아래로 간 것
- [`0006`](docs/adr/0006-business-day-and-the-sales-key.md) — 영업일은 Order 에, 타이머로 닫지 않음, 네 부분짜리 매출 key
- [`0007`](docs/adr/0007-a-table-is-a-destination-and-qr-codes-are-not-one-thing.md) — 테이블은 주문이 *가는 곳*이다. 테이블 QR 과 주문 QR 을 나눈 이유
- [`0008`](docs/adr/0008-execution-plane-services-read-bo-master-through-a-view.md) — OMS·APP-SERVER 가 BO 기준정보를 읽는 방법(postgres_fdw view, 읽기 전용)
- [`0009`](docs/adr/0009-the-pos-terminal-mints-the-transaction-number.md) — 거래번호는 POS 가 만든다, 다시 보내도 안전한 unique 제약
- [`0010`](docs/adr/0010-the-pos-terminal-holds-state-without-a-store-server.md) — 매장 서버 없음, POS 가 SQLite 원장을 들고 묶음으로 올림
- [`0011`](docs/adr/0011-sold-out-and-the-pickup-number.md) — 결품은 판매일자 key 라 개점이 저절로 지운다, 수령번호는 두 값 두 역할
- [`0012`](docs/adr/0012-app-server-owns-the-customer-order-ledger.md) — APP-SERVER 가 자기 주문 원장을 갖는 이유, 화면이 만드는 멱등 key
- [`0013`](docs/adr/0013-pos-server-becomes-a-system-of-record.md) — POS-Server 가 사실을 갖기 시작한다: 매출 원본·분해, 개점·마감, Live Check, 마스터 배포
- [`0014`](docs/adr/0014-master-screens-are-definitions-not-code.md) — 기준정보 화면은 정의 한 줄이 화면 하나, 감사로그는 한 곳을 지난다
- [`0015`](docs/adr/0015-store-terminals-electron-where-the-hardware-is.md) — 하드웨어가 있는 단말만 Electron 셸, 나머지는 웹. KDS-MAIN 이 주방의 중심

## 받아 보기

```
git clone --recurse-submodules https://github.com/schware/sun-moon-java-platform.git
```

submodule 이 아닌 것 (Device Server, 단말 화면, 셸):

```
git clone -b master https://github.com/schware/sun-moon-java-platform.git sun-moon-device-server
git clone https://github.com/schware/sun-moon-terminals.git
git clone https://github.com/schware/sun-moon-store-app.git
```

submodule 은 각 저장소의 최신 commit(= 서버에 배포된 commit)을 가리킵니다.

## 작업 기록

진행 중에 남긴 인수인계 문서입니다. 시간 순서대로 무엇을 정하고 바꿨는지 볼 수 있습니다.
[09-18](docs/HANDOFF-2026-09-18.md) · [09-24](docs/HANDOFF-2026-09-24.md) · [09-27](docs/HANDOFF-2026-09-27.md) ·
[09-29](docs/HANDOFF-2026-09-29.md) · [10-01](docs/HANDOFF-2026-10-01.md) · [10-03](docs/HANDOFF-2026-10-03.md)

이전 구현(공유 Jetty 위의 WAR, Spring 없는 Netty BO)은 비공개 archive 저장소로 남겨 두었고,
바꾼 이유는 [ADR-0003](docs/adr/0003-docker-replaces-shared-jetty.md) · [ADR-0004](docs/adr/0004-bo-joins-this-family-as-a-spring-service.md) 에 있습니다.
