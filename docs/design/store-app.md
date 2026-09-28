# 설계 — 매장 단말 앱 (POS · KIOSK · KDS-MAIN, Electron)

- 근거: [ADR-0015](../adr/0015-store-terminals-electron-where-the-hardware-is.md), [FLOW.md](../FLOW.md)
- 작성: 2026-09-27
- **이 문서는 구현용입니다.** 열린 질문은 전부 기본값으로 결정해 두었고,
  바꿀 자리는 `▶ 기본값` 으로 표시했습니다. 단계마다 **바꾸는 곳 · 계약 ·
  확인**이 있으니, 한 단계씩 끝내고 확인까지 한 뒤 다음으로 가십시오.

## 진행 (2026-09-28)

| 단계 | 상태 | 커밋 |
|---|---|---|
| **0** 인증 `warn` | ✅ 배포·확인. 관리 토큰은 서버 파일에만(`Debian-Setting/docs/secrets.md` G) | Device Server `0f3843b`, BO `067503a` |
| **1** 페어링 | ✅ 서버·BO·단말 3개 구현·배포. 서버 안에서 발급→페어링→사용→해제 확인 | Device Server `3395e00`, BO `de4c91f`, KDS `cacff64`, DID `c1948f6`, POS `2a2f68e` |
| **2** KDS-MAIN 권한 | ✅ 구현. Order V15 배포, Device Server·BO·KDS 배포 중 (아래 확인 참고) | Order `c850d98`, Device Server `c448f09` `c47b2a0`, BO `6b85b1e`, KDS `783e295` |
| **3** Electron 셸 | ✅ 구현. 설치 파일은 아직 안 만듦(`npm run dist`). 창 없는 점검(`SUNMOON_SMOKE`)으로 공인 주소 페어링→암호화 저장→화면이 토큰으로 `/orders` 200→외부 fetch 차단까지 확인 | 셸 `0c88cc8` (새 저장소 `sun-moon-store-app`, private), 단말 KDS `6e2e8b0` POS `bb58eee` DID `466fcee` |
| **4** 원장·결제 | ✅ §6.2 배포(Order V16, APP-SERVER, POS). §6.1 셸 SQLite 원장 구현 — 창 없는 점검으로 확인 | Order `7b23e38`, APP-SERVER `0d7dc2b`, POS `84e6c50` `cdef65b`, 셸 `73c96de` |
| **5** 프린터·라벨 | ✅ 배포. 시험 매장으로 끝까지 확인(2026-09-28): 아메리카노 3 + 라떼 1 → 강제 완료 → 라벨 작업 4개 QUEUED → 셸(KDS-MAIN)이 가짜 프린터로 4장(CP949, "2 / 3") → PRINTED. 재출력은 MAIN 만(POS 403), content 복사 + copyNo 1, 2. 재처리에 PRINT. **실물 프린터는 아직** | Device Server `778f3cb` `0db98e6`, 셸 `43a1e46`, KDS `a6ae52d`, POS `dcd31ec`, Debian-Setting `6307583` |
| **6** 화면 번들 업데이트 | ✅ 구현, **기본 꺼짐**(`SUNMOON_UI_UPDATES=on`) — §11 대로 enforce 뒤에 켬. 서명 키는 빌드 PC 에만(secrets.md H). 셸 자체 업데이트(§8.2)는 코드 서명 뒤 | 셸 `7edb66f`, Debian-Setting `5df0969` |
| 7~8 | 아직 | — |

**설계와 달라진 것 (구현하면서 정한 것):**

- **`/metrics` 는 공개**입니다(§2.2 는 "localhost 만 토큰 없이"). 엔드포인트가
  요청만 받고 출발지 주소를 모릅니다. 지표는 기계의 사실이라 공개로 두었습니다.
- **페어링 무차별 대입 제한은 서버 전체**입니다(§3.4 는 "같은 IP"). 같은 이유 —
  1분에 20번 실패하면 그 1분 동안 페어링 전체를 막습니다. 공격자가 페어링을 잠깐
  막을 수는 있지만 코드를 맞힐 수는 없습니다.
- **`device_pairing_code` 의 키가 코드가 아닙니다**(id). 6자리는 시간이 지나면
  다시 나오므로, 기록을 남기려면 코드가 키일 수 없습니다. 살아 있는 코드끼리만
  안 겹치게 발급할 때 확인합니다.
- **fdw 뷰(`v_bo_devices`)에 `kds_role` 을 안 넣었습니다.** 역할은 BO 가 코드를
  받을 때 Device Server 에 직접 넘기므로 뷰가 필요 없습니다 — sudo 작업이 하나 줄었습니다.
- **웹 KDS 도 아직 MAIN 으로 페어링됩니다**(§3.6 은 "웹은 MAIN 불가"). KDS-MAIN
  Electron(3단계)이 없는 지금 막으면 MAIN 이 어디에도 없습니다. **3단계에서 막으십시오.**
- **토큰 없이 붙는 단말은 warn 에서 그대로 통과**합니다. 단, **토큰을 냈는데 모르는
  토큰이면 warn 에서도 거절**합니다 — 안 그러면 해제(revoke)가 뜻이 없습니다.
- 확인된 기기는 엔드포인트에서 `ApiGuard.currentDevice()` 로 읽습니다(§3.5 의
  "요청 attribute" 자리). **2단계의 역할 확인은 이것을 씁니다.**
- 웹 단말의 예전 직접 입력은 **"페어링 전 임시" 로 표시된 우회로**로 남겼습니다.
  서버가 `enforce` 가 되면 서버에서 막힙니다.
- (2단계) **역할 확인은 페어링된 기기에만** 적용됩니다. 토큰 없는 요청은 역할을 모르므로
  예전처럼 통과하고, `enforce` 가 되면 ApiGuard 가 먼저 막습니다. 페어링된 기기의
  위반은 모드와 상관없이 403 — 새로 생긴 기기라 막아도 멈출 매장이 없습니다.
- (2단계) **비상 수령 칸은 페어링된 SUB 에만** 뜹니다. 페어링 전 매장은 MAIN 도 토큰이
  없어서 서버가 "MAIN 없음" 으로 보고, 그대로 띄우면 지금 쓰는 모든 SUB 에 수령 칸이 생깁니다.
- (2단계) MAIN 이 마지막으로 보인 시각은 **5초마다 연결을 보고** 적습니다. 물을 때만
  보면 한 시간 붙어 있던 MAIN 이 끊긴 직후 "한 시간 전부터 없었다" 가 됩니다.
  재기동 직후는 기동 시각부터 셉니다.
- (2단계에 덧붙임) **`GET /orders` 는 페어링된 POS·KDS 에 자기 매장 주문만** 줍니다.
  그전에는 전 매장 주문이 단말로 가고 화면이 걸렀습니다. DID 는 여러 매장이라 그대로.
- 페어링된 기기가 **다른 매장의 주문 id** 로 상태 변경·수령·강제 완료 등을 부르면
  주문을 한 번 더 읽어 403 (Device Server `804d2ad`). **서버 반영은 다음 Device Server 배포 때.**
- (3단계) 화면은 `sunmoon://app/<화면>/` 으로 엽니다 — 번들이 `/pos/assets/…` 절대 경로라
  `file://` 로는 안 됩니다. 이 scheme 을 **secure 로 두지 않았습니다**: Device Server 가
  아직 http 라 secure 출처에서 부르면 mixed content 로 막힙니다. 단말 화면은 secure
  context 전용 API(`crypto.randomUUID` 등)를 쓰지 않는 것을 확인했습니다. TLS 가 생기면 secure 로.
- (3단계) 페어링은 셸의 **main 프로세스**가 합니다. 셋업 화면은 코드만 넘기고, 페어링 채널은
  셋업 화면 주소에서 온 것만 받습니다.
- (3단계) 셸이 생겼으므로 **웹 KDS 는 이제 MAIN 으로 페어링하지 않습니다**(§3.6 대로). 단,
  페어링 전 임시 입력(직접 입력)의 "메인" 선택은 아직 남아 있습니다 — 셸이 매장에 깔리기
  전까지의 우회로이고, `enforce` 가 되면 서버에서 막힙니다.
- (4단계) 셸 원장은 **`node:sqlite`**(Node 내장)입니다 — `better-sqlite3` 대신. 네이티브
  모듈 빌드가 없는 대신 Electron 이 Node 22.13+ 를 실어야 해서 **Electron 33 → 44** 로 올렸습니다.
- (4단계) **웹 POS 를 셸로 바꾸는 것은 마감 뒤에.** IndexedDB 는 출처마다 따로라 브라우저의
  웹 POS 원장은 셸(`sunmoon://app`)에서 보이지 않습니다. 설계의 "옮겨오기" 는 셸 안의
  IndexedDB(셸 0.1) 에서만 됩니다. 번호는 판매일자마다 1부터라 마감 뒤에 바꾸면 겹치지 않습니다.
- (4단계) 원장 채널은 매장·단말을 **화면이 보낸 값이 아니라 기기 신원**으로 바꿔 적습니다.
- (5단계) `v_bo_devices` 에 `ip_address, port` 를 더하는 것은 **sunmoon 으로** 됐습니다
  (sudo 불필요 — 컬럼을 끝에 더하는 CREATE OR REPLACE VIEW). 서버에 이미 반영.
- (5단계) **프린터 주소는 셸 main 이 정합니다** — Device Server 의 이 매장 프린터 목록(BO 장비)
  중에서만. 화면은 작업 번호·영수증 내용만 넘깁니다(화면이 준 주소로 연결하면 화면의 구멍이
  매장 PC 에서 아무 곳으로나 연결을 여는 길이 됨).
- (5단계) 프린터·라벨 주소는 **모드와 상관없이 페어링된 기기만** 부릅니다. 찍는 것은 셸이고
  셸은 페어링해야 뜹니다.
- (5단계) 한 주문의 라벨은 **최대 60장** — 수량 오타(999잔)로 프린터가 한 시간 도는 것을 막음.
- (5단계) 한국 모델 ESC/POS 의 2바이트 모드(`FS &`) + CP949 를 가정했습니다. 다른 프린터면
  `escpos.ts` 만 바꾸면 됩니다. **실물 프린터로는 아직 확인하지 않았습니다.**
- (6단계) 번들 형식은 zip 이 아니라 **gzip(JSON {screen, version, files})** 입니다 — zip 을
  풀려면 라이브러리가 필요하고, 셸 main 에 들어가는 의존성은 적을수록 좋습니다. 서명은 묶음
  바이트 전체에 대한 ed25519, checksum(SHA-256)도 따로 확인합니다.
- (6단계) 적용은 **셸이 시작할 때만**입니다. 새벽 4시 새로 띄우기는 화면만 다시 여는 것이라
  번들을 바꾸지 않습니다 — 영업 중에 화면이 바뀌는 일이 없습니다.
- (6단계) 받는 주소(packageUrl)의 호스트를 제한하지 않았습니다. 서명이 맞아야 풀리므로 어디서
  받든 같고, 제한하면 CDN 등으로 옮길 때 셸을 다시 깔아야 합니다.
- (3단계) 새벽 4시 화면 새로 띄우기는 **시각 기준**입니다(설계는 "마감 뒤"). 마감 여부를
  셸이 알려면 영업일 상태를 물어야 해서, 대부분 매장의 마감 뒤·개점 전인 시각으로 정했습니다.

---

## 0. 결정 요약

| 항목 | 결정 |
|---|---|
| Electron 단말 | POS · KIOSK · KDS-MAIN. **셸 하나 + 역할** |
| 웹 단말 | KDS SUB · DID (지금 방식) |
| 장비 | ▶ 기본값: **Windows 10/11 x64**. Electron 단말은 PC 에서만 돕니다 |
| 기기 인증 | 페어링 코드 → 기기 토큰. **웹과 앱이 같은 방식**, 보관 장소만 다름 |
| KDS 역할 | **BO 장비 마스터가 정함.** 단말은 스스로 고르지 않음 |
| 매장당 KDS-MAIN | 하나. 두 번째 연결은 서버가 거절 |
| MAIN 이 끊기면 | ▶ 기본값: **3분**(`TERMINAL_GRACE`) 뒤 SUB 에 **비상 수령**이 열림 (표시·기록됨) |
| 로컬 원장 | POS · KIOSK: SQLite (`better-sqlite3`). 웹 POS 는 IndexedDB 그대로(개발용) |
| 프린터 | ▶ 기본값: **네트워크 ESC/POS (TCP 9100)**. USB 는 나중 |
| 라벨 | ▶ 기본값: **조리 완료 시, 줄 단위, 수량만큼** (음료 3잔 = 3장). 재출력은 KDS-MAIN 만 |
| 업데이트 | 두 층 — 셸(드묾) / 화면 번들(잦음, **서명 검증**) |
| VAN | 인터페이스만. 구현은 VAN 사 정한 뒤. 그 전까지 썬앤문페이 |
| KIOSK | **캐셔 없는 POS** — 자기 POS번호로 채번, 수락된 채로 OMS 에 생성 |

---

## 1. 전체 구조

```
sun-moon-store-app/                 ← 새 저장소 (셸)
  src/main/            Electron main — 네이티브 기능 전부 여기
    index.ts           창, 역할, 자동 재시작
    config.ts          %APPDATA%/sun-moon/config.json (역할·서버 주소·기기 신원)
    identity.ts        페어링, 기기 토큰 (safeStorage 로 암호화 보관)
    ledger.ts          SQLite 원장          (POS, KIOSK)
    printer.ts         ESC/POS over TCP     (POS 영수증, KDS-MAIN 라벨)
    updater.ts         화면 번들 · 셸 업데이트
    ipc.ts             역할별로 열어 주는 IPC 채널 목록 — 여기 없는 것은 없다
  src/preload/
    index.ts           contextBridge — window.sunmoon 하나만 노출
  ui/                  화면 번들이 들어오는 자리 (업데이트가 채움)
    pos/  kiosk/  kds/
```

**화면은 기존 저장소 그대로입니다** — `sun-moon-terminal-pos`, `-kds`, 그리고
KIOSK 는 `sun-moon-java-platform-channel-order/frontend` (손님 화면 재사용,
[customer-app.md](customer-app.md) §5). 셸은 그 빌드 결과(`dist/`)를 싣고 돕니다.

**화면 코드는 셸이 있든 없든 돕니다.** 네이티브 기능은
`window.sunmoon?.ledger` 처럼 **있으면 쓰고 없으면 웹 방식**으로 갑니다. 그래야
개발할 때 브라우저로 그대로 띄울 수 있고, KDS 화면은 SUB(웹)와 MAIN(셸)이
같은 번들을 씁니다.

### 역할별로 열어 주는 기능

| `window.sunmoon.*` | POS | KIOSK | KDS-MAIN |
|---|---|---|---|
| `identity` (기기 신원·토큰) | ✅ | ✅ | ✅ |
| `ledger` (로컬 원장) | ✅ | ✅ | — |
| `printer.receipt` | ✅ | ✅ | — |
| `printer.label` | — | — | ✅ |
| `drawer` (돈통) | ✅ | — | — |
| `payment` (VAN) | 나중 | 나중 | — |
| `app` (버전·재시작) | ✅ | ✅ | ✅ |

역할에 없는 기능은 **노출 자체를 안 합니다** (`undefined`). 권한 검사가
아니라 존재하지 않는 것입니다.

---

## 2. 보안 바닥 — Electron 전에 반드시

### 2.1 Electron 설정 (고정, 바꾸지 마십시오)

```ts
new BrowserWindow({
  webPreferences: {
    contextIsolation: true,   // 화면과 preload 를 가름
    nodeIntegration: false,   // 화면에서 Node 금지
    sandbox: true,
    preload: path.join(__dirname, '../preload/index.js'),
  },
  kiosk: role === 'KIOSK',    // KIOSK 만 종료 불가 전체 화면
  fullscreen: true,
})
```

- 화면은 **로컬 파일(`ui/<역할>/index.html`)만** 엽니다. 외부 주소로 이동하는
  `will-navigate`, `new-window` 는 전부 막습니다.
- CSP: `connect-src` 는 Device Server 주소 하나, `script-src 'self'`.
- IPC 채널은 `ipc.ts` 의 목록에 있는 것만. 화면이 임의의 채널을 부를 수 없습니다.

**왜 고정인가**: 이 설정 하나가 빠지면 화면의 작은 구멍(XSS)이 **매장 PC
탈취**가 됩니다. 셸이 하나인 이유가 이걸 한 곳에서 지키기 위해서입니다.

### 2.2 ⚠️ Device Server API 인증 — 가장 먼저 (0단계)

**지금 Device Server(8087)는 HTTP 인증이 전혀 없고 공인으로 열려 있습니다.**
단말이 인터넷에서 붙어야 해서 열어 둔 것인데, 그 결과 인터넷의 누구든:

- `POST /pos/notices` — 전 매장 POS 에 긴급 메시지를 띄움
- `POST /business-days/close` — 매장을 마감시킴
- `POST /terminals/broadcast` — 단말에 아무 프레임이나 밀어 넣음
- **`POST /pos/programs/release` — 아무 파일 주소를 "배포" 로 등록**

마지막 것이 Electron 과 만나면 **원격 코드 실행**이 됩니다 — 셸이 그 기록을
보고 업데이트를 받는 순간 매장 PC 가 공격자의 코드를 돌립니다. **그래서 이
단계가 Electron 보다 먼저입니다.**

**주소를 부르는 쪽으로 셋으로 가릅니다:**

| 청중 | 확인 | 주소 |
|---|---|---|
| **공개** | 없음 | `GET /health`, `GET /pos/time` |
| **단말** | 기기 토큰 (§3) | `GET /orders`, `POST /orders/status·pickup·recall·received`, `POST /sales`, `POST /etc-sales`, `POST /pos/business`, `POST /pos/heartbeat`, `GET /pos/masters`, `POST /pos/masters/ack`, `GET /pos/programs`, `POST /pos/programs/ack`, `GET /pos/notices`, `POST /pos/notices/ack`, `GET /menus`, `GET /business-days/status`, `POST /business-days/open·close`, `WS /ws` |
| **관리 (BO)** | 관리 토큰 | `GET /terminals`, `POST /terminals/push·broadcast`, `GET /sales/totals`, `GET /etc-sales/totals`, `GET /pos/business`, `GET /pos/business/history`, `GET /pos/live`, `GET /pos/masters/pending`, `POST /pos/masters/version`, `GET /reprocess`, `POST /reprocess/one·all`, `POST /pos/programs/release`, `GET /pos/programs/status`, `POST /pos/notices`, `GET /pos/notices/all`, `GET /metrics`, (새) `POST /pos/pairing-codes`, (새) 라벨 관리 |
| 없앰 | — | `POST /orders/legacy` (옛 Netty 시절) — 쓰는 곳이 없으면 지움 |

**구현:**

- `RouteKey` 에 청중을 붙입니다: `Map.entry(new RouteKey(POST, "/pos/notices"), Audience.ADMIN, ...)`.
  디스패처가 청중에 맞는 확인을 먼저 하고 엔드포인트를 부릅니다.
- **관리 토큰**: 요청 헤더 `X-Admin-Token`. Device Server 는 env `ADMIN_TOKEN`,
  BO 는 env `DEVICE_SERVER_ADMIN_TOKEN` 을 `deviceServerClient` 의 기본 헤더로.
  **새 비밀입니다 — `Debian-Setting/docs/secrets.md` §1 표에 추가하십시오.**
- **단말 토큰**: 요청 헤더 `Authorization: Device <token>` (HTTP),
  WebSocket 은 핸드셰이크 쿼리 `token=` (브라우저가 WS 에 헤더를 못 붙임).
- **켜는 방식**: env `API_AUTH=off|warn|enforce`, ▶ 기본값 `warn`.
  `warn` 은 확인에 실패해도 통과시키고 **로그에 남깁니다**. 로그가 조용해진 뒤
  `enforce`. (단말 인증 `DEVICE_DIRECTORY` 를 켤 때와 같은 이유 — 모르는 채로
  막으면 전 매장이 조용히 멈춥니다.)
- `/metrics` 를 관리로 두면 Prometheus 가 못 긁습니다 → Prometheus 는 localhost
  에서 긁으므로, **localhost 에서 온 요청은 관리 토큰 없이 `/metrics` 만** 허용.

> ⚠️ **TLS 가 없습니다.** 토큰이 평문 HTTP 로 오갑니다. 같은 네트워크에서 엿보면
> 가져갈 수 있습니다. 토큰은 "인터넷의 아무나" 를 막는 것이고, TLS 는 도메인이
> 생긴 뒤의 일입니다(ADR-0007 "도메인 없음"). 그때까지의 한계로 알고 갑니다.

**확인:**
```bash
# 토큰 없이 — warn 이면 200 + 로그, enforce 면 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8087/pos/notices -d '{}'
# BO 화면(단말 공지, 배포 현황)이 enforce 에서도 그대로 동작
```

---

## 3. 기기 페어링과 역할 (1단계)

### 3.1 흐름

```
BO 장비 화면 ─ [페어링 코드 발급] ─→ Device Server: 6자리 코드, 10분 유효, 한 번만
                                        (그 장비의 매장·번호·종류·역할이 코드에 묶임)
단말 첫 화면 ─ 코드 입력 ─→ POST /pos/pair {code}
                         ←── {storeId, deviceId, type, role, token}
단말은 token 을 보관 (셸: safeStorage / 웹: localStorage)
이후 모든 요청: Authorization: Device <token>   WebSocket: ?token=
```

**단말이 매장·번호·역할을 고르지 않습니다.** 지금 KDS 화면의 "생산/메인" 선택,
POS 의 매장·번호 입력이 전부 사라지고 **코드 하나**가 됩니다. 역할은 BO 가
정한 것이 따라옵니다.

### 3.2 BO — 장비에 KDS 역할

`bo_service.devices` 에 컬럼 하나 (schema.sql, `ADD COLUMN IF NOT EXISTS`):

```sql
ALTER TABLE devices ADD COLUMN IF NOT EXISTS kds_role VARCHAR(8);  -- 'MAIN' | 'SUB' | NULL
```

- `terminal_type = 'KDS'` 일 때만 뜻이 있습니다. ▶ 기본값: 비어 있으면 `SUB`.
- 장비 화면에 역할 선택과 **[페어링 코드]** 버튼. 코드는 BO 가 만들지 않고
  Device Server 에 요청합니다 (`POST /pos/pairing-codes`, 관리 토큰) — 기기
  신원의 주인은 연결을 쥔 쪽(Device Server)입니다.
- `v_bo_devices` 뷰에 `kds_role` 추가 → `Debian-Setting/server/db/pos-server-fdw.sql`
  의 뷰 정의 수정 + 서버에서 다시 실행.

### 3.3 Device Server — 표 (pos_server `V6__device_pairing.sql`)

```sql
CREATE TABLE IF NOT EXISTS device_pairing_code (
    code        VARCHAR(6) PRIMARY KEY,           -- 숫자 6자리
    store_id    TEXT NOT NULL,
    device_id   TEXT NOT NULL,
    device_type TEXT NOT NULL,                    -- POS | KIOSK | KDS | DID
    kds_role    TEXT,
    issued_by   TEXT,                             -- BO 운영자
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,             -- issued_at + 10분
    used_at     TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS device_credential (
    id          BIGSERIAL PRIMARY KEY,
    store_id    TEXT NOT NULL,
    device_id   TEXT NOT NULL,
    device_type TEXT NOT NULL,
    kds_role    TEXT,
    token_hash  TEXT NOT NULL UNIQUE,             -- SHA-256(token). 토큰 자체는 저장 안 함
    paired_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
-- 한 기기에 살아 있는 자격은 하나. 다시 페어링하면 옛 것을 revoke 하고 새로.
CREATE UNIQUE INDEX IF NOT EXISTS uq_device_credential_live
    ON device_credential (store_id, device_id, device_type) WHERE revoked_at IS NULL;
```

- 토큰: 32바이트 난수, URL-safe Base64. **저장은 해시만.**
- 코드: 6자리 숫자, 충돌 시 다시 뽑음, **한 번 쓰면 끝**(`used_at`).
- `kds_role` 은 **페어링 순간의 값을 적어 둡니다.** BO 에서 역할을 바꾸면 그 기기는
  다시 페어링해야 합니다 — 역할이 소리 없이 바뀌어 주방의 중심이 옮겨 가는
  일을 막습니다.

### 3.4 주소

| 주소 | 청중 | 입력 | 출력 |
|---|---|---|---|
| `POST /pos/pairing-codes` | 관리 | `{storeId, deviceId, deviceType, kdsRole, issuedBy}` | `{code, expiresAt}` |
| `POST /pos/pair` | **공개** (코드가 곧 증명) | `{code}` | `{storeId, deviceId, deviceType, kdsRole, token}` |
| `POST /pos/devices/revoke` | 관리 | `{storeId, deviceId, deviceType}` | `{revoked}` |
| `GET /pos/devices` | 관리 | `?storeId=` | 기기별 페어링 상태·마지막 접속 |

`/pos/pair` 는 **잘못된 코드가 반복되면 막아야 합니다** — 6자리는 백만 개라 무차별
대입이 됩니다. ▶ 기본값: 같은 IP 에서 1분에 10번 실패하면 10분 차단.

### 3.5 핸드셰이크와 요청 확인

- `TerminalHandshakeHandler`: `token` 으로 `device_credential` 을 찾고, 거기 적힌
  **매장·번호·종류·역할을 신원으로 씁니다.** 쿼리의 `deviceId` 등은 **무시**
  (`warn` 모드에서는 다르면 로그).
- 기존 `BoDeviceDirectory`(명단 대조)는 **그대로 둡니다** — 페어링된 기기라도
  BO 에서 `active=false` 로 내리면 못 붙습니다.
- **KDS-MAIN 은 매장당 하나**: MAIN 으로 붙으려는데 같은 매장에 MAIN 이 이미
  붙어 있으면 거절 (`409`, 이유: "이 매장의 KDS-MAIN 이 이미 연결돼 있습니다").
- HTTP 요청: 디스패처가 `Authorization: Device` 를 확인하고, 신원을
  엔드포인트에 넘깁니다(요청 attribute). 엔드포인트는 쿼리의 `deviceId` 대신
  **이 신원을 씁니다.**

### 3.6 웹 단말 (KDS SUB · DID, 개발용 웹 POS)

- 첫 화면이 **"매장·번호 입력" 에서 "페어링 코드 입력" 으로** 바뀝니다.
- 토큰은 `localStorage` (`sunmoon.deviceToken`). 탭이 아니라 **기기**에 묶이는
  값이라 `sessionStorage` 가 아닙니다.
- **웹 KDS 에서는 MAIN 이 될 수 없습니다** — 페어링 응답의 역할이 MAIN 이면
  웹은 "이 기기는 KDS-MAIN 앱에서 열어야 합니다" 를 띄우고 멈춥니다.

**확인:**
- BO 에서 코드 발급 → 웹 KDS 에 입력 → 역할이 BO 에 적힌 대로 뜸
- 같은 코드 두 번 → 두 번째 거절
- MAIN 기기 둘 → 두 번째 연결 거절
- BO 에서 revoke → 그 기기의 다음 요청 401 (enforce)

---

## 4. KDS-MAIN 의 권한을 서버가 지킴 (2단계)

지금은 **화면에서 버튼을 숨긴 것뿐**입니다. §3 이후에는 서버가 역할을 알므로
서버가 지킵니다.

| 명령 | 누가 | 바꾸는 곳 |
|---|---|---|
| 수령 (`/orders/pickup`) | KDS-MAIN · (비상 시) KDS SUB | Device Server 가 역할 확인 후 Order 로 |
| 다시 부르기 (`/orders/recall`) | KDS-MAIN | 〃 |
| 강제 전부 완료 (새 `/orders/force-produced`) | KDS-MAIN | Order: 새 주소 |
| 조리 시작 | KDS SUB — **자기 줄만** | Order: `OrderLine.startedAt` |
| 라벨 재출력 | KDS-MAIN | §6 |

### 4.1 Order (order_service `V15__line_started_and_force.sql`)

```sql
ALTER TABLE order_line ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;
ALTER TABLE orders     ADD COLUMN IF NOT EXISTS force_produced_by TEXT;   -- 강제 완료한 기기
ALTER TABLE orders     ADD COLUMN IF NOT EXISTS pickup_emergency BOOLEAN NOT NULL DEFAULT false;
```

- **조리 시작은 줄 단위**: `OrderLine` 에 `startedAt`. SUB 가 "시작" 을 누르면
  **자기 줄만** 시작됩니다. 주문의 `COOKING` 은 **"한 줄이라도 시작됨"** 으로
  계산합니다 — `PRODUCED` 를 "모든 줄이 끝남" 으로 계산하는 것과 같은 방식.
- **강제 전부 완료**: `Order.forceProduced(deviceId)` — 안 끝난 줄을 전부 끝내고
  `force_produced_by` 에 적습니다. SUB 가 끝낸 것과 **기록상 구분**됩니다
  (강제 수령 9/17 과 같은 규칙: 표시된 우회로).
- **비상 수령**: 수령 요청에 `emergency=true` 가 오면 `pickup_emergency` 에 적습니다.

> 지금 MAIN 의 "전부 조리완료" 버튼은 **배정된 줄을 못 끝냅니다** (코드 기준 —
> 서버가 누른 기기에 배정된 줄만 끝냄). 이 단계에서 `force-produced` 로 바꿉니다.

### 4.2 Device Server — 역할 확인과 비상 모드

- `pickup`, `recall`, `force-produced`: 신원의 역할이 `MAIN` 이 아니면 `403`.
- **비상 수령**: 그 매장의 KDS-MAIN 이 `TERMINAL_GRACE`(3분) 넘게 끊겨 있으면,
  SUB 의 `pickup` 을 `emergency=true` 로 바꿔 통과시킵니다. MAIN 이 돌아오면
  다시 막힙니다.
- SUB 화면에는 비상 모드일 때만 수령 칸이 뜹니다 — 서버가 `GET /kds/mode`
  (`{emergency: bool, since}`) 로 알려 줍니다.

### 4.3 BO

- 매출 조회에 **강제 완료 · 비상 수령 배지** (강제 수령 배지와 같은 자리).

**확인:**
- SUB 토큰으로 `recall` → 403. MAIN 토큰으로 → 200
- SUB 가 자기 줄만 시작 → 다른 SUB 화면의 그 주문은 "대기" 그대로
- MAIN 끄고 3분 → SUB 에 수령 칸 → 수령 → `pickup_emergency=true`
- MAIN 의 강제 완료 → `force_produced_by` 에 MAIN 기기

---

## 5. Electron 셸 (3단계)

### 5.1 처음 켜면

```
config.json 없음 → [서버 주소] + [페어링 코드] 입력 화면 (셸 자체 화면)
               → POST /pos/pair → 역할·신원·토큰 받음
               → token 은 safeStorage 로 암호화해 config.json 에
               → 역할의 화면 번들을 연다 (ui/<role>/index.html)
```

- ▶ 기본값 서버 주소: `http://211.217.183.232:8087` (도메인이 생기면 바꿈)
- 역할 → 화면 번들: `POS → ui/pos`, `KIOSK → ui/kiosk`, `KDS(MAIN) → ui/kds`

### 5.2 화면에 신원을 넘기는 법

화면 번들은 지금 `sessionStorage` / URL 쿼리로 신원을 읽습니다. 셸은
`window.sunmoon.identity.get()` → `{storeId, deviceId, deviceType, kdsRole, token, serverUrl}`
을 제공하고, 화면은 **그게 있으면 그것을, 없으면 지금 방식(페어링 코드 화면)** 을
씁니다. `deviceServer.ts` 의 `BASE` 도 `serverUrl` 을 우선합니다.

### 5.3 늘 켜져 있게

- Windows 로그인 시 자동 시작 (`app.setLoginItemSettings`)
- 화면 프로세스가 죽으면 (`render-process-gone`) **다시 띄움** — 1분에 3번 넘게
  죽으면 멈추고 오류 화면 (무한 재시작 방지)
- 하루 한 번, **마감 뒤** 화면을 새로 띄움 (긴 시간 켜 둔 브라우저의 메모리 누수)
- KIOSK: `kiosk: true` — Alt+F4·작업 표시줄 막힘. 관리자 탈출은 ▶ 기본값: 화면
  구석 5번 누르고 **관리자 PIN** (BO 장비에 설정)

### 5.4 빌드

- `electron-builder`, Windows NSIS 설치 파일
- ▶ 기본값: **코드 서명 없음** (시연 단계). 매장 배포 전에 인증서.
- 1단계에서는 화면 번들을 **설치 파일에 같이 넣습니다** — 두 층 업데이트는 §7.

**확인:** 설치 → 코드 입력 → POS 화면이 뜨고 주문을 수락할 수 있음. 서버를 끊고
재시작해도 화면이 뜸(원장 동작은 §6.1).

---

## 6. 네이티브 기능 (4·5단계)

### 6.1 POS 로컬 원장 — SQLite (4단계)

ADR-0010 2단계. 지금 IndexedDB(`sun-moon-terminal-pos/src/lib/ledger.ts`)와
**같은 모양, 같은 규칙**입니다. 바뀌는 것은 저장소뿐입니다.

```sql
-- %APPDATA%/sun-moon/ledger.sqlite
CREATE TABLE counter (key TEXT PRIMARY KEY, last INTEGER NOT NULL);
CREATE TABLE tran (
  id TEXT PRIMARY KEY,              -- 매장:단말:판매일자:거래번호
  store_id TEXT, device_id TEXT, business_date TEXT, tran_no TEXT,
  state TEXT NOT NULL,              -- MINTED | PENDING | SENT | VOID
  order_id INTEGER, payload TEXT,   -- JSON
  minted_at TEXT, sent_at TEXT, attempts INTEGER DEFAULT 0, last_error TEXT
);
CREATE INDEX tran_state ON tran(state);
```

**브리지** (`window.sunmoon.ledger`) — 지금 IndexedDB 모듈과 **같은 함수 이름**:
`mint(storeId, deviceId, businessDate, orderId)`, `confirm(id, payload)`,
`discard(id, reason)`, `pending(storeId, deviceId)`, `unsentCount(...)`,
`markSent(ids)`, `markFailed(ids, reason)`, `minted(...)`.

- `mint` 는 **한 트랜잭션** (`db.transaction(() => { 카운터 +1; tran 삽입 })()`).
  이게 이 기능의 전부입니다 — 번호와 거래가 같이 나거나 같이 안 남.
- POS 화면의 `lib/ledger.ts` 를 **어댑터**로: `window.sunmoon?.ledger` 가 있으면
  그것을, 없으면 지금의 IndexedDB 를 씁니다.
- **옮겨오기**: 셸로 처음 뜨면 화면이 IndexedDB 의 `counter`·`tran` 을 읽어
  `ledger.import(rows)` 로 넘김 → SQLite 에 넣고 `config.json` 에
  `ledgerMigrated: true`. 카운터는 **큰 쪽을 남김** (localStorage 옮길 때와 같은 규칙).

**확인:** 9/25 에 웹 POS 로 한 확인을 셸에서 그대로 — 수락 → `SENT`, 거절 →
번호 안 태움, 전송 실패 → 배너, 마감 → 경고. 그리고 **브라우저 데이터를 지워도
원장이 남는 것**.

### 6.2 거래 전문에 결제 싣기 (4단계에 같이)

지금 POS 가 만드는 거래 전문의 `payments` 가 **비어 있습니다** — 결제는
APP-SERVER 가 OMS 앞에서 승인해서 POS 를 안 지나갑니다. 길을 잇습니다:

1. **Order** (`V16__order_payment.sql`, JSONB 에도): 주문에 `payment` —
   `{method, approvalNo, amount}`. `CreateOrderRequest` 가 받습니다.
2. **APP-SERVER**: `HttpOmsGateway.place` 가 승인 결과를 함께 보냅니다.
   `AppOrderService` 에서 승인 뒤 `oms.place(saved, kds)` 를 부르는 자리에서
   `approval` 을 넘기면 됩니다 (`OmsGateway.place(order, kds, approval)`).
3. **POS 화면**: `Order` 타입에 `payment`, `trSale.ts` 가
   `payments: [{paymentSeq:1, paymentType: payment.method, paymentAmount, approvalNo}]`.
   `payment` 가 없는 옛 주문은 지금처럼 비웁니다(지어 넣지 않음).

**확인:** 앱 주문 → POS 수락 → `tr_sale_payment` 에 `SUNMOON_PAY` + `SMP-...` 승인번호.

### 6.3 프린터 (5단계)

- 프린터는 **BO 장비**입니다: `device_type = 'RECEIPT_PRINTER' | 'LABEL_PRINTER'`,
  `ip_address`, `port`(기본 9100), `store_id`. (9/13 부터 IP·포트 칸과 연결 확인이 있음)
- 셸이 **자기 매장의 프린터 목록**을 받음: `GET /pos/printers` (단말 토큰 → 매장은
  신원에서) → `v_bo_devices` 에서 읽음. `v_bo_devices` 에 `ip_address, port` 추가.
- `printer.ts`: Node `net.Socket` 으로 TCP 9100 에 ESC/POS 바이트를 씀. 외부
  라이브러리 없이 됩니다. 명령은 초기화(`ESC @`), 한글(▶ 기본값 CP949 인코딩 —
  `iconv-lite`), 줄 바꿈, 자르기(`GS V`), 돈통(`ESC p`).
- 브리지: `printer.receipt(order)`, `printer.label(job)`, `drawer.open()`.
  **화면은 바이트를 모릅니다** — 무엇을 찍을지만 넘기고, 모양은 셸이 만듭니다.
- 영수증: 지금 POS 의 `Receipt` 컴포넌트와 같은 내용 + 주문 QR(`GS ( k` 명령).

**확인:** 실물 프린터가 필요합니다. 없으면 `nc -l 9100 > out.bin` 으로 받아
바이트를 확인 (초기화로 시작, 자르기로 끝).

---

## 7. 라벨 작업 (5단계)

### 7.1 표 (pos_server `V7__print_job.sql`)

```sql
CREATE TABLE IF NOT EXISTS print_job (
    id           BIGSERIAL PRIMARY KEY,
    store_id     TEXT NOT NULL,
    printer_id   TEXT NOT NULL,        -- BO 장비 device_id (LABEL_PRINTER)
    kind         TEXT NOT NULL,        -- LABEL (나중에 KITCHEN 등)
    order_id     BIGINT NOT NULL,
    line_seq     INT,
    unit_no      INT,                  -- 수량 중 몇 번째 (1/3)
    unit_total   INT,
    copy_no      INT NOT NULL DEFAULT 0,  -- 0 = 처음, 1.. = 재출력 차례
    original_id  BIGINT REFERENCES print_job(id),  -- 재출력이면 처음 작업
    content      JSONB NOT NULL,       -- 찍을 내용. 재출력은 이걸 그대로 씀
    requested_by TEXT NOT NULL,        -- 기기 (KDS-MAIN)
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status       TEXT NOT NULL DEFAULT 'QUEUED',   -- QUEUED | PRINTED | FAILED
    error        TEXT,
    attempts     INT NOT NULL DEFAULT 0,
    printed_at   TIMESTAMPTZ,
    -- 처음 출력은 단위마다 한 번. 재출력은 copy_no 가 달라서 여러 번 가능.
    UNIQUE (order_id, line_seq, unit_no, copy_no)
);
CREATE INDEX IF NOT EXISTS idx_print_job_queue ON print_job (store_id, status) WHERE status <> 'PRINTED';
```

### 7.2 흐름

```
주문 PRODUCED (order-events) ─→ Device Server: 줄×수량만큼 print_job 생성 (content 에 그 순간의 값)
                            ─→ 그 매장 KDS-MAIN 에 WS {type:"PRINT", jobId}
KDS-MAIN 셸 ─ GET /pos/print-jobs/{id} ─→ printer.label ─→ POST /pos/print-jobs/{id}/ack {ok, error}
KDS-MAIN "라벨 다시" ─ POST /pos/print-jobs/{id}/reprint ─→ 새 행 (copy_no+1, content 복사) ─→ WS PRINT
```

- **처음 출력 시점**: ▶ 기본값 **주문 전체가 `PRODUCED` 가 될 때**. (줄 하나가
  끝날 때마다 찍으려면 트리거만 바꾸면 됩니다 — 표는 그대로)
- **content 는 만드는 순간 고정**: `{pickupNo, menuName, unitNo, unitTotal,
  tableLabel, orderedAt, copyNo}`. 재출력은 **이걸 복사**합니다 — 다시 그리지 않습니다.
- 라벨에 `copyNo > 0` 이면 **"재출력 N"** 을 크게 찍습니다.
- KDS-MAIN 이 꺼져 있으면 작업은 `QUEUED` 로 남고, 다시 붙으면 셸이
  `GET /pos/print-jobs?status=QUEUED` 로 가져가 찍습니다.
- **재처리**: `FAILED` 는 재처리 화면의 다섯 번째 종류 `PRINT`
  (`RawLogReprocessor` 가 아니라 새 `PrintJobReprocessor` — "다시" 가 KDS-MAIN 에
  다시 밀어 넣기라서 뜻이 다릅니다).

### 7.3 KDS 화면

- MAIN 의 수령 대기 카드에 **[라벨 다시]** — 줄·단위를 고르는 작은 목록.
- 카드에 "라벨 3/3 · 재출력 1" 처럼 상태 표시 (`GET /pos/print-jobs?orderId=`).

**확인:** 음료 3잔 주문 → PRODUCED → 3장. 하나 재출력 → "재출력 1" 이 찍히고,
content 가 처음과 같음 (그 사이 BO 에서 메뉴 이름을 바꿔 봐도).

---

## 8. 업데이트 — 두 층 (6단계)

| | 화면 번들 | 셸 |
|---|---|---|
| 무엇 | `ui/pos` 등 — React 빌드 | Electron 본체 |
| 얼마나 자주 | 자주 | 드묾 |
| 어디서 | §3.5 프로그램 배포 (`program = "ui-pos"` 등) | nginx `:8000/releases/shell/` (`electron-updater` generic) |
| 검증 | **ed25519 서명** — 공개키는 셸에 박혀 있음 | 코드 서명 (인증서 생기면) |

### 8.1 화면 번들 업데이트

1. 빌드 기계에서: `dist/` 를 zip → **개인키로 서명**(`.sig`) → nginx `:8000/releases/ui/`
   에 올림 → BO 배포 현황 화면에서 등록 (`packageUrl`, `checksum`=SHA-256).
2. 셸: heartbeat 때 `GET /pos/programs` → 새 버전이 있으면 zip·서명 받기 →
   **셸에 박힌 공개키로 서명 확인** → `ui/<role>.next/` 에 풀기 →
   **다음 적용 시점**에 `ui/<role>` 과 바꿈, 옛 것은 `ui/<role>.prev/` 로 (되돌리기용).
3. **적용 시점**: ▶ 기본값 **마감 뒤 또는 다음 시작 때.** 영업 중에는 받아만 두고
   안 바꿉니다 — POS-Server 가 그 단말의 개점·마감을 압니다.
4. 결과를 `POST /pos/programs/ack` (`INSTALLED` / `FAILED`) — 배포 현황에 보임.
5. 새 번들로 띄운 뒤 30초 안에 화면이 죽으면 **자동으로 `.prev` 로 되돌림.**

**서명이 왜 필요한가**: §2.2 를 고쳐도, 배포 기록이 잘못 들어가는 길(BO 계정
탈취 등)은 남습니다. 서명은 **"우리 빌드 기계가 만든 것만 돈다"** 를 셸이 직접
확인하는 마지막 벽입니다. **개인키는 서버에 두지 마십시오** — 빌드하는 PC 에만.
새 비밀이므로 `secrets.md` 에 "어디 있는지" 를 적습니다.

### 8.2 셸 업데이트

- `electron-updater` 의 generic provider, 주소 `http://<서버>:8000/releases/shell/`.
- 역시 마감 뒤에만. 코드 서명이 없는 동안은 **자동 업데이트를 끄고** 매장 방문
  또는 원격으로 설치 파일을 다시 돌립니다 (서명 없는 자동 업데이트는 §8.1 의
  서명 확인이 셸 자신에게는 안 걸리기 때문).

---

## 9. KIOSK (7단계)

**캐셔 없는 POS**입니다.

- 화면: **손님 화면(channel-order frontend) 재사용** — [customer-app.md](customer-app.md) §5.
- 흐름: 손님이 담고 결제 → 셸이 **자기 POS번호로 채번** (`ledger.mint`) →
  `POST /orders/kiosk` (단말 토큰) → Device Server → Order 에 **수락된 채로 생성**
  (`acceptedBy = kiosk-01`, `tranNo`) → 영수증 출력 (주문 QR 포함).
- Order: 새 주소 `POST /orders/accepted` — 생성과 수락을 한 번에. 수락 규칙
  (거래번호 유일성 등)은 기존 `movedTo(ACCEPTED)` 와 같은 것을 씁니다.
- 결제: ▶ 기본값 썬앤문페이(가상). VAN 이 정해지면 `window.sunmoon.payment`.
- POS 는 KIOSK 주문을 **수락 대기에 안 띄웁니다** (이미 수락됨) — 최근 주문에만.

**확인:** KIOSK 주문 → POS 수락 없이 KDS 에 뜸 → 거래가 `kiosk-01` 번호로
POS-Server 에 올라감.

---

## 10. 단말 코드 공통화 (8단계, 선택)

지금 `useTerminalSocket.ts` 100줄이 단말 셋에 **똑같이** 복사돼 있고,
`deviceServer.ts` 는 455/336/261줄로 갈라졌습니다. 셸로 가면서 페어링·토큰
코드가 여기에 또 붙습니다 — 그 전에 모으는 게 쌉니다.

▶ 기본값: 단말 저장소 넷(pos·kds·did·셸)을 **npm workspaces 모노레포**로 합치고
`packages/terminal-core` 에 `deviceServer` 공통부·`useTerminalSocket`·페어링을 둡니다.
배포 경로(`tar` 로 nginx 에)는 그대로입니다.

---

## 11. 단계와 순서

| 단계 | 내용 | 배포 | 선행 |
|---|---|---|---|
| **0** | Device Server API 인증 (`warn`) | Device Server, BO | — |
| **1** | 페어링 + BO KDS 역할 + 웹 단말 페어링 화면 | Device Server, BO, 단말 3개, fdw 뷰 | 0 |
| **2** | KDS-MAIN 권한 서버 확인 · 줄 단위 조리 시작 · 강제 완료 · 비상 수령 | Order, Device Server, KDS, BO | 1 |
| **0→enforce** | 로그가 조용해지면 `API_AUTH=enforce` | Device Server | 1 |
| **3** | Electron 셸 (번들 동봉, 페어링, 늘 켜짐) | 설치 파일 | 1 |
| **4** | SQLite 원장 + 거래 전문 결제 | 셸, POS, Order, APP-SERVER | 3 |
| **5** | 프린터 + 라벨 작업 + 재처리 PRINT | 셸, Device Server, KDS, BO | 3 |
| **6** | 두 층 업데이트 (서명) | 셸, nginx | 0(enforce), 3 |
| **7** | KIOSK | 셸, Order, Device Server, channel-order | 4, 5 |
| **8** | 공통화 (선택) | 단말 전부 | 3 |

**0 과 6 의 순서를 바꾸지 마십시오.** 인증 없이 자동 업데이트를 켜면 §2.2 의
원격 코드 실행이 실제로 열립니다.

---

## 12. 하지 말 것 — 이 플랫폼이 이미 겪은 것들

구현을 가볍게 하더라도 이것들은 지키십시오. 전부 실제로 한 번씩 당했습니다.

1. **Spring 서비스에 생성자가 둘이면 `@Autowired`** — 그리고 배선 시험
   (`*WiringTest`). 세 번 죽었습니다(BusinessDayService, MenuService, MemberService).
2. **BO 에 화면을 더하면 `SpaRoutes` 에 한 줄** — 안 하면 새로고침에서 죽습니다.
   `SpaRoutesTest` 가 잡아 줍니다.
3. **Docker 빌드는 한 번에 하나** — 동시에 돌리면 이 서버의 CPU 가 살아 있는
   WebSocket 을 떨어뜨립니다.
4. **`docker run` 에 넣은 env 는 `bin/docker-start-all.sh` 에도** — 안 그러면
   `stop-all → start-all` 때 조용히 빠집니다.
5. **재부팅 뒤에는 `docker ps -a` 부터** — `unless-stopped` 인데도 안 살아난 적이
   있습니다. 살릴 때는 `run` 이 아니라 `start`.
6. **공통으로 옮길 때는 SQL 을 글자 단위로 대조** — 시험은 DB 없이 돌아서 못 잡습니다
   (`LIMIT 200` 이 빠질 뻔했습니다).
7. **실패는 멈추고 사람을 기다린다** — 배치가 `FAILED` 를 다시 집어 가지 않게.
8. **모르는 것과 없는 것을 가른다** — 목록·설정을 못 읽으면 막지 말고 경고.
9. **우회로는 표시하고 기록한다** — 강제 수령·강제 완료·비상 수령.
10. **새 비밀은 `secrets.md` 에** — 관리 토큰, 서명 개인키 위치.
11. **새 migration 번호는 저장소마다 확인** — 지금 Device Server V5, Order V14,
    APP-SERVER V3.
