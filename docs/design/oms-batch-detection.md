# 설계 — OMS Batch Job: 찾아내는 일 넷 (업무 정의서 §5.8)

- 결정: 2026-10-08 Sun&Moon — §5.8 의 네 가지를 **문서대로**. 정합성 비교는 **OMS ↔ 채널(APP-SERVER)**,
  범위는 **오늘과 어제 영업일**, 보여 주기만 하는 종류는 공통 라이브러리에 `retryable` 칸을 더해 "다시" 를 숨김.
- 근거: Sun-Moon-Document `Total_Management _Solution/Business_Requirements_Document.md` §5.8, §4.4.6
- 관련: [`reprocess-library.md`](reprocess-library.md) (틀), ADR-0012 (손님 원장)
- 상태: **배포됨 (2026-10-09)** — java-common `46d472b`(0.2.0) · Order `c68e96e` · APP-SERVER `9d128b0` · BO `53a4730`.
  시험 java-common 12 · Order 128 · APP-SERVER 75 · BO 93. 서버에서 새 종류가 모두 0건으로 답함(쿼리가 실제 DB 에서 돎),
  `GET /order/orders/digest` 응답, BO 번들에 "찾아낸 목록" 문구 확인. 배포 중 `/var` 가 가득 찬 일은 Debian-Setting
  `docs/debian13.md` "오늘 겪은 것"

## 1. 무엇을 찾나

§5.8 의 일곱 줄 중 이미 있는 것과 이번 것:

| §5.8 | 어디서 | 상태 |
|---|---|---|
| 채널 전달 실패 건 재처리 | APP-SERVER `APP_SEND` | 결정 대기 (reprocess-library §11-1) |
| POS 수신 확인이 없는 주문 조회 | OMS `ORDER_NOT_RECEIVED` | **이번** |
| 상태 지연 및 장기 미처리 주문 확인 · 미종료 주문 확인 | OMS `ORDER_STALLED` | **이번** (둘을 한 종류로) |
| 중복 주문과 중복 이벤트 확인 | OMS `ORDER_DUPLICATE` | **이번 — 중복 주문만.** 중복 이벤트는 Redis Pub/Sub 이라 받은 쪽 기록이 없어 찾을 수 없음 |
| OMS와 채널 간 상태 정합성 비교 | APP-SERVER `CHANNEL_MISMATCH` | **이번** |
| 누락 상태 재전송 | `ORDER_NOT_RECEIVED`·`ORDER_STALLED` 의 "다시" | **이번** |

## 2. 규칙

모두 **그 매장의 최근 영업일 두 개**(오늘·어제)만 봅니다 — 오래된 것까지 늘 보이면 화면이 쓸모없어집니다.
OMS 는 `store_business_days`, APP-SERVER 는 원장의 `business_date` 로 정합니다. **표를 새로 만들지 않습니다** —
찾아낸 것은 지금 상태에서 매번 다시 계산하고, 주문이 처리되면 목록에서 저절로 사라집니다.

| kind | 찾는 것 | "다시" (`retryable`) |
|---|---|---|
| `ORDER_NOT_RECEIVED` | PLACED · `received_at` 없음 · 접수 후 `DETECT_NOT_RECEIVED_AFTER`(30초) 지남 | 그 주문을 막 들어온 것처럼 이벤트로 다시 냄 → POS 가 다시 받음 (✔) |
| `ORDER_STALLED` | ① 지난 영업일인데 안 끝남(미종료) ② ACCEPTED·COOKING 이 마지막 변경 후 30분(`DETECT_STALLED_COOKING_AFTER`) ③ PRODUCED 가 60분(`DETECT_STALLED_PICKUP_AFTER`) 넘게 수령 안 됨 | 지금 상태를 다시 알림(단말이 다시 읽음) (✔) |
| `ORDER_DUPLICATE` | 같은 매장 · 같은 손님 · 같은 금액 · 같은 줄(메뉴×수량)이 `DETECT_DUPLICATE_WINDOW`(2분) 안에 두 번. 거절·만료·취소된 것은 빼고, 손님이 `kiosk`(회원번호 없는 KIOSK)인 것도 뺌 — 다른 손님이 같은 메뉴를 시킨 것일 수 있음 | 없음 — POS 에서 하나를 거절·취소 (✘) |
| `CHANNEL_MISMATCH` | ① 원장은 실패·거절(환불)인데 OMS 에 같은 **승인번호**의 주문이 살아 있음 — 돈은 돌려줬는데 음식이 나감 ② 원장은 SENT 인데 OMS 에 주문이 없거나 거절·만료·취소 — 매장은 안 받았는데 돈은 받은 채 ③ 원장은 손님 취소인데 OMS 주문이 살아 있음 | 없음 — 환불·취소는 사람이 판단 (✘) |

`CHANNEL_MISMATCH` 는 APP-SERVER 가 OMS 의 `GET /orders/digest?since=` 를 **한 번** 불러(주문마다 부르지 않음)
`(id, 매장, 영업일, 상태, 승인번호)` 를 받아 원장과 맞춰 봅니다. OMS 에 닿지 못하면 빈 목록이 아니라
**"OMS 를 읽지 못해 비교하지 못했습니다"** 한 줄을 냅니다(모르는 것과 없는 것은 다르다).

## 3. 공통 라이브러리 — `retryable` (0.2.0)

`Reprocessor.retryable()` (기본 참)과 응답의 종류마다 `retryable` 칸. 거짓이면 BO 가 "다시"·"전부 다시" 를 숨기고
"찾아낸 목록 — 원래 자리에서 처리하면 사라집니다" 를 보여 줍니다. 서비스가 `retry` 를 받아도 아무것도 하지 않습니다.
Device Server 는 0.1.0 그대로(칸이 없으면 BO 가 참으로 읽음).

## 4. 순서

1. java-common 0.2.0 (`retryable`)
2. OMS — `RecentOrder` 조회(최근 영업일 둘), 세 종류, `GET /orders/digest`
3. APP-SERVER — `CHANNEL_MISMATCH`, OMS digest 읽기
4. BO — `retryable` 이면 버튼 숨김
5. 배포 OMS → APP-SERVER → BO (한 번에 하나)

## 5. 확인

- 각 저장소 시험(규칙마다 경계값)
- 서버: `GET /order/reprocess`·`/app-server/reprocess` 가 새 종류를 0건으로 답함(쿼리가 실제 DB 에서 돎),
  `GET /order/orders/digest` 의 모양, BO 재처리 화면에 서비스별로 보이고 찾아낸 종류에는 "다시" 가 없음
