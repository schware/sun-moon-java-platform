# 설계 — 손님 앱 (웹 · 모바일 · 키오스크 화면)

- 근거: [FLOW.md](../FLOW.md), ADR-0007(QR), 0012(APP-SERVER 원장), 0015(단말)
- 작성: 2026-09-27
- **이 문서는 구현용입니다.** 열린 질문은 기본값으로 결정했고 `▶ 기본값` 으로
  표시했습니다. 단계마다 **바꾸는 곳 · 계약 · 확인**이 있습니다.

---

## 0. 결정 요약

| 항목 | 결정 |
|---|---|
| **화면** | **하나** — 지금의 `channel-order/frontend` (React) |
| **껍데기** | **셋** — 웹(QR·브라우저) / 모바일 앱(Capacitor) / 키오스크(매장 단말 셸) |
| 모바일 기술 | **Capacitor** — 지금 React 화면을 그대로 싸서 앱으로. 다시 짜지 않음 |
| QR 테이블 주문 | **웹 그대로.** 손님에게 앱 설치를 요구할 수 없습니다 |
| 앱만 하는 일 | 푸시("준비됐습니다"), 회원 자동 기억, 주문 이력 |
| 회원 | 앱은 첫 실행에 **묻지 않고** 발급. **이력은 회원 토큰으로만** |
| 푸시 | FCM. **APP-SERVER 가 `order-events` 를 구독** — Order 는 안 고침 |
| 결제 | 썬앤문페이(가상) 그대로 |
| 매장 찾기(GIS) | 나중 (§4.7) — 지금은 매장 목록 |
| 모바일 플랫폼 | ▶ 기본값: **Android 먼저.** iOS 는 Mac 과 Apple 계정이 필요 |
| 배포 | ▶ 기본값: 시연은 **APK 직접 설치**, 그다음 Play 내부 테스트 |
| 딥링크 | **도메인이 생긴 뒤** (iOS·Android 모두 도메인 검증이 필요) |

### 왜 Capacitor 인가

| | 좋은 점 | 여기서 안 맞는 점 |
|---|---|---|
| **Capacitor** | 지금 React 화면을 그대로. 네이티브 기능(푸시·보안 저장)은 플러그인 | 화면이 웹뷰 — 이 앱 규모에서는 문제 안 됨 |
| React Native | 네이티브 느낌 | **화면을 다시 짜야** 합니다. 웹·키오스크와 화면이 갈라짐 |
| Flutter | 성능 | 언어부터 다름(Dart). 전부 다시 |
| PWA 만 | 가장 쌈 | iOS 푸시가 "홈 화면에 추가" 한 뒤에만 됨. 스토어에 없음 |

**결정적인 이유는 공통화입니다** — 같은 화면이 웹(QR), 앱, 키오스크 셋에서
돕니다. 매장 단말이 "셸 하나 + 역할" 인 것과 같은 모양입니다.

---

## 1. ⚠️ 보안 바닥 — 가장 먼저 (A0 단계)

### 1.1 OMS(8083) 공개를 닫습니다 — ✅ 2026-09-28 완료

> **했습니다.** 공개 규칙을 지우고 LAN 전용으로 바꿨고, LTE 로 밖에서 안 열리는
> 것을 확인했습니다. 서버 안의 호출은 전부 정상입니다. 아래는 왜 닫았는지의
> 기록으로 남깁니다. **§1.2 (CORS) 도 2026-09-28 구현했습니다**(channel-order `0a9eefa`).

**OMS 는 지금 공개(`ufw allow 8083/tcp`)이고 인증이 없습니다.** 그래서
인터넷에서:

- `GET /order/orders` — **전체 주문**이 PIN(수령 증명번호)·영수증 QR 토큰과 함께
  나옵니다. 9/24 에 만든 "추측 불가능한 128비트 토큰" 이 이 목록에서 그대로 보입니다.
- `POST /order/orders` — **결제를 건너뛰고** 주문을 만들 수 있습니다.
- 상태 변경·수령·취소도 됩니다.

**OMS 를 부르는 쪽은 전부 같은 서버 안(localhost)** 입니다 — channel-order,
APP-SERVER, Device Server, BO (`--network host`). 손님 화면도 단말도 OMS 를 직접
부르지 않습니다. 그래서 **닫아도 기능은 안 깨집니다.** (랜딩 페이지의 Order
Swagger 링크만 막힙니다.)

```bash
# 사람이 직접 (sudo)
sudo ufw delete allow 8083/tcp
sudo ufw allow from 192.168.0.0/24 to any port 8083 proto tcp   # LAN 에서 Swagger 는 보이게
```

> ⚠️ **확인은 밖에서 하십시오 (LTE).** LAN 에서는 NAT 루프백 때문에 공인 IP 로
> 불러도 열려 보입니다. `docker.md` 에 적어 둔 그 함정입니다.

같은 이유로 **KDS(8081)·Delivery(8082)** 도 공개인지 보고, 밖에서 부를 쪽이
없으면 같이 닫습니다.

### 1.2 channel-order CORS — ✅ 2026-09-28 구현·배포 (channel-order `0a9eefa`)

> `CorsConfig` — `/api/**` 에 GET·POST, 헤더 `Content-Type, Authorization`,
> 적힌 출처만. 값에 `*` 가 섞이면 **그 값은 버립니다**(오타 하나로 전부 열리지
> 않게). 시험 4개(`CorsTest`). 기본값을 쓰므로 기동 스크립트에 env 를 더하지 않았습니다.

모바일 앱의 화면은 `https://localhost`(Android) / `capacitor://localhost`(iOS)
에서 돕니다. channel-order 입장에서 **다른 출처**라 CORS 가 필요합니다.
지금 CORS 설정이 **없습니다.**

- `WebConfig` 에 `addCorsMappings("/api/**")` — 허용 출처는 env
  `APP_CORS_ORIGINS` (쉼표로), ▶ 기본값 `https://localhost,capacitor://localhost`.
- **`*` 로 열지 마십시오.** 아무 웹사이트나 손님 대신 주문을 넣을 수 있게 됩니다.
- 허용 메서드 `GET, POST`, 헤더 `Content-Type, Authorization`.

**확인:** `curl -H "Origin: https://localhost" -I .../channel-order/api/stores` 에
`Access-Control-Allow-Origin: https://localhost`. `Origin: https://evil.example` 에는 없음.

---

## 2. 화면 하나를 세 껍데기에 — 플랫폼 층 (A1 단계)

`channel-order/frontend/src/platform/` 을 새로 두고, 껍데기마다 다른 것만 여기로 모읍니다.
**화면 컴포넌트는 플랫폼을 직접 보지 않습니다.**

```
src/platform/
  index.ts     kind(): 'web' | 'app' | 'kiosk'
                 — kiosk: window.sunmoon?.identity 가 있으면
                 — app:   Capacitor.isNativePlatform()
                 — 나머지: web
  api.ts       BASE — web: '/channel-order/api' (같은 출처)
                      app: import.meta.env.VITE_API_BASE (절대 주소)
                      kiosk: 셸이 준 서버 주소 (Device Server 경유, §5)
  storage.ts   회원 토큰 보관 — web: localStorage / app: Preferences(보안 저장) / kiosk: 저장 안 함
  push.ts      app 만. 나머지는 아무 일도 안 함
  order.ts     주문 넣기 — web·app: channel-order / kiosk: 셸 브리지
```

- 지금 `api.ts` 의 `const BASE = '/channel-order/api'` 를 `platform/api.ts` 로.
- 지금 `rememberedMember()` 의 `localStorage` 를 `platform/storage.ts` 로.
- **이 단계는 웹 동작이 하나도 안 바뀌어야 합니다.** 바뀌는 것은 코드 위치뿐.

**확인:** 웹에서 매장 선택 주문, 테이블 QR 주문, 영수증 QR 조회가 그대로.

---

## 3. 회원 토큰과 주문 이력 (A2 단계)

### 3.1 왜 토큰이 필요한가

회원번호는 **비밀이 아닙니다** — 열한 자리 숫자이고, 손님이 적어 두었다가 치는
값입니다. 번호만으로 이력을 보여주면 **남의 번호를 치면 남의 주문이 보입니다.**

그래서 둘을 가릅니다:

| | 필요한 것 | 지금 |
|---|---|---|
| 주문에 번호 붙이기 | 회원번호 | 그대로 (9/24) |
| **이력 보기** | **회원 토큰** — 그 번호를 받은 기기만 가짐 | 새로 |

### 3.2 다른 기기로 옮기기 — 영수증이 증명

새 휴대폰에서 이력을 보려면: **그 번호로 주문한 영수증의 QR 을 한 번 찍습니다.**
영수증 QR 토큰(9/24)이 가리키는 주문의 손님이 그 회원번호면, 새 기기에 회원
토큰을 줍니다. 영수증을 가진 사람이 곧 그 주문의 사람이라는 9/24 의 규칙을
그대로 씁니다.

### 3.3 표 (app_server `V4__member_device.sql`)

```sql
-- 회원번호를 가진 기기 하나 = 한 줄. 앱 설치마다, 웹 브라우저마다.
CREATE TABLE IF NOT EXISTS member_device (
    id          BIGSERIAL PRIMARY KEY,
    member_no   VARCHAR(16) NOT NULL REFERENCES member(member_no),
    token_hash  TEXT NOT NULL UNIQUE,         -- SHA-256(회원 토큰). 토큰 자체는 저장 안 함
    platform    TEXT NOT NULL,                -- WEB | ANDROID | IOS
    push_token  TEXT,                         -- FCM 등록 토큰 (앱만, §4)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_member_device_member ON member_device (member_no) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_member_device_push ON member_device (push_token) WHERE push_token IS NOT NULL;

-- 원장에서 주문 상태 화면으로 가려면 영수증 QR 토큰이 필요합니다.
ALTER TABLE app_order_header ADD COLUMN IF NOT EXISTS claim_token TEXT;
```

- `claim_token`: OMS 가 주문을 만들 때 돌려주는 값을 APP-SERVER 가 받아 적습니다.
  `HttpOmsGateway` 의 답(`Answer`)에 `claimToken` 을 더하고, `AppOrderService` 가
  원장에 적습니다. (Order 는 이미 응답에 `claimToken` 을 넣고 있습니다.)

### 3.4 주소 (APP-SERVER, channel-order 가 그대로 중계)

| 주소 | 인증 | 입력 | 출력 |
|---|---|---|---|
| `POST /members` (바뀜) | 없음 | `{platform}` | `{memberNo, issuedAt, memberToken}` ← 토큰이 더해짐 |
| `POST /members/link` | 없음 (영수증이 증명) | `{memberNo, claimToken, platform}` | `{memberToken}` / 404 |
| `GET /members/me` | `Authorization: Member <token>` | — | `{memberNo, issuedAt}` |
| `GET /members/me/orders` | 〃 | `?limit=30` | `[{orderNo, storeName, businessDate, summary, amount, status, claimToken, placedAt}]` |
| `POST /members/me/push-token` | 〃 | `{pushToken}` | 204 (§4) |
| `POST /members/me/logout` | 〃 | — | 그 기기 토큰 revoke |

- `/members/link` 의 확인: OMS 에 `GET /orders/claim/{token}/owner` (**LAN 전용**,
  새 주소, `{customerId}` 만 돌려줌)를 물어 `customerId == memberNo` 인지.
  **§1.1 로 OMS 공개를 닫은 뒤에 만듭니다.**
- 잘못된 시도가 반복되면 막습니다 — ▶ 기본값 같은 회원번호로 10분에 5번 실패하면 1시간.
- 이력의 `status` 는 원장의 상태(`SENT` 등)가 아니라 **OMS 의 상태**를 보여줍니다 —
  손님이 궁금한 것은 "매장이 어디까지 했나" 입니다. 30건을 OMS 에 하나씩 묻지 말고,
  ▶ 기본값: 목록은 원장 상태 + 오늘 주문만 OMS 에 물어 채움.

### 3.5 화면

- **앱**: 첫 실행에 **묻지 않고** `POST /members` → 토큰 보관. 앱 자체가 그
  손님의 기기라 물을 이유가 없습니다.
- **웹**: 지금처럼 "번호 받기 / 번호 입력" (선택). 번호를 받으면 토큰도 같이 보관.
- **"내 주문"** 화면 (앱·웹 공통, 토큰이 있을 때만): 목록 → 누르면 기존
  `OrderStatus`(영수증 QR 화면)로.
- **"다른 기기에서 쓰던 번호" 연결**: 번호 입력 + 영수증 QR 찍기 (앱은 카메라
  플러그인, 웹은 `/o/{token}` 주소를 여는 것으로 대신).

**확인:** 앱 A 에서 주문 2건 → 이력 2건. 앱 B 에서 번호만 치면 이력 안 보임,
주문은 그 번호로 붙음. 앱 B 에서 영수증 QR 로 연결 → 이력 보임.

---

## 4. 푸시 (A4 단계)

### 4.1 길

```
OMS ─ order-events (Redis) ─→ APP-SERVER (새로 구독)
                              ├ 이벤트의 orderId 로 원장 찾기 (oms_order_no)
                              ├ customerId 가 회원번호인가 (MemberNo.looksValid)
                              ├ 그 회원의 살아 있는 기기 중 push_token 있는 것
                              └→ FCM HTTP v1 ─→ 손님 휴대폰
```

**Order 는 안 고칩니다.** `order-events` 에 `customerId` 가 이미 실려 있고,
회원 주문이면 그 값이 회원번호입니다.

### 4.2 무엇을 보내나

| 이벤트 (status) | 제목 | 본문 |
|---|---|---|
| ACCEPTED | 주문이 접수됐습니다 | {매장} · 수령번호 {P-4} |
| **PRODUCED** | **준비됐습니다** | 수령번호 {P-4} — 카운터로 오세요 |
| REJECTED / EXPIRED | 주문이 취소됐습니다 | 결제는 돌려드렸습니다 |
| CANCELLED | 취소됐습니다 | 〃 |

- **PIN 은 절대 넣지 않습니다.** 잠금 화면에 뜹니다.
- 데이터에 `claimToken` 을 넣어 누르면 그 주문 상태 화면으로.
- **다시 부르기**는 상태가 안 바뀌어서 이벤트가 없습니다 → 1단계에서는 푸시 안 함.
  (필요하면 Order 가 `called` 이벤트를 따로 내게 — 나중)
- 환불 문구는 **실제로 환불됐을 때만** — 원장의 환불 상태를 보고 고릅니다.

### 4.3 APP-SERVER 구현

- 의존성: `org.redisson:redisson` (Order 가 쓰는 것과 같은 버전), env `REDIS_URL`.
  **새 env 는 `docker-start-all.sh` 의 APP-SERVER 줄에도** (§7-4).
- `infrastructure/push/FcmPushSender` — `google-auth-library-oauth2-http` 로 액세스
  토큰, `RestClient` 로 `POST https://fcm.googleapis.com/v1/projects/{id}/messages:send`.
  무거운 `firebase-admin` 대신 — 쓰는 기능이 이것 하나라서.
- env `FCM_PROJECT_ID`, `FCM_CREDENTIALS_FILE` (서비스 계정 JSON 경로).
  **없으면 푸시를 끄고 기동 로그에 한 번 남깁니다** — 설정이 없는 서버에서
  죽으면 안 됩니다. **새 비밀입니다 — `secrets.md` 에 추가.**
- FCM 이 "등록 토큰이 없다"(`UNREGISTERED`)고 하면 그 `push_token` 을 지웁니다.
- 구독이 끊겨 있는 동안의 이벤트는 **잃습니다** (Redis pub/sub). 앱은 열 때 이력을
  다시 읽으므로 손님은 상태를 봅니다 — 푸시가 늦는 것이지 사실이 사라지는 게 아님.
- **배선 시험** (`*WiringTest`) — 이 서비스는 생성자 사고를 이미 한 번 겪었습니다.

### 4.4 앱

- `@capacitor/push-notifications`. 권한은 **첫 주문을 넣은 직후에** 묻습니다
  ("준비되면 알려드릴까요?"). 앱을 켜자마자 묻지 않습니다 — 이유를 모르는 권한
  요청은 거절됩니다.
- 받은 등록 토큰을 `POST /members/me/push-token`.

**확인:** 앱에서 주문 → POS 수락 → "접수됐습니다" 알림 → KDS 완료 → "준비됐습니다"
알림 → 누르면 그 주문 상태 화면.

---

## 5. 키오스크 화면 모드 (매장 단말 앱 7단계와 함께)

키오스크는 **같은 화면**을 매장 단말 셸에서 돌립니다 ([store-app.md](store-app.md) §9).
`platform.kind() === 'kiosk'` 일 때만 달라지는 것:

| | 웹·앱 | 키오스크 |
|---|---|---|
| 매장 | 고름 / QR 이 정함 | **셸의 신원이 정함** (고르지 않음) |
| 회원 | 발급·보관 | **입력만.** 발급·보관 안 함 — 여럿이 쓰는 기계에 남의 토큰을 남기면 안 됨 |
| 주문 넣기 | channel-order | 셸 브리지 → Device Server `POST /orders/kiosk` |
| 결제 | APP-SERVER 가 승인 | 셸 (`window.sunmoon.payment`, 그 전까지 썬앤문페이) |
| 영수증 | 화면 QR | **출력** (셸 프린터, 주문 QR 포함) |
| 쉬는 화면 | — | 60초 손을 안 대면 장바구니 비우고 처음으로 |
| 글씨·버튼 | 휴대폰 크기 | 큰 터치 크기 (`data-kind="kiosk"` CSS) |

---

## 6. 모바일 빌드와 배포

### 6.1 Android (A3 단계)

- `channel-order/frontend` 안에 Capacitor: `npx cap init "썬앤문" com.sunmoon.order`,
  `npx cap add android`. **`android/` 폴더는 git 에 넣습니다** (Capacitor 권장).
- 빌드: `VITE_API_BASE=http://211.217.183.232:8085/channel-order/api npm run build && npx cap sync android`
  → Android Studio 에서 APK.
- **평문 HTTP 허용이 필요합니다** (TLS 가 없어서) — `network_security_config.xml`
  에 그 서버 주소만. ▶ 도메인과 TLS 가 생기면 **반드시 지웁니다.**
- 작업 PC 에 Android Studio(SDK) 설치가 필요합니다. JDK 21 은 이미 있습니다.
- 채널 순서: **APK 직접 설치(시연)** → Play Console 내부 테스트(등록비 1회) → 공개.

### 6.2 iOS (A5 단계)

- **macOS 와 Xcode 가 필요합니다.** 작업 PC 는 Windows 라 직접 못 합니다 —
  Mac 을 쓰거나 클라우드 빌드(Codemagic 등).
- Apple Developer 계정(연간 비용), 푸시는 APNs 키를 FCM 에 등록.
- **iOS 는 평문 HTTP 를 막습니다**(ATS). 예외를 둘 수는 있지만 심사에서 문제가
  됩니다 → 사실상 **도메인과 TLS 가 먼저**입니다.

### 6.3 딥링크 (A6 단계)

영수증 QR·테이블 QR 을 찍었을 때 앱이 깔려 있으면 앱으로 열기. Android App Links 와
iOS Universal Links 모두 **도메인에 검증 파일**을 올려야 합니다 — IP 로는 안 됩니다.
**도메인이 생긴 뒤.** 그 전까지 QR 은 웹으로 열리고, 그걸로 충분합니다.

---

## 7. 단계와 순서

| 단계 | 내용 | 배포 | 선행 |
|---|---|---|---|
| **A0** | OMS 공개 닫기(sudo, 밖에서 확인) + channel-order CORS | ufw, channel-order | — |
| **A1** | 플랫폼 층 (웹 동작 그대로) | channel-order | — |
| **A2** ✅ | 회원 토큰 + 이력 + 기기 연결 + claim_token 원장 기록 — 2026-09-29 (Order `1b5cf9a`, APP-SERVER `dcd4363`, channel-order `194a73a`). claim_token 은 `app_order_header` 가 아니라 **`app_oms_order_map`** 에(OMS 가 돌려준 값끼리 한 곳에) | APP-SERVER, Order(owner 주소), channel-order | A0 |
| **A3** | Android 앱 껍데기 (APK) | — | A0, A1 |
| **A4** 🔶 | 푸시 (Redis 구독 + FCM) — **서버 쪽 구현**(APP-SERVER `fd203dd`): 구독·대상 고르기·문구·FCM 발송·없는 토큰 지우기. **꺼져 있음** — `PUSH_EVENTS_REDIS=true` 와 `FCM_PROJECT_ID`·`FCM_CREDENTIALS_FILE` 이 있어야 켜짐. FCM 은 `google-auth-library` 대신 JDK 만으로(서비스 계정 JWT). Spring Data Redis(Order 와 같은 것)로 구독 — 설계의 redisson 대신. 앱 쪽(권한·토큰 등록)은 A3 뒤 | APP-SERVER, 앱 | A2, A3 |
| **A5** | iOS | — | 도메인·TLS, Mac |
| **A6** | 딥링크 | — | 도메인 |
| **K** | 키오스크 화면 모드 | channel-order, 셸 | store-app 7단계 |

**A0 을 먼저 하십시오.** 앱을 내보낸 뒤에 OMS 가 열려 있으면, 손님 수가 늘수록
노출되는 주문도 늘어납니다.

---

## 8. 하지 말 것

1. **PIN 을 푸시·이력·앱 화면에 넣지 않는다** — 수령을 막는 값입니다. 종이
   영수증에만.
2. **회원번호만으로 이력을 보여주지 않는다** — 토큰으로만.
3. **키오스크에 회원 토큰을 남기지 않는다** — 여럿이 쓰는 기계입니다.
4. **CORS 를 `*` 로 열지 않는다.**
5. **가격을 화면이 정하지 않는다** — 지금처럼 id·수량만 보냅니다(9/18).
6. **푸시 권한은 켜자마자 묻지 않는다** — 첫 주문 뒤에.
7. **평문 HTTP 예외는 그 주소 하나만, 그리고 TLS 가 생기면 지운다.**
8. **새 비밀(FCM 서비스 계정)은 `secrets.md` 에, 새 env(`REDIS_URL` 등)는
   `docker-start-all.sh` 에도.**
9. **Spring 생성자 둘이면 `@Autowired` + 배선 시험** — APP-SERVER 는 이미 한 번 죽었습니다.
10. **확인은 밖에서** — 공개·CORS·앱 접속은 LAN 에서 확인하면 NAT 루프백에 속습니다.
