# 설계 — 실패 재처리 공통 라이브러리

- 결정: 2026-10-03 Sun&Moon — **안 ③ 공통 라이브러리** ([HANDOFF-2026-10-03](../HANDOFF-2026-10-03.md) §4-2)
- 상태: **설계 완료, 구현 전**. 구현은 §8 의 순서대로, 단계마다 §9 확인을 통과한 뒤 다음으로.
- 관련: 업무 정의서 §5.8(배치)·§10, APP-SERVER §4.4.6, umbrella ADR-0012(거절은 못 닿음이 아니다)

---

## 1. 원칙 — 틀은 같이, 처리는 각자

09-18 에 정한 것: **상태 기계는 규칙이 달라 합치지 않는다.** 그래서 이 라이브러리가 나누는 것은 **틀**뿐입니다.

| 라이브러리가 갖는 것 (같은 것) | 각 서비스가 갖는 것 (다른 것) |
|---|---|
| 실패 한 건의 모양(`FailedItem`) | 실패를 어디에 적고 어떻게 찾는가(표·질의) |
| 처리기 계약(`Reprocessor`) | **무엇을 다시 하는가** — 다시 보내기, 다시 발행, 다시 찍기 |
| 처리기 묶음과 "전부 다시"(`ReprocessService`) | HTTP 어댑터(Netty 는 `RestEndpoint`, Spring 은 `@RestController`) |
| **HTTP 응답 모양**(`ReprocessViews`) — BO 가 세 서비스를 같은 타입으로 읽게 | 인증(Device Server 는 관리 토큰, Order·APP-SERVER 는 LAN 전용) |

실패를 들고 있는 것도, 되돌리는 것도 **실패한 서비스 자신**입니다. 다시 하는 법을 아는 쪽이 주인입니다.

## 2. 지금 있는 것 (2026-10-03)

- **Device Server** — 틀이 이미 있습니다: `domain/reprocess/` 의 `Reprocessor`·`FailedItem`·`ReprocessService` 와
  처리기 다섯(`SALE` 매출 분해, `ETC` ETC 매출 분해, `MASTER` 마스터 배포, `PROGRAM` 프로그램 설치, `PRINT` 라벨 — 앞의 둘은 같은 `RawLogReprocessor`). 틀은
  slf4j 말고는 아무것에도 기대지 않습니다 — **그대로 라이브러리로 옮길 수 있습니다.**
- **APP-SERVER** — 손님 원장에 `SEND_FAILED`(OMS 에 닿지 못한 주문)가 이미 있고, 주석이 "재처리 대상이라 가장
  잘 보여야 한다(§4.4.6)" 고 말합니다. **틀만 없습니다.**
- **Order** — 이벤트 발행(`RedisEventPublisher`)이 실패하면 예외를 던지고 끝납니다. 주문은 저장됐지만 **그
  이벤트는 사라지고 기록도 없습니다**(단말은 15초 폴링으로 결국 알게 되지만, 손님 앱 푸시·라벨 작업은 이벤트로만 움직임).
- **BO** — 재처리 화면(`ReprocessPage`)이 Device Server 의 `/reprocess` 하나만 부릅니다.

## 3. 라이브러리

### 3-1. 어디에 두나

| 안 | 평가 |
|---|---|
| `sun-moon-platform-core` 안에 | ✗ — core 는 Netty·MyBatis·Jackson 을 `api` 로 끌고 옵니다. Spring 서비스가 재처리 틀 하나 때문에 Netty 를 받게 됩니다. core 의 규칙("도메인을 모른다")에도 걸립니다 |
| **새 저장소 `sun-moon-java-common`, 모듈 `reprocess`** | ✓ — **의존성은 slf4j-api 하나**. 다음에 공통화할 것(업스트림 읽기, 배포·적용 결과 — 09-18 목록)도 같은 저장소의 다른 모듈로 들어올 자리 |

```text
sun-moon-java-common/                (private)
├── settings.gradle.kts              include("reprocess")
├── build.gradle.kts                 Java 21 toolchain, group com.sunmoon
└── reprocess/
    ├── build.gradle.kts             artifact com.sunmoon:sun-moon-reprocess:0.1.0, 의존성 slf4j-api 만
    └── src/main/java/com/sunmoon/common/reprocess/
        ├── FailedItem.java          (Device Server 에서 옮김)
        ├── Reprocessor.java         (옮김 — Outcome 포함)
        ├── ReprocessService.java    (옮김 — Retried 포함)
        └── ReprocessViews.java      (새로 — §4 의 응답 모양을 Map 으로 만드는 순수 함수)
```

### 3-2. 가져다 쓰는 방법

지금 Device Server 가 core 를 쓰는 방식과 **같게** 합니다 — 검증된 길입니다.

- 각 서비스 저장소에 **git submodule** 로 `java-common/` 을 두고, `settings.gradle.kts` 에 `includeBuild("java-common")`,
  `build.gradle.kts` 에 `implementation("com.sunmoon:sun-moon-reprocess:0.1.0")`.
- Docker 빌드는 저장소를 통째로 복사하므로 submodule 이 받아져 있으면 그대로 됩니다 → 서버의 `deploy.sh` 가
  모든 서비스에서 `git submodule update --init` 을 하도록(지금은 device 만).
- 서버에 **deploy key 하나 더**(`java-common`, 읽기 전용) — core 때와 같은 절차.
- **나중에 Forgejo 가 주 저장소가 되면** Forgejo 의 Maven 패키지 저장소에 올리고 submodule 대신 버전 의존성으로
  바꿉니다. 그때까지는 submodule 이 단순합니다.

## 4. HTTP 계약 — 세 서비스가 같은 모양으로

지금 Device Server 와 BO 사이의 모양을 **그대로 표준으로** 삼습니다(BO 의 `PosOps.Reprocess` 등이 이미 이걸 읽음).

| 요청 | 응답 |
|---|---|
| `GET {base}/reprocess?storeId=` | `{storeId, kinds: [{kind, label, count, items: [{id, storeId, summary, error, attempts, failedAt}]}]}` |
| `POST {base}/reprocess/one?kind=&id=&storeId=` | `{kind, id, accepted, done, message}` |
| `POST {base}/reprocess/all?kind=&storeId=` | `{kind, queued, failed}` |

`ReprocessViews` 가 이 세 모양을 `Map` 으로 만들어 주고, 각 서비스의 어댑터는 그걸 JSON 으로 내보내기만 합니다.
**`kind` 는 서비스 사이에서 겹치지 않게** 서비스 접두사를 붙입니다(아래 §5) — BO 가 한 화면에 모을 때 구분됩니다.

## 5. 처음 붙일 처리기

| 서비스 | kind | 실패란 | 다시 하기 | 필요한 것 |
|---|---|---|---|---|
| APP-SERVER | `APP_SEND` | 손님 원장의 `SEND_FAILED` 주문 | OMS 로 **다시 보내기**. 화면이 만든 멱등 키(ADR-0012)를 그대로 써서 두 번 들어가지 않게 | 표는 이미 있음. 처리기 + 컨트롤러 |
| Order | `ORDER_EVENT` | 발행에 실패한 주문 이벤트 | Redis 로 **다시 발행** | **실패 이벤트 표**(`V17__failed_order_event.sql`: id, order_id, store_id, payload, error, attempts, failed_at, resolved_at) — 발행이 실패하면 거기 적고 예외는 지금처럼 |
| Device Server | `SALE` `ETC` `MASTER` `PROGRAM` `PRINT` | (지금 그대로) | (지금 그대로) | import 경로만 라이브러리로 |

Device Server 의 kind 는 이미 BO 화면에 쓰이고 있어 **이름을 바꾸지 않습니다.** 새로 생기는 것만 서비스 접두사를 붙입니다.

업무 정의서 §5.8 의 나머지 배치(POS 수신 확인 없는 주문, 장기 미처리, 중복, 상태 정합성 비교)는 **실패가 아니라
"찾아내는" 일**이라 이 틀에 바로 맞지는 않습니다 — 찾아낸 건을 `FailedItem` 으로 보여 주고 "다시"를 정의할 수
있는 것만 처리기가 됩니다. 이번 범위가 아닙니다(§7).

## 6. BO — 한 화면에 셋을

- `ReprocessPage` 가 **Device Server · Order · APP-SERVER** 세 곳을 부르고, 종류 목록에 **"서비스" 칸**을 더합니다.
- 주소: Device Server 는 지금처럼(관리 토큰), Order·APP-SERVER 는 BO 가 이미 가진 `ORDER_SERVICE_URL`·
  `APP_SERVER_URL` 로. 두 서비스는 LAN 전용이라 토큰 없음(지금 다른 BO 호출과 같음).
- **한 곳이 안 닿아도 나머지는 보입니다.** 안 닿은 서비스는 "읽지 못함(서비스 이름)"으로 표시 — 빈 목록으로 보이면
  "실패 0건"과 구분이 안 됩니다(BO 매출 조회와 같은 원칙).
- BO 쪽 타입(`PosOps.Reprocess` …)은 그대로 쓰고, 이름만 공통으로 옮길지는 구현 때 판단(동작은 같음).

## 7. 이번 범위가 아닌 것

- §5.8 의 "찾아내는" 배치들 — 틀이 생긴 뒤 하나씩 정함
- 자동 재시도(일정 시간마다 저절로 다시) — 지금은 BO 에서 사람이 누름. 필요하면 서비스마다 스케줄러가 `retryAll` 을 부르면 됨
- 공통 HTTP 어댑터(Spring 자동 설정 모듈) — 컨트롤러가 20줄 정도라 서비스마다 둬도 됨. 셋 이상이 같은 모양이면 그때

## 8. 구현 순서 (단계마다 §9 확인 후 다음)

1. **`sun-moon-java-common` 저장소** — Gradle, `reprocess` 모듈, Device Server 의 세 파일을 옮기고 패키지만 바꿈 +
   `ReprocessViews`. 라이브러리 단위 시험(Device Server 의 `ReprocessServiceTest` 를 옮겨 옴).
2. **Device Server 를 첫 소비자로** — submodule + `includeBuild`, `domain/reprocess` 의 세 파일을 지우고 import 를
   라이브러리로, `ReprocessEndpoints` 가 `ReprocessViews` 를 씀. **응답이 글자까지 같은지**(BO 화면이 그대로 뜨는지) 확인.
3. **APP-SERVER** — submodule, `AppSendReprocessor`(`SEND_FAILED` 조회 + 다시 보내기), `ReprocessController`.
4. **Order** — submodule, `V17` 실패 이벤트 표, 발행 실패 시 기록, `OrderEventReprocessor`, `ReprocessController`.
5. **BO** — 세 곳을 모으는 `ReprocessPage`·백엔드.
6. **서버** — `deploy.sh` 가 모든 서비스에서 submodule 받기, `java-common` deploy key, 배포 순서: Device Server →
   APP-SERVER → Order → BO.

## 9. 확인 (단계마다)

- 각 저장소 `./gradlew test` 통과, 라이브러리는 자체 시험 통과
- Device Server: BO 재처리 화면이 **바뀌기 전과 똑같이** 보임(목록·한 건 다시·전부 다시)
- APP-SERVER: 시험 매장에서 Order 를 잠시 멈추고 손님 주문 → `SEND_FAILED` → Order 를 다시 띄우고 BO 에서 "다시" →
  주문이 OMS 에 **한 번만** 들어감(멱등 키)
- Order: Redis 를 잠시 멈추고 주문 상태 변경 → 실패 이벤트 표에 기록 → Redis 를 다시 띄우고 "다시" → 단말이 신호를 받음
- BO: 한 서비스를 멈춰도 나머지 둘의 목록이 보이고, 멈춘 쪽은 "읽지 못함"

## 10. 정해 주실 것

| # | 질문 | 제안 |
|---|---|---|
| 1 | 라이브러리 저장소 이름 | `sun-moon-java-common` (모듈 `reprocess`) |
| 2 | 새 kind 이름 | 서비스 접두사 — `APP_SEND`, `ORDER_EVENT` |
| 3 | Order 실패 이벤트를 다시 발행할 때 **옛 상태의 이벤트**를 그대로 보낼지, 지금 주문 상태로 새로 만들지 | **지금 상태로 새로** — 이벤트는 "바뀌었다"는 신호이고 받는 쪽이 다시 읽으므로(FLOW §9), 옛 상태를 그대로 보내면 늦게 온 신호가 화면을 되돌릴 수 있음 |
