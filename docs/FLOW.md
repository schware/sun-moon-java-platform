# 전체 흐름 — 매장을 통제하는 시스템과 손님 쪽

2026-09-27, Sun&Moon 과 정리한 그림입니다. 결정의 이유는 ADR 에 있고
(특히 [0010](adr/0010-the-pos-terminal-holds-state-without-a-store-server.md),
[0013](adr/0013-pos-server-becomes-a-system-of-record.md),
[0015](adr/0015-store-terminals-electron-where-the-hardware-is.md)), 이 문서는
**지금 무엇이 어디로 흐르는가**를 한 곳에 모읍니다.

구현 설계는 둘로 나눴습니다:

- [`design/store-app.md`](design/store-app.md) — 매장 단말 앱 (POS · KIOSK · KDS-MAIN, Electron)
- [`design/customer-app.md`](design/customer-app.md) — 손님 앱 (웹 · 모바일 · 키오스크 화면)

---

## 1. 한 장 그림

```
                         ┌──────────────── 중앙 (클라우드 역할) ────────────────┐
  손님                    │                                                      │
  ├─ 휴대폰 앱 ──┐        │   channel-order ─→ APP-SERVER ─┐                     │
  ├─ QR 웹 ──────┼──────→ │   (손님 창구)      (손님 원장·결제)│                     │
  │              │        │                                  ↓                     │
  │              │        │                               OMS(Order) ── order-events ──┐
  외부 채널 ─────┼──────→ │                               (사실이 적히는 곳)  (Redis)    │
  (배민 등, 미래) │        │                                  ↑                  │      │
                 │        │   Device Server (POS-Server) ────┘ ←────────────────┘      │
                 │        │   (단말 연결·매출 원본·운영)  ←── BO (기준정보·관제)       │
                 │        │         ↑ WebSocket                                  │      │
                 │        └─────────┼──────────────────────────────────────────┼──────┘
                 │                  │                                          │
  매장 ──────────┴──────────────────┼────────────────────── APP-SERVER 가 구독 ─┘
    카운터   POS (Electron) ────────┤                       → 손님 앱에 푸시
    무인     KIOSK (Electron) ──────┤
    주방     KDS-MAIN (Electron) ───┤  ← 주방의 중심, 라벨 프린터
             KDS SUB (웹) ──────────┤
    홀       DID (웹) ──────────────┘
```

**매장 안에 서버는 없습니다** (ADR-0010). 모든 단말은 중앙에 붙습니다.
KDS-MAIN 이 주방의 중심이지만 **결정권자**이지 **저장소**가 아닙니다.

---

## 2. 단말과 서버

| 무엇 | 형태 | 붙는 곳 | 하는 일 |
|---|---|---|---|
| **POS** | Electron | Device Server | 주문 수락·거절, 거래번호 채번, **로컬 원장**, VAN, 영수증·돈통 |
| **KIOSK** | Electron | Device Server | 손님이 스스로 주문·결제. **캐셔 없는 POS** — 자기 번호로 거래를 끊음 |
| **KDS-MAIN** | Electron | Device Server | **주방의 중심** — 전체 흐름, 수령, 다시 부르기, 라벨 출력·재출력, KDS SUB·DID 명령 |
| **KDS SUB** | 웹 | Device Server | 자기 줄만 보고 만듦 |
| **DID** | 웹 | Device Server | 부른 번호를 보여줌 |
| **손님 웹** (QR) | 웹 | channel-order | 테이블 QR 주문, 영수증 QR 로 상태 조회 |
| **손님 앱** | 모바일 (Capacitor) | channel-order | 웹과 같은 화면 + 푸시, 회원 기억, 주문 이력 |
| channel-order | Spring, 공개 | APP-SERVER, OMS | 손님 창구. 손님이 아는 유일한 주소 |
| APP-SERVER | Spring, LAN | OMS, BO(fdw) | 손님 주문 원장, 결제, 회원번호, **손님 푸시** |
| OMS (Order) | Spring | — | **사실이 적히는 곳** — 주문, 영업일, 수령번호 |
| Device Server | Netty | OMS, APP-SERVER, BO(fdw) | 단말 연결, 매출 원본·분해, 개점·마감, Live Check, 배포, 공지, **라벨 작업**, **기기 인증** |
| BO | Spring, LAN | Device Server, OMS | 기준정보(사람이 고치는 것), 관제 화면 |

---

## 3. 주문 한 건의 일생

### 손님 앱 / QR 웹

```
손님 ─ 장바구니 ─→ channel-order ─→ APP-SERVER
                                   ├ 결제수단 켜져 있나 (BO 기준정보)   ← 꺼져 있으면 여기서 끝
                                   ├ 원장에 적음
                                   ├ 결제 승인 (썬앤문페이)
                                   └→ OMS: PLACED ─ order-events ─→ Device Server ─→ POS
POS 수락 ─→ OMS: ACCEPTED (거래번호 = POS 가 채번, 로컬 원장에 기록 → 30초마다 POS-Server 로)
                         ─→ KDS SUB 들이 자기 줄을 봄, KDS-MAIN 은 전체를 봄
KDS SUB 각자 자기 줄 완료 ─→ 모든 줄이 끝나면 OMS: PRODUCED (첫 호출 자동)
                         ─→ DID 가 번호를 띄움, KDS-MAIN 이 라벨 출력
                         ─→ APP-SERVER 가 order-events 를 듣고 손님 앱에 "준비됐습니다" 푸시
KDS-MAIN 이 수령 확인 (PIN, 또는 표시된 강제 수령) ─→ OMS: COMPLETED
```

### KIOSK

KIOSK 는 **캐셔 없는 POS**입니다. 손님이 이미 결제했으므로 POS 의 수락을
기다리지 않습니다:

```
손님 ─ KIOSK 에서 주문·결제 ─→ KIOSK 가 자기 POS번호로 거래번호 채번 (로컬 원장)
                           ─→ Device Server ─→ OMS: 수락된 상태로 생성 (acceptedBy = kiosk-01)
                           ─→ 이후는 위와 같음 (KDS → DID → 수령)
```

### POS 대면 주문

카운터에서 받은 주문도 KIOSK 와 같은 길입니다 — 받는 사람이 POS 일 뿐.
(구현은 매장 단말 앱 설계의 후반 단계)

---

## 4. 상태마다 주인이 하나

Sun&Moon: *"조리 완료 상태, 픽업 등 다양한 상태를 전달할 때 중심점이 생긴다."*

| 상태 | 주인 | 서버가 확인하는가 |
|---|---|---|
| 주문 접수 | 채널 → OMS 가 적음 | ✅ |
| 수락 / 거절 | POS (KIOSK 는 스스로) | ✅ |
| 조리 시작 (내 줄) | KDS SUB | 설계 — 지금은 SUB 가 주문 전체를 움직임 |
| 조리 완료 (내 줄) | KDS SUB | ✅ 줄 단위 |
| 주문 전체 조리 완료 | 자동 — 모든 줄이 끝나면 | ✅ |
| 강제 전부 완료 | KDS-MAIN (표시된 우회로) | 설계 |
| 호출 (DID) | 첫 호출 자동 · 다시 부르기 KDS-MAIN | 설계 — 지금은 화면에서만 |
| 수령 | KDS-MAIN (MAIN 이 끊기면 비상 수령을 SUB 에) | 설계 — 지금은 화면에서만 |
| 라벨 출력·재출력 | KDS-MAIN | 설계 |
| 취소 | 손님 (APP) · 매장 (POS) | 부분 |

**"설계" 인 줄은 지금 서버가 KDS-MAIN 을 모르기 때문입니다.** MAIN 은 화면의
`sessionStorage` 값일 뿐이라, 서버는 어느 KDS 의 요청이든 받습니다. 매장
단말 앱 설계 §4 (기기 인증과 역할)가 이걸 풉니다.

---

## 5. 명령의 두 층

| 층 | 누가 | 무엇 | 받는 쪽 |
|---|---|---|---|
| **매장 운영** | **KDS-MAIN** | 다시 부르기, DID 새로고침, KDS SUB 관리, 라벨 재출력, 강제 완료, 수령 | 주방 쪽 단말 |
| **본사** | BO | 공지·긴급, 프로그램 배포, 마스터 배포, 결제수단·기준정보 | 모든 단말 |

**주방 쪽 단말에 명령하는 주인은 KDS-MAIN 하나**입니다. 서버는 전달만 합니다.
다른 단말이 KDS SUB 나 DID 에 명령하는 길은 두지 않습니다.

---

## 6. 이벤트의 길

| 채널 | 누가 냄 | 누가 들음 | 무엇에 쓰나 |
|---|---|---|---|
| Redis `order-events` | OMS | Device Server | 단말에 push (POS·KDS·DID) |
| Redis `order-events` | OMS | **APP-SERVER** (새로) | 손님 앱 푸시 |
| Redis `menu-events` | BO | Device Server | 결품·메뉴 바뀜 → 단말이 다시 읽음 |
| WebSocket | Device Server | 단말 | "바뀌었다" 는 신호 + 긴급 공지 + **라벨 작업** |

이벤트는 **바뀌었다는 신호**입니다. 받은 쪽은 무엇이 참인지 다시 물어봅니다
(OMS 가 사실의 주인). 신호를 믿고 화면을 그리지 않습니다.

`order-events` 에는 `customerId` 가 실려 있어서, 회원 주문이면 그 값이 회원번호
입니다 — APP-SERVER 가 **Order 를 안 고치고** 손님을 찾을 수 있습니다.

---

## 7. 사실이 적히는 곳

| 어디 | 무엇 |
|---|---|
| `order_service` | 주문, 영업일, 수령번호, 주문 줄 |
| `app_server` | 손님 원장, 결제·환불, 회원번호, **앱 기기(푸시 토큰)** |
| `pos_server` | 매출 원본(TR·ETC)과 분해, 개점·마감, Live Check, 배포, 공지, **라벨 작업**, **기기 인증** |
| `bo_service` | 기준정보 — 매장, 메뉴, 가격, 장비(+KDS 역할), 결제수단, 공통코드 |
| `delivery_service` | 배달 |
| **POS · KIOSK 로컬 SQLite** | **그 단말 거래의 원본** (ADR-0010). 서버는 올라온 것을 모읍니다 |

**백업은 없습니다** — 정책만 `Debian-Setting/docs/backup.md`.

---

## 8. 흔들리지 않는 원칙

이 흐름을 고칠 때 지켜 온 것들입니다. 새 기능이 이것과 부딪히면 먼저 멈추고
ADR 을 쓰십시오.

1. **매장에 서버는 없다.** 단말끼리 매장 LAN 에서 직접 이야기하지 않습니다.
   KDS-MAIN 도 명령만 하고 품지 않습니다.
2. **사실은 OMS 가 적는다.** 단말은 요청하고, 이벤트는 신호일 뿐입니다.
   예외는 거래 원본 — POS·KIOSK 의 로컬 원장이 원본입니다.
3. **원본은 그대로 담고, 나중에 푼다.** 실패하면 멈추고 사람을 기다린다
   (배치가 다시 집어 가지 않는다 — 2026-09-18 의 388번).
4. **모르는 것과 없는 것은 다르다.** 목록을 못 읽으면 막지 않고 크게 경고한다
   (단말 인증, 결제수단).
5. **매장 화면에 막다른 길을 두지 않는다.** 필수 입력 대신 **표시된 우회로**
   (강제 수령, 강제 완료, 비상 수령) — 그리고 우회로는 기록에 남는다.
6. **명령의 주인은 하나.** 주방은 KDS-MAIN, 본사는 BO.
