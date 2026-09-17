# ADR-0014: 기준정보 화면은 코드가 아니라 정의다

- **Status**: Accepted
- **Date**: 2026-09-18
- **Deciders**: Project owner

## Context

Sun&Moon: **"BO 관련에 대해서 공통으로 만들어서 CRUD 를 가능하게 만드는
부분에 대해서 한번 고민을 해주세요."**

The instinct is right, and the arithmetic says why. `Business_Requirements_
Document.md` §6.3 lists sixteen screen-code prefixes. §6.2 lists the
기준정보 alone as 회사·조직·브랜드·거래처 / 점포와 운영 형태 / 품목·
레시피·메뉴·가격 / 메뉴 분류·세트·부가 메뉴 / 결품과 이력 / 카드사·
결제·할인 수단 / 공통·지역·달력 코드. That is dozens of screens whose
entire job is **목록·검색·입력·저장**.

BO already had eleven hand-built screens, and they are near-identical:
a controller with four methods, a JDBC repository, a React page with a
table and a form. Writing the next forty the same way means writing the
same code forty times — and **forty copies means forty places that differ
slightly**. Some would record who changed what; some would not. Some
would validate a required field; some would let a blank through. Nobody
would notice until the day it mattered.

## Decision

기준정보 화면은 **데이터**다. `MasterCatalog` 에 정의 한 줄을 더하는
것이 화면 하나를 더하는 것이고, controller 도 repository 도 page 도
새로 만들지 않는다.

```
MasterDefinition(code, label, table, idColumn, screen, fields, orderBy)
MasterField(name, label, type, required, searchable, editable, codeGroup)
```

- Server: one `MasterController` at `/api/master/{code}`, one
  `JdbcMasterRepository` that builds SQL from the definition, one
  `MasterService` that filters and coerces what the screen sent.
- Client: one `MasterPage` at `/master/:code`. It fetches the definition
  and draws itself — columns, input types, search fields.
- First four: 회사, 브랜드, 거래처, 카드사. `master_audit` for all of
  them, for free.

### 무엇에 쓰고 무엇에 안 쓰는가 — this is the actual decision

The framework is the easy part. The judgement is the boundary, and
getting it wrong is how a system becomes "everything is a row in a grid."

**씁니다** — 사람이 값을 적어 두는 표. The screen's whole job is list,
search, enter, save. 회사, 브랜드, 거래처, 카드사, 코드류.

**안 씁니다** — **무엇이 일어나는** 화면:

| 화면 | 왜 아닌가 |
| --- | --- |
| 결품 | 격자가 아니라 **눌러서 걸고 푸는 타일**입니다. 장사 중에 몇 번씩 누르는 화면이라 행을 열고 저장을 누르는 모양이면 안 씁니다 |
| 개점 현황 | 현재 상태와 이력이 한 화면이고, 이력에는 무시된 전문이 있습니다 |
| Live Check | 다섯 축을 나란히 보는 것이 화면의 존재 이유입니다 |
| 매출 조회 | 일자×매장으로 접었다 펴고, 그 안에 주문 줄이 또 있습니다 |
| 메뉴 | 가격에 기간 이력이 붙어 있습니다 |
| 장비 | 접속 확인 버튼이 있습니다 — 표가 아니라 동작입니다 |
| 매장 배치도 | QR을 만들고 그림을 그립니다 |

These already exist and are hand-built. Converting them would not save
code; it would **lose the reason each of them looks the way it does**.

### 안전장치는 한 줄이다

> **SQL에 들어가는 이름은 전부 정의에서 온다.**

Table name, column names, order-by — none of it comes from the request.
Values are the opposite: always parameters, never concatenated. Names from
the definition, values from `?`. That single rule is what separates
"generic CRUD" from "an endpoint that edits any table in the database",
and it is why `MasterDefinition.field(name)` returns `Optional`: an
unknown column name is **dropped**, not passed through.

A filter on a column the definition does not mark `searchable` is also
dropped — silently, not with a 400. An old screen holding an old filter
should lose the filter, not the whole list.

### 감사로그가 가장 큰 값어치다

§6.2 asks for 변경 로그. Hand-built, that is forty more places to
remember. Through one path it is written **once** and applies to
everything, in the same transaction as the write — because splitting them
produces both "changed with no record" and "record with no change," and
both waste someone's afternoon later.

It stores the value **before** as well as after. Only-after answers "what
is it now", which the row already answers. 지운 행은 더 분명합니다: 값을
안 남기면 무엇이 있었는지 아무도 모르고, 기준정보는 다른 데이터가
참조하고 있어서 그 질문이 반드시 나옵니다.

### 타입을 포기한 자리

`Map<String, Object>` 입니다. 표마다 record 를 만들면 컴파일러가
지켜 주지만 그러면 표마다 파일이 하나씩 늘어 **공통화의 의미가
사라집니다**. 안전은 정의가 봅니다 — 어떤 칸이 있고, 무슨 종류이고,
무엇이 필수인지.

This is the honest trade: the compiler stops helping at the boundary, and
the definition takes over. It is only acceptable *because* the boundary is
narrow — four endpoints, one repository, one page.

## Consequences

- 새 기준정보 = `schema.sql` 에 표 + `MasterCatalog` 에 정의. 두 곳입니다.
- 감사로그·필수검사·타입변환·중복키 409·참조 위반 409가 전부 공짜로 붙습니다.
- 화면이 서버 정의를 읽으므로, 칸을 하나 늘려도 **프런트엔드 배포가
  필요 없습니다** — 서버만 올라가면 화면이 그 칸을 그립니다.
- 반대로 화면을 **특별하게** 만들 수 없습니다. 그게 필요한 순간이
  이 구조에서 나가야 하는 순간이고, 나가는 길은 열려 있습니다(손으로
  만든 page 를 라우트에 붙이면 됩니다).

## 알고 두는 한계

1. **권한이 한 겹입니다.** 모든 기준정보가 `Screen.MASTER` 하나를
   공유하므로, 브랜드를 고칠 수 있는 사람은 거래처도 고칩니다.
   `MasterDefinition` 이 자기 `Screen` 을 가리킬 수 있게 자리는
   열어 뒀지만, 권한 화면이 마흔 개가 되는 것이 정말 필요한지는
   **나눠 달라는 요구가 실제로 나온 뒤에** 정하는 편이 낫습니다.
2. **복합키를 못 다룹니다.** `idColumn` 이 하나입니다. `common_codes`
   가 (group_code, code) 라서 그 화면은 지금도 손으로 만든 것이고,
   그게 이 한계가 실제로 무엇을 막는지 보여주는 예입니다.
3. **목록이 500행에서 잘립니다.** 페이지가 없습니다. 기준정보는 보통
   그보다 적지만 품목·메뉴는 아닐 수 있고, 그때 조용히 잘리는 것이
   제일 나쁜 모양입니다 — 잘렸다는 것을 화면이 말해 주는 것이 먼저입니다.
4. **Excel 업로드·다운로드가 없습니다** (§6.2). 공통 CRUD의 다음
   후보이고, 여기서 하면 역시 한 번만 만들면 됩니다.
5. **CODE 타입이 아직 그냥 글자 입력입니다.** 정의는 `codeGroup` 을
   들고 있지만 화면이 그걸로 선택 목록을 그리지 않습니다.

## 다음에 생각할 것 — "모든 것을 공통으로"

Sun&Moon's larger aim: **"가능하면 모든것을 다 공통으로 해서 하는 작업을
다시 한번 총 정리 하고 싶어요."**

This ADR is one instance of that, and it suggests the shape of the rest.
The parts of this platform that repeat are not random; they are the same
four, over and over:

| 되풀이되는 것 | 지금 | 공통화한다면 |
| --- | --- | --- |
| 기준정보 CRUD | **이 ADR** | 했음 |
| 업스트림 읽기 | `Http*Repository` 넷이 거의 같습니다 (매출, 단말, APP 원장, POS 운영) | 못 닿음→502 규칙과 타임아웃을 한 곳에 |
| 배포와 적용 결과 | 마스터 IRT(§3.6)와 프로그램 배포(§3.5)가 **같은 모양**입니다 — 목표 버전, 장비별 적용, 재전송 목록 | "무엇을 누구에게 내려보내고 적용됐는지"를 하나로 |
| 원본 받고 나중에 푸는 것 | TR 매출(§3.1~3.2). ETC 매출(§3.3)이 같은 모양으로 올 예정 | 수신 로그 + 분해 배치 + 실패 재처리를 하나로 |
| 재처리 | **없습니다** | 실패한 것을 다시 돌리는 자리가 하나 |

The last row is the one to do next, because it is missing everywhere:
분해 실패도, 마스터 적용 실패도, 프로그램 설치 실패도 지금은 기록만
되고 되돌릴 길이 없습니다.

A word of caution to carry into that work: 공통화는 **되풀이되는 것**에
쓰는 것이지 비슷해 보이는 것에 쓰는 것이 아닙니다. 이 ADR의 표에서
"안 씁니다" 칸이 "씁니다" 칸보다 긴 데는 이유가 있습니다.
