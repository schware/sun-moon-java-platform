# 전체 흐름 — 지금 어떻게 동작하는가

**2026-09-29 기준, 실제로 돌고 있는 모습**입니다(9/27 에 Sun&Moon 과 정리한 그림을 구현한 뒤).
결정의 이유는 ADR 에 있고 — 특히 [0010](adr/0010-the-pos-terminal-holds-state-without-a-store-server.md),
[0013](adr/0013-pos-server-becomes-a-system-of-record.md),
[0015](adr/0015-store-terminals-electron-where-the-hardware-is.md) — 구현 설계와 단계별 진행은:

- [`design/store-app.md`](design/store-app.md) — 매장 단말 앱 (POS · KIOSK · KDS-MAIN, Electron). **0~7단계 구현**
- [`design/customer-app.md`](design/customer-app.md) — 손님 앱 (웹 · 모바일 · 키오스크 화면). **A0·A2·K 구현**

표에서 ✅ 는 **서버에 올라가 있고 확인한 것**, 🔶 는 구현했지만 켜지 않았거나 실물로 확인하지 못한 것입니다.

---

## 1. 한 장 그림

```
                        ┌──────────────────── 중앙 (192.168.0.2) ─────────────────────┐
  손님                   │                                                             │
  ├─ QR 웹 · 앱 ────────→ │  channel-order :8085 ─→ APP-SERVER :8086 ─┐                  │
  │   (공개)              │  (손님 창구, CORS)       (손님 원장·결제)  │                  │
  │                       │                                            ↓                  │
  │                       │                      OMS (Order) :8083 ── Redis order-events ─┐
  │                       │                      (사실이 적히는 곳, LAN 전용)             │
  │                       │                            ↑                                  │
  │                       │  Device Server :8087 ──────┘ ←────────────────────────────────┘
  │                       │  (단말 연결·인증·매출 원본·라벨·운영)   ←── BO :8080 (기준정보·관제, LAN)
  │                       │         ↑ HTTP + WebSocket (매장·장비 ID)                      │
  │                       └─────────┼─────────────────────────────────────────────────────┘
  매장                              │
    카운터  POS ───────────── 셸 ───┤   셸 = SunMoon Store (Electron, sun-moon-store-app)
    무인    KIOSK ─────────── 셸 ───┤        원장(SQLite)·프린터·업데이트를 가진 단말
    주방    KDS-MAIN ──────── 셸 ───┤   ← 주방의 중심, 라벨 프린터
            KDS SUB ───────── 웹 ───┤
    홀      DID ───────────── 웹 ───┘
```

**매장 안에 서버는 없습니다**(ADR-0010). 모든 단말은 중앙의 Device Server 하나에 붙습니다.
KDS-MAIN 은 주방의 **결정권자**이지 저장소가 아닙니다.

---

## 2. 단말과 서버

| 무엇 | 형태 | 붙는 곳 | 하는 일 |
|---|---|---|---|
| **POS** | 셸 (또는 웹) | Device Server | 주문 수락·거절, 거래번호 채번, **로컬 원장**, 매출 올리기, 영수증·돈통 |
| **KIOSK** | 셸 | Device Server | **캐셔 없는 POS** — 손님이 담고 결제, 자기 번호로 채번, 수락된 채로 주방으로 |
| **KDS-MAIN** | 셸 (또는 웹) | Device Server | 주방의 중심 — 전체 흐름, 수령, 다시 부르기, 강제 전부 완료, **라벨** |
| **KDS SUB** | 웹 | Device Server | 자기 줄만 시작·완료. MAIN 이 3분 넘게 끊기면 비상 수령 |
| **DID** | 웹 | Device Server | 부른 번호를 보여줌. 여러 매장을 한 화면에 가능 |
| **손님 웹** (QR) | 웹 | channel-order | 테이블 QR 주문, 영수증 QR 로 주문 상태 조회 |
| channel-order | Spring, **공개** | APP-SERVER, OMS | 손님 창구. 손님 앱 출처에만 CORS |
| APP-SERVER | Spring, LAN | OMS, BO(fdw) | 손님 주문 원장, 결제 승인, 회원번호 |
| OMS (Order) | Spring, **LAN** | — | **사실이 적히는 곳** — 주문, 영업일, 수령번호, 줄 단위 상태, 결제 |
| Device Server | Netty, **공개** | OMS, APP-SERVER, BO(fdw) | 단말 연결, **기기 인증**, 매출 원본·분해, 개점·마감, 배포, 공지, **라벨 작업**, KDS-MAIN 권한 |
| BO | Spring, LAN | Device Server, OMS | 기준정보(장비·**KDS 역할**·결제수단 등), KIOSK 관리자 PIN, 관제·재처리 |

공개 여부 확인은 LTE 로만 합니다(NAT 루프백). OMS 는 2026-09-28 에 닫았습니다.

---

## 3. 기기 신원 — 매장·장비 입력

> **2026-10-01 Sun&Moon 결정: 토큰은 제외. KDS·DID 의 짝은 STORE-ID.** 페어링(코드 → 기기
> 토큰)은 같은 날 코드에서 걷어냈습니다(Device Server V9 가 표를 지움).

- 단말은 첫 화면의 **단말 등록**(매장 ID · 장비 ID, KDS 는 생산/메인)으로 시작합니다. 셸(POS·KIOSK·
  KDS-MAIN)도 첫 실행 때 같은 값을 넣습니다(`config.json`).
- 요청에는 매장·장비 ID 를 적어 보냅니다. WebSocket 연결 때 Device Server 가 **BO 장비 목록**에
  (매장, 장비, 종류)가 있는지 확인합니다(`DEVICE_DIRECTORY=bo`).
- 같은 매장의 KDS 와 DID 가 함께 움직입니다 — 주문 신호와 목록이 매장 단위라 따로 묶는 설정이 없습니다.
- **KDS 의 MAIN / SUB 는 BO 장비 화면의 "KDS 역할"이 정합니다**(예: `kds-01` MAIN, `kds-02` SUB).
  KDS 가 연결하면 Device Server 가 BO 명단에서 역할을 찾아 연결 인사(WELCOME)로 알려 주고, 화면은
  그 역할을 따릅니다(단말 등록에서 고른 것과 달라도). 라벨 작업(PRINT)은 MAIN 연결로 가고, 한 매장에
  MAIN 은 하나(409). BO 명단을 모를 때만 화면이 고른 역할을 씁니다.

### 주소마다 청중 (`ApiGuard`)

| 청중 | 증명 | 예 |
|---|---|---|
| 공개 | 없음 | `/health`, `/pos/time`, `/metrics`, **단말이 부르는 주소 전부**(`/orders…`, `/sales`, `/menus`, `/pos/print-jobs…`, `/orders/kiosk`, `/pos/kiosk/unlock`) |
| 관리 | `X-Admin-Token` (BO 만 가짐) | `/terminals…`, `/pos/notices`(등록), `/pos/programs/release`, `/reprocess…`, `/pos/devices/kiosk-pin` |

표에 없는 주소는 **관리**로 봅니다(잊으면 닫히는 쪽으로). 시험이 Bootstrap 의 주소를 전부 읽어
청중이 정해졌는지 확인합니다. 지금 모드는 **`API_AUTH=enforce`**(2026-11-09) — 닫히는 것은 관리 주소뿐이고, 토큰 없는 관리 요청은 401 입니다.

---

## 4. 주문 한 건의 일생

### 손님 앱 / QR 웹 ✅

```
손님 ─ 장바구니 ─→ channel-order ─→ APP-SERVER
                                   ├ 결제수단 켜져 있나 (BO)          ← 꺼져 있으면 409, 원장에 안 적음
                                   ├ 원장에 적고, 결제 승인 (썬앤문페이)
                                   └→ OMS: PLACED  (결제 {method, approvalNo, amount} 가 주문에 실림)
          order-events ─→ Device Server ─→ POS   (POS 가 없는 매장이면 그 자리에서 거절)
POS 수락 ─→ 로컬 원장에서 거래번호 채번 ─→ OMS: ACCEPTED
         ─→ 원장 확정 → 거래 전문(결제 포함) → POST /sales → 30초마다 분해 (tr_sale_*)
KDS SUB "가져가기" ─→ 자기 줄만 시작 (주문은 한 줄이라도 시작되면 COOKING)
KDS SUB "조리완료" ─→ 자기 줄만 완료. 모든 줄이 끝나면 OMS: PRODUCED (첫 호출 자동)
                   └ KDS-MAIN "전부 조리완료" = 강제 완료 (남은 줄 전부, 누가 눌렀는지 남음)
PRODUCED ─→ DID 가 번호를 띄움
         ─→ 라벨 작업: 줄 × 수량 (라벨 프린터가 있는 매장만) ─→ KDS-MAIN 셸이 찍음
KDS-MAIN 수령 확인 (PIN, 또는 표시된 강제 수령) ─→ OMS: COMPLETED
         └ MAIN 이 3분 넘게 끊기면 생산 KDS 가 비상 수령 (pickup_emergency 로 남음)
```

### KIOSK ✅ (2026-09-29 끝까지 확인)

```
손님 ─ KIOSK 에서 담기 ─→ 결제 (셸, 가상 승인 SMK-…)
     ─→ 셸 원장에서 자기 번호로 채번
     ─→ POST /orders/kiosk ─→ Device Server 가 메뉴판에서 값을 다시 매김 (화면 값 안 믿음)
                           ─→ OMS POST /orders/accepted: 수락된 채로 생성 (acceptedBy = 그 KIOSK)
                              이벤트는 ACCEPTED 한 번 — POS 수락 대기에 안 뜨고 바로 주방으로
     ─→ 원장 확정 → 매출 올리기 → 영수증 출력(QR) → 번호 크게 → 15초 뒤 처음으로
```

KIOSK 는 WebSocket 을 쓰지 않습니다. 60초 손을 안 대면 처음으로, 회원번호는 입력만.

**관리자 탈출**: 화면 왼쪽 위 구석을 3초 안에 5번 → 셸의 관리자 창 → **관리자 PIN**(BO 장비 화면에서
정함, 서버에는 해시만) → 셸이 서버에 물어 확인 → 2분 동안 창 모드 · 앱 끄기 · 단말 등록 지우기.
기기마다 10분에 5번 틀리면 10분 막힘. 서버에 닿지 않으면 풀리지 않습니다(그때는 Windows 쪽으로).

### 회원 — 번호와 토큰 ✅ (A2)

```
번호 받기 ─→ 회원번호(11자리, 비밀 아님) + 이 기기의 회원 토큰
주문에 번호를 붙임 ─→ 원장·OMS 의 손님 번호
"내 주문" ─→ 토큰이 있는 기기만 (번호만으로는 안 보임 — 남의 번호를 치면 남의 주문이 보이므로)
새 기기 ─→ 그 번호로 주문한 영수증 QR 화면에서 번호를 넣음
         ─→ APP-SERVER 가 OMS 에 "이 영수증의 손님" 을 물어(LAN 전용) 맞으면 토큰
            같은 번호로 10분에 5번 틀리면 1시간 막힘
```

---

## 5. 상태마다 주인이 하나

| 상태 | 주인 | 서버가 지키는가 |
|---|---|---|
| 주문 접수 | 채널 → OMS | ✅ |
| 수락 / 거절 | POS (KIOSK 는 스스로) | ✅ |
| 조리 시작 (내 줄) | KDS SUB | ✅ 줄 단위 (`started_at`) |
| 조리 완료 (내 줄) | KDS SUB | ✅ 줄 단위 |
| 주문 전체 완료 | 자동 — 모든 줄이 끝나면 | ✅ |
| 강제 전부 완료 | KDS-MAIN | ✅ 서버가 역할 확인, `force_produced_by` |
| 다시 부르기 | KDS-MAIN | ✅ 서버가 역할 확인 |
| 수령 | KDS-MAIN (비상 시 생산 KDS) | ✅ 서버가 역할 확인, `pickup_emergency` |
| 라벨 출력·재출력 | KDS-MAIN | ✅ 재출력은 MAIN 만 |
| 취소 | 손님 (APP) · 매장 (POS) | 부분 |

메인의 일(다시 부르기·강제 완료·수령·라벨 다시)은 **서버도 확인합니다** — 요청의 장비가 그 주문 매장의
BO 명단에서 MAIN 이어야 합니다(`KdsAuthority`). 명단을 모르거나 명단에 없는 KDS 는 막지 않습니다.
BO 에 MAIN 이 정해진 매장에서 그 MAIN 이 **3분 넘게 끊기면** 생산 KDS 에 수령 칸이 열리고, 그 수령은
**비상 수령**(`pickup_emergency`)으로 남습니다. BO 매출 조회에는 강제 완료·비상 수령·번호 확인 없는 수령이
**같은 자리에 배지**로 보입니다.

---

## 6. 명령의 두 층

| 층 | 누가 | 무엇 | 받는 쪽 |
|---|---|---|---|
| **매장 운영** | **KDS-MAIN** | 다시 부르기, 라벨 재출력, 강제 완료, 수령 | 주방 쪽 단말 |
| **본사** | BO | 공지·긴급, 프로그램·화면 배포, 마스터 배포, 결제수단·장비 | 모든 단말 |

KDS-MAIN 은 **매장에 하나** — 같은 매장에 두 번째 MAIN 이 붙으면 409.

---

## 7. 셸 (SunMoon Store) — 하드웨어가 있는 자리 🔶

`sun-moon-store-app`(private). POS · KIOSK · KDS-MAIN 만 셸로 엽니다(SUB·DID 코드는 "웹에서").

| 무엇 | 어떻게 |
|---|---|
| 화면 | 각 단말 저장소의 빌드를 `sunmoon://app/<화면>/` 으로. KIOSK 는 손님 화면을 `/channel-order/` 로 |
| 가두기 | contextIsolation · sandbox · Node 없음, 셸 밖 이동·새 창 금지, **CSP**(스크립트는 셸 안, 연결은 Device Server 하나), 권한 요청 거절, IPC 채널 목록 고정 |
| 원장 | `%APPDATA%/SunMoon Store/ledger.sqlite` (Node 내장 sqlite, WAL). POS·KIOSK 만. 매장·단말은 기기 신원으로 적음 |
| 프린터 | ESC/POS over TCP 9100, CP949. **어디로 찍을지는 셸이 정함** — BO 장비(`RECEIPT_PRINTER`/`LABEL_PRINTER`) 중 그 매장 것만 |
| 늘 켜짐 | 로그인 시 시작, 화면이 죽으면 다시(1분 3번 넘으면 멈춤), 새벽 4시 새로 띄움, 두 번 켜면 첫 창으로 |
| 결제 | KIOSK: 가상 승인 `SMK-yyyyMMdd-XXXXXX`(서울 날짜). VAN 이 정해지면 main 만 바뀜 |
| 관리자 탈출 | KIOSK: 구석 5번 → 관리자 창 → PIN(서버가 확인) → 창 모드·끄기·단말 등록 지우기 |
| 업데이트 | 화면 번들: **서명(ed25519)** 확인 → 받아 두고 **다음 시작 때** 적용 → 30초 안에 죽으면 되돌림. **기본 꺼짐** |

설치 파일은 `npm run dist` → `release/SunMoon Store Setup 0.1.0.exe`(약 115MB, **서명 없음**). 2026-09-29 에
만들어 포장된 앱으로 점검했습니다. **실물 프린터로는 아직 확인하지 않았습니다**(가짜 프린터로 라벨 4장).
창을 띄우지 않는 점검은 `SUNMOON_SMOKE=1` — README 참고.

---

## 8. 라벨 ✅ (가짜 프린터로 확인)

```
OMS: PRODUCED (이전 상태가 PRODUCED 가 아닐 때 — 다시 부르기 제외)
  ─→ Device Server: 주문을 읽어 줄 × 수량만큼 print_job (content 는 그 순간 고정, 한 주문 최대 60장)
  ─→ 그 매장 KDS-MAIN 에 WS {"type":"PRINT","jobIds":[…]}
KDS-MAIN 셸 ─ 작업을 읽고 ─→ 라벨 프린터 ─→ ack (PRINTED / FAILED)
MAIN 이 꺼져 있었으면: 켜질 때 QUEUED 를 한 번에
[라벨 다시] ─→ content 를 복사한 새 작업 (copyNo 1, 2 …, "재출력 N" 이 찍힘)
FAILED ─→ BO 재처리의 다섯 번째 종류 PRINT ─→ 다시 줄 세우고 MAIN 에 알림
```

---

## 9. 이벤트의 길

| 채널 | 누가 냄 | 누가 들음 | 무엇에 쓰나 |
|---|---|---|---|
| Redis `order-events` | OMS | Device Server | 단말에 push (POS·KDS·DID), **라벨 작업 만들기** |
| Redis `order-events` | OMS | APP-SERVER (A4, **꺼져 있음**) | 손님 앱 푸시 — `PUSH_EVENTS_REDIS`·FCM 설정이 있어야 |
| Redis `menu-events` | BO | Device Server | 결품·메뉴 바뀜 → 단말이 다시 읽음 |
| WebSocket | Device Server | 단말 | "바뀌었다" 신호, 긴급 공지, **PRINT** |

이벤트는 **바뀌었다는 신호**입니다. 받은 쪽은 무엇이 참인지 다시 묻습니다(OMS 가 사실의 주인).

---

## 10. 사실이 적히는 곳

| 어디 | 무엇 |
|---|---|
| `order_service` (V16) | 주문·줄(시작·완료 시각), 영업일, 수령번호, 강제 완료·비상 수령, **결제** |
| `app_server` (V4) | 손님 원장, 결제·환불, 회원번호, **회원 기기(토큰 해시·푸시 토큰)**, 주문의 영수증 토큰 |
| `pos_server` (V9) | 매출 원본(TR·ETC)과 분해, 개점·마감, 배포, 공지, **라벨 작업**, **KIOSK 관리자 PIN(해시)** |
| `bo_service` | 기준정보 — 매장, 메뉴, 장비(+KDS 역할, 프린터 IP·포트), 결제수단 |
| **POS · KIOSK 셸 SQLite** | **그 단말 거래의 원본** (ADR-0010). 서버는 올라온 것을 모읍니다 |

**백업은 없습니다** — 정책만 `Debian-Setting/docs/backup.md`.

---

## 11. 켜는 스위치

| 스위치 | 어디 | 지금 | 켤 때 |
|---|---|---|---|
| `API_AUTH` | macpro `/srv/sunmoon/env/device.env` | **`enforce`** (2026-11-09) | — 관리 주소만 닫힘. 코드 기본값은 `warn` 이라 env 를 빠뜨리면 다시 열립니다 |
| `ADMIN_TOKEN` / `DEVICE_SERVER_ADMIN_TOKEN` | 위 파일 / BO `.env` (같은 값) | 설정됨 | 교체는 `secrets.md` G |
| `DEVICE_DIRECTORY` | Device Server run 줄 | `bo` | — |
| `APP_CORS_ORIGINS` | channel-order | 기본값(`https://localhost, capacitor://localhost`) | 앱 출처가 바뀌면 |
| `SUNMOON_UI_UPDATES` | 셸 환경 | 꺼짐 | `API_AUTH=enforce` 가 켜졌으므로 이제 `on` 가능 |
| `PUSH_EVENTS_REDIS`, `FCM_PROJECT_ID`, `FCM_CREDENTIALS_FILE` | APP-SERVER run 줄 | 없음(푸시 꺼짐) | Firebase 프로젝트와 앱(A3)이 생기면 — `secrets.md` I |

Device Server 를 다시 띄울 때 **`--env-file sun-moon-device.env` 를 빼먹으면** 인증이 조용히 기본값이
됩니다 — `bin/docker-start-all.sh` 의 줄을 그대로 쓰십시오.

---

## 12. 남은 것

- ~~`API_AUTH=enforce`~~ — 2026-11-09 켬(관리 주소만 닫힘, Sun&Moon 결정)
- **실물 프린터** 확인, 코드 서명 인증서(그 뒤 셸 자체 업데이트)
- 진짜 VAN (그때 KIOSK 는 결제수단 확인을 승인 **전**으로)
- 손님 앱 A3(Android 껍데기 — Android SDK 필요) · A4 앱 쪽(푸시 권한·토큰 등록 — Firebase 프로젝트 필요).
  A4 **서버 쪽은 구현**돼 있고 설정이 없어 꺼져 있습니다
- 단말 코드 공통화(8단계, 선택) — 지금 `pairing.ts` 는 세 저장소에 같은 복사본

---

## 13. 흔들리지 않는 원칙

1. **매장에 서버는 없다.** KDS-MAIN 도 명령만 하고 품지 않습니다.
2. **사실은 OMS 가 적는다.** 단말은 요청하고, 이벤트는 신호일 뿐. 예외는 거래 원본 — POS·KIOSK 원장.
3. **원본은 그대로 담고, 나중에 푼다.** 실패하면 멈추고 사람을 기다린다.
4. **모르는 것과 없는 것은 다르다.** 목록을 못 읽으면 막지 않고 크게 경고한다.
5. **매장 화면에 막다른 길을 두지 않는다.** 표시된 우회로(강제 수령·강제 완료·비상 수령)
   — 그리고 우회로는 기록에 남는다.
6. **명령의 주인은 하나.** 주방은 KDS-MAIN, 본사는 BO.
7. **화면을 믿지 않는다.** 값(가격)은 서버가 메뉴판에서, 프린터 주소는 셸이 BO 장비에서.
