# 설계 — 단말 화면 셋을 한 저장소로 (POS · KDS · DID)

- 결정: 2026-10-03 Sun&Moon — **안 ② 한 저장소** ([HANDOFF-2026-10-03](../HANDOFF-2026-10-03.md) §4-1)
- 상태: **구현·배포 완료 (2026-10-03)** — [sun-moon-terminals](https://github.com/schware/sun-moon-terminals), 옛 저장소 셋은 archive.
  §2 의 "빌드 없는 공통 패키지" 방식이 그대로 됐습니다(각 화면의 `tsc -b` 가 공통 파일의 오류도 잡는 것을 확인) — §6-1 대안은 쓰지 않음.
- 범위: 웹 단말 화면 세 저장소(`sun-moon-terminal-pos`, `-kds`, `-did`). **셸(`sun-moon-store-app`)과 손님 화면
  (`channel-order`)은 옮기지 않습니다** — 셸은 Electron 이라 빌드·배포가 다르고, 손님 화면은 Spring 서비스 안에 있습니다.

---

## 1. 왜

세 화면의 통신 코드(`src/lib/deviceServer.ts`, `useTerminalSocket.ts`)가 **복사본 셋**입니다. 2026-10-03 실측:

| | POS | KDS | DID |
|---|---|---|---|
| `deviceServer.ts` 줄 수 | 464 | 414 | 261 |
| 내보내는 이름 | 30 | 30 | 17 |
| `useTerminalSocket.ts` | 100줄 — 셋 사이 약 80줄이 같음 | | |

**셋 모두에 있는 이름 17개** 중 10개는 글자까지 같고, 7개는 조금씩 다릅니다(§3). 그동안 한 화면만 고쳐서 다른
화면이 뒤처진 일이 실제로 있었습니다 — 예: 2026-10-01 페어링을 걷어낼 때 같은 수정을 세 번 했고, `pairing.ts` 는
"고치면 셋 다 고치십시오" 라는 주석으로 버텼습니다.

## 2. 모양

```text
sun-moon-terminals/                  ← 새 저장소 (private)
├── package.json                     private, "workspaces": ["common", "pos", "kds", "did"]
├── package-lock.json                하나 (npm workspaces)
├── tsconfig.base.json               세 화면이 같이 쓰는 컴파일 옵션 (strict, noUnused* …)
├── README.md
├── common/                          @sunmoon/terminal-common — 빌드 단계 없음, TS 원본 그대로
│   ├── package.json                 "exports": { "./device-server": "./src/deviceServer.ts", … }
│   └── src/
│       ├── deviceServer.ts          §3 의 공통 부분
│       ├── useTerminalSocket.ts
│       ├── shell.ts                 지금 POS·KDS 에 같은 복사본
│       └── format.ts                formatAmount · formatTime · tableLabel
├── pos/                             지금 sun-moon-terminal-pos 그대로 (+ 공통을 import)
│   ├── package.json                 name "pos", 의존성 "@sunmoon/terminal-common": "*"
│   ├── vite.config.ts               base: '/pos/' 그대로
│   └── src/lib/                     POS 만의 것: ledger.ts · trSale.ts · claimQr.ts · posApi.ts(§3)
├── kds/                             src/lib/kdsApi.ts(§3)
└── did/
```

- **npm workspaces** 를 씁니다. 지금 세 화면이 npm·`package-lock.json` 을 쓰고 있어서 도구를 바꾸지 않습니다.
- **공통 패키지는 빌드하지 않습니다.** `exports` 가 `.ts` 원본을 가리키고, 각 화면의 Vite 가 원본째 가져다
  번들에 넣습니다(작업 공간 패키지는 Vite 가 미리 묶지 않고 소스로 다룹니다). 타입 검사도 각 화면의 `tsc -b` 가
  import 를 따라가 함께 합니다. → **§7 단계 2 에서 이 동작을 먼저 확인**하고, 안 되면 대안(§6-1)으로.
- 결과물 위치는 그대로입니다: `pos/dist`, `kds/dist`, `did/dist`. nginx 의 `/pos/` `/kds/` `/did/` 도 그대로.

## 3. 무엇이 `common` 으로 가나

### 3-1. 그대로 옮기는 것 (셋이 글자까지 같음 — 10개)

`OrderStatus` · `BusinessDayStatus` · `MenuBoard` · `MenuBoardItem` · `fetchOrders` · `fetchBusinessDayStatus` ·
`fetchMenuBoard` · `isMenuEvent` · `formatAmount` · `formatTime` (+ POS·KDS 에 같은 `tableLabel`)

### 3-2. 합쳐서 옮기는 것 (셋 다 있지만 조금씩 다름 — 7개)

전부 **더하기** 차이라 합집합으로 합칩니다. 서버는 세 화면에 같은 주문 JSON 을 보내고, 타입은 그중 무엇을
읽느냐의 차이일 뿐입니다.

| 이름 | 차이 | 합치는 법 |
|---|---|---|
| `Order` | 공통 18 필드 + POS `claimToken`·`payment`, KDS `forceProducedBy`·`pickupEmergency`·`kdsDeviceId`, DID `calledAt`, POS·KDS `floor`·`tableNo` | **합집합.** 한쪽에만 있던 필드는 서버가 늘 보내는지 확인해 필수/선택(`?`)을 정함 |
| `OrderLine` | KDS·DID `kdsDeviceId`·`producedAt`, KDS `startedAt?` | 합집합 (`startedAt?` 는 선택 유지 — 옛 주문에 없음) |
| `TerminalEvent` | POS 공지 필드(`noticeId`·`title`·`body`·`urgent`), KDS `jobIds`·`kdsRole` | 합집합, 전부 선택 필드 |
| `STATUS_LABEL` | PRODUCED 표시가 POS "조리 완료" / KDS "조리완료" | **공통 기본값 + 화면별 덮어쓰기** (`{ ...STATUS_LABEL, PRODUCED: '조리완료' }`) — 표시는 화면의 일 |
| `changeStatus` | POS 만 `tranNo` 인자 | 인자를 합침 (`tranNo?` 선택) |
| `socketUrl` | KDS 만 `role` 인자 | 인자를 합침 (`role?` 선택) |
| `BASE` | DID 는 셸 주소를 안 봄(셸에서 안 돎) | 공통은 `shellServerUrl() ?? VITE_DEVICE_SERVER ?? 기본값` — DID 에서는 셸이 없으니 결과가 같음 |

오류 문구를 만드는 `failure()` 는 KDS 에만 403 문구("수령번호가 맞지 않습니다")가 있습니다 → 공통은 기본 문구,
**상태 코드별 덮어쓰기를 인자로** 받게 합니다.

### 3-3. 화면에 남는 것

| 화면 | 남는 것 (새 파일 이름) |
|---|---|
| POS | `posApi.ts` — 매출 올리기, 공지·확인, 개점·마감, 받음 보고, `findOrder` · `ledger.ts` · `trSale.ts` · `claimQr.ts` |
| KDS | `kdsApi.ts` — 줄 시작, 강제 완료, 다시 부르기, 수령, 비상 모드, 라벨 작업·다시 찍기, `KdsRole`·`lineBelongsTo`·`isPrintEvent` |
| DID | 없음 (공통만으로 충분) |

**공통에 넣지 않는 기준**: 한 화면만 쓰는 것은 공통이 아닙니다(09-18 원칙 — "되풀이되는가"). 두 화면이 쓰더라도
업무가 다르면 넣지 않습니다.

## 4. git 이력

세 저장소의 이력을 **버리지 않습니다.** `git subtree add` 로 각 저장소를 폴더 아래로 들입니다.

```bash
git init sun-moon-terminals && cd sun-moon-terminals
git commit --allow-empty -m "단말 화면 셋을 한 저장소로 (설계: umbrella docs/design/terminal-monorepo.md)"
git subtree add --prefix=pos https://github.com/schware/sun-moon-terminal-pos.git main
git subtree add --prefix=kds https://github.com/schware/sun-moon-terminal-kds.git main
git subtree add --prefix=did https://github.com/schware/sun-moon-terminal-did.git main
```

- 추가 도구가 필요 없고(`git filter-repo` 불필요), 옛 커밋은 그대로 남습니다(경로는 옛 커밋 기준).
- **옛 저장소 셋은 지우지 않고 archive(읽기 전용)** 로 두고, README 첫 줄에 새 저장소를 가리키는 안내를 답니다.
  이력을 찾을 일이 생기면 옛 저장소에서 보면 됩니다.

## 5. 빌드 · 배포 · 연결된 곳

| 무엇 | 지금 | 바뀐 뒤 |
|---|---|---|
| 설치 | 저장소마다 `npm ci` | 루트에서 `npm ci` 한 번 |
| 빌드 | 저장소마다 `npm run build` | 루트 `npm run build`(셋 다) 또는 `npm run build -w kds` |
| 서버에 올리기 | `tar -C dist . \| ssh … /srv/sunmoon/nginx/html/kds` | `tar -C kds/dist . \| ssh …` — **올리는 곳은 그대로** |
| 셸 번들 (`sun-moon-store-app/scripts/bundle-ui.mjs`) | `../sun-moon-terminal-pos/dist`, `../sun-moon-terminal-kds/dist` | `../sun-moon-terminals/pos/dist`, `…/kds/dist` — **두 줄** |
| 문서 | Debian-Setting `development.md`·`debian13.md`, umbrella README 표 | 새 경로로 |
| 나중의 Forgejo CI | 저장소마다 | **하나** — 공통이 바뀌면 셋 다 시험·빌드 |

배포 도우미로 루트에 `scripts/deploy.sh <pos|kds|did>` (빌드 → 올리기 → 서버가 주는 번들 이름 확인)를 둡니다.
지금 손으로 치는 tar 명령을 그대로 담은 것입니다.

## 6. 정할 것과 대안

### 6-1. 공통을 원본으로 가져가는 방식이 안 될 때

§2 의 "빌드 없는 공통 패키지"가 Vite·`tsc -b` 에서 문제를 일으키면(예: 공통 파일에 화면의 tsconfig 규칙이 안 걸리거나,
타입 검사가 공통을 건너뜀), **경로 별칭**으로 바꿉니다: 각 화면의 `tsconfig.json` `paths` 와 `vite.config.ts`
`resolve.alias` 에 `@sunmoon/terminal-common → ../common/src`. 결과물은 같고 설정이 두 군데 늘 뿐입니다.

### 6-2. 정해 주실 것

| # | 질문 | 제안 |
|---|---|---|
| 1 | 새 저장소 이름 | `sun-moon-terminals` |
| 2 | 옛 저장소 셋 | archive + 안내 (지우지 않음) |
| 3 | 공통 패키지 이름 | `@sunmoon/terminal-common` (npm 에 올리지 않음 — 저장소 안에서만) |

## 7. 구현 순서 (단계마다 §8 확인 후 다음)

1. **새 저장소 만들기** — §4 의 subtree 세 번. 각 폴더에서 지금처럼 `npm ci && npm run build` 가 되는지(아직 공통 없음).
2. **workspaces 켜기** — 루트 `package.json`·`tsconfig.base.json`, 화면별 `package-lock.json` 을 지우고 루트 하나로.
   빈 `common` 패키지에 `formatAmount` 하나만 옮겨 **§2 방식이 되는지 먼저 확인**(안 되면 §6-1).
3. **그대로 옮기는 10개**(§3-1)를 common 으로. 세 화면의 import 를 바꾸고 각자 지움.
4. **합치는 7개**(§3-2) — 타입 합집합, `STATUS_LABEL` 덮어쓰기, `changeStatus`·`socketUrl` 인자, `failure()` 덮어쓰기.
5. **`useTerminalSocket.ts`·`shell.ts`** 를 common 으로 (KDS 의 `role` 인자 포함).
6. **화면별 파일 이름 정리** — `deviceServer.ts` 에 남은 것을 `posApi.ts`·`kdsApi.ts` 로. DID 는 `deviceServer.ts` 없음.
7. **배포 도우미·셸 경로·문서** (§5).
8. **하나씩 배포**: DID → KDS → POS 순(가장 단순한 것부터). 각각 §8 확인.
9. **옛 저장소 archive** + 안내.

## 8. 확인 (단계마다)

- `npm run build` 가 셋 다 통과 (`tsc -b` 포함 — 타입 오류 0)
- 화면별로: 첫 화면(단말 등록) → 매장·장비 입력 → 연결됨 표시
  - POS: 주문 목록, 수락, 개점·마감, 매출 올리기
  - KDS: 생산 화면은 내 줄만, 메인은 완료 / 조리 중 / 대기, WELCOME 의 BO 역할을 따름
  - DID: 여러 매장(`?storeId=a,b`), 다시 부르기 번호
- 배포 뒤 서버가 주는 번들 이름이 방금 빌드한 것과 같음
- 셸: `npm run ui` 가 새 경로에서 번들을 가져옴, `npm test` 통과

## 9. 되돌리기

배포한 번들만 문제면 옛 저장소에서 빌드해 다시 올리면 됩니다(옛 저장소는 archive 라 읽기만 되지만 clone·빌드는 됨).
저장소 합치기 자체는 옛 저장소를 지우지 않으므로 언제든 돌아갈 수 있습니다.
