*🇬🇧 English version: [DESIGN.md](DESIGN.md)*

# sun-moon-java-platform — 설계서

2026-09-09 기준, 이 저장소의 as-built 설계다. 실제로 존재하고 동작하는
것을 설명하며, 없는 것은 없다고 분명히 적는다 — 마지막의 "알려진 공백"
절은 나중에 덧붙인 게 아니라 설계의 일부다.

각 결정의 *이유*는 [`docs/adr/`](adr/)에 있고, 이 문서는 그 결과로 나온
시스템을 설명한다. 본문 곳곳에 해당 ADR을 참조해뒀다.

> **저장소 이름에 대한 주의**: GitHub의 `sun-moon-java-platform`에는 서로
> 무관한 두 코드베이스가 브랜치로 나뉘어 있다. `main`은 기존에 있던 Spring
> Boot 기반 microservices 시스템(Order/KDS/Delivery)이고, `master` — 이
> 브랜치 — 가 여기서 설명하는, 처음부터 새로 만든 Spring 없는 구현이다.

---

## 1. 이것이 무엇인가

Java **Enterprise Runtime Platform**: 하나의 JVM 프로세스가 REST API,
Back Office(BO) 관리 API, WebSocket transport, raw TCP Socket transport,
Batch 엔진을 함께 호스팅한다. DDD 구조, Netty 기반, 동시 접속자
1,000~10,000명 목표, 그리고 **Spring을 전혀 쓰지 않는다**
(ADR-0002, `Alignment`의 ADR-0010).

`sun-moon-python-platform`, `sun-moon-c-server`와 같은 `sun-moon-*`
계열의 Java 구성원이다. 세 저장소가 같은 아키텍처 아이디어를 각각
Python, C, Java로 증명한다.

## 2. 실제로 설계를 이끄는 원칙

1. **하나의 runtime, 여러 transport.** Socket, REST, WebSocket, Batch가
   한 프로세스 안에서 Netty event-loop group 한 쌍을 공유한다(ADR-0002).
   port는 프로세스가 아니라 *노출 경계*를 나눈다(ADR-0009).
2. **Framework의 마법 없음.** DI container 없음, annotation scanning 없음,
   Spring Batch 없음. 모든 결선은 composition root(`Bootstrap.java`)
   한 곳에서 손으로 하고, Job/Step/Chunk 엔진도 직접 작성했다. 대가는
   코드가 늘어나는 것이고, 이득은 모든 메커니즘이 눈에 보인다는 것이다.
3. **Ports and adapters, 기본값은 fake.** 모든 외부 의존(DB, cache,
   message bus, session store)은 domain이 소유한 interface 뒤에 있고
   구현이 둘이다 — 기본으로 결선되고 테스트가 덮는 in-memory fake, 그리고
   컴파일은 되지만 이 환경에서 **라이브 검증되지 않은** 실제 adapter
   (ADR-0003, ADR-0005). JDK만 있는 머신에서 `./gradlew run`과
   `./gradlew test`가 그대로 동작한다.
4. **검증된 것만 검증됐다고 쓴다.** 문서의 주장은 "실행 중인 서버 대상으로
   테스트됨"과 "컴파일됨"을 구분한다. §11 참고.

## 3. Runtime 구성

프로세스 하나. `NioEventLoopGroup` boss(1 thread) + worker group(Netty
기본값: 코어 수 × 2) 한 쌍을 모든 listener가 공유한다.

```
                       ┌──────────────────────── JVM 하나 ────────────────────────┐
                       │                                                          │
  BO Frontend  ──8080──┼─▶ HTTP listener "BO"        ─┐                           │
  (추후 SPA)           │     /health /metrics /bo/*   │                           │
                       │                              │                           │
  API client   ──8083──┼─▶ HTTP listener "Order API" ─┼─▶ 공유 boss + worker      │
  WS client            │     /health /orders /ws      │   event-loop group        │
                       │                              │                           │
  장비         ──9090──┼─▶ Socket listener           ─┘                           │
                       │                                                          │
                       │   Quartz scheduler ─▶ Batch 엔진 (자체 thread pool)      │
                       └──────────────────────────────────────────────────────────┘
```

| Port | Listener | 서비스하는 것 | WebSocket | 환경변수 |
|---|---|---|---|---|
| 8080 | BO | `/health`, `/metrics`, `/bo/*` | 없음 | `BO_PORT` |
| 8083 | Order API | `/health`, `/orders`, `/ws` | 있음 | `API_PORT` |
| 9090 | Socket | raw TCP (echo) | — | `SOCKET_PORT` |

`/health`를 두 HTTP listener 모두에 둔 건 의도적이다 — 각각 독립적으로
probe할 수 있어야 한다. 배포 시 공개되는 port가 BO이고(ADR-0007), 그래서
`/metrics`도 BO 쪽에 있다.

**Threading.** Netty event-loop thread가 codec, router, 그리고 endpoint
handler 자체를 실행한다. Batch는 Quartz 자체 `SimpleThreadPool`에서
event loop 바깥으로 완전히 분리돼 돌아간다. 현재 **blocking 작업을 event
loop 밖으로 넘기는 처리는 없다** — §12의 공백 1번 참고.

## 4. 계층 구조

Hexagonal 구조를 `com.sunmoon.platform` 아래 package로 표현했다:

```
transport/          inbound adapter — Netty pipeline, routing, endpoint
  http/             REST: router, listener spec, 공용 response/validation helper
  http/bo/          BO 인증 + 권한 decorator
  http/bo/commoncode/, http/bo/device/   BO 화면
  ws/, socket/      WebSocket, raw TCP handler
domain/             모델 + 그것이 소유한 port (interface만, framework 타입 없음)
  order/ operator/ commoncode/ device/
infrastructure/     domain port를 구현하는 outbound adapter
  persistence/      MyBatis+Postgres(실제) / in-memory(fake)
  auth/             session store, BCrypt 해싱
  cache/            Redisson(실제) / in-memory(fake)
  messaging/        Kafka(실제) / in-memory(fake)
batch/              Job/Step/Chunk 엔진 + Quartz 스케줄링
observability/      Micrometer registry, OpenTelemetry tracer
core/               CoreRuntime(listener 바인딩), RuntimeConfig
Bootstrap.java      composition root — 구현체를 고르는 유일한 장소
```

**의존 규칙.** `domain`은 JDK 외에 아무것도 의존하지 않는다. `transport`와
`infrastructure`는 둘 다 `domain`을 의존하되 서로는 의존하지 않는다. 어떤
adapter가 어떤 port를 구현하는지 아는 곳은 `Bootstrap` 하나뿐이라, 전체
시스템을 fake에서 실제 인프라로 바꾸는 건 그 파일 하나를 고치는 일이다.

Domain 모델은 Java `record`다. Port는 해당 domain package 안에 선언된
interface다(예: `domain.device.DeviceRepository`).

## 5. 요청 처리 흐름

BO 요청 하나가 처리되는 전 과정:

```
TCP → HttpServerCodec → HttpObjectAggregator → [WebSocketServerProtocolHandler]
    → RestRequestRouter → AuthorizedEndpoint → 실제 RestEndpoint → domain port → adapter
```

- **Routing** (`RestRequestRouter`)은 `RouteKey(HttpMethod, path)`로
  매칭하며 query string은 제거한다(ADR-0008). Path variable은 없다 —
  대상 행의 key는 request body(`DELETE`)나 query string(`?group=`,
  `?type=`)으로 전달한다. 매칭 실패 시 404.
- **Authorization** (`AuthorizedEndpoint`)은 decorator라서 endpoint 자체는
  인증을 전혀 언급하지 않는다. session cookie를 확인한 뒤, 운영자가
  super admin이거나 해당 `Screen`에 대한 권한이 그 `Action`을 허용하면
  통과시킨다. 아니면 401(session 없음) 또는 403.
- **Endpoint**는 단일 메서드 interface
  (`RestEndpoint: FullHttpRequest → FullHttpResponse`)를 구현하며, body를
  파싱·검증하고 domain port를 호출한 뒤 `JsonResponses`로 JSON을 반환한다.

## 6. Back Office

### 6.1 인증 — JWT가 아니라 서버 측 session

로그인하면 서버가 보관하는 불투명한 session id를 발급하고, `/bo` 경로로
한정된 `HttpOnly` + `SameSite=Lax` cookie로 내려준다. 비밀번호는 BCrypt
해시다(cost 12, `at.favre.lib:bcrypt`).

JWT 대신 session을 고른 이유는 계정이 탈취되거나 퇴사한 운영자를 레코드
하나 지워서 **즉시** 차단할 수 있어야 하기 때문이다 — 관리자 화면에는
그게 필요하고, 단일 프로세스인 동안에는 JWT의 stateless 이점이 실익이
없다(ADR-0004). 권한은 매 요청마다 다시 읽지 않고 로그인 시 한 번 읽어
session에 캐싱한다.

| Endpoint | 용도 |
|---|---|
| `POST /bo/auth/login` | 자격 검증, session 생성, cookie 설정 |
| `POST /bo/auth/logout` | 서버 측 session 삭제, cookie 만료 |
| `GET /bo/auth/me` | 현재 운영자 + 접근 가능 화면 (Frontend 메뉴 구성용) |

**최초 운영자 문제.** BO에서 운영자를 관리하려면 로그인이 필요하므로,
`Bootstrap`이 `BO_ADMIN_USERNAME` / `BO_ADMIN_PASSWORD`로 super admin을
하나 seed한다 — 단, 운영자가 하나도 없을 때만, 그리고 추측 가능한
기본값은 절대 쓰지 않는다. 환경변수가 없으면 경고만 남기고 아무것도
만들지 않는다.

### 6.2 권한 모델 — 3단계

1. **전체관리자**(`Operator.superAdmin`) — 모든 검사를 건너뛴다.
2. **화면별** — `Screen`은 테이블이 아니라 코드로 정의된 enum
   (`COMMON_CODE`, `DEVICE`, `OPERATOR`)이다. 화면 목록은 운영자의
   행위가 아니라 배포로 바뀌기 때문이다.
3. **화면 안의 액션별** — `OperatorScreenPermission`의 독립적인 boolean
   네 개: `canView` / `canCreate` / `canSave` / `canDelete`
   (조회 / 신규 / 저장 / 삭제).

HTTP 메서드 하나가 액션 하나에 정확히 대응하며, CRUD에 메서드 인식
라우팅이 필요한 이유가 바로 이것이다:

| Method | Action | 의미 |
|---|---|---|
| `GET` | `VIEW` | 목록 조회(필터 가능) |
| `POST` | `CREATE` | 추가; key가 이미 있으면 409 |
| `PUT` | `SAVE` | 수정; key가 없으면 404 |
| `DELETE` | `DELETE` | body의 key로 삭제 |

`create`와 `save`를 upsert로 합치지 않고 별개 연산으로 둔 것은 신규와
저장이 별개의 권한이기 때문이다.

### 6.3 화면

| 화면 | Endpoint | 모델 |
|---|---|---|
| 공통코드 | `/bo/common-code` (+`?group=`) | `CommonCode(groupCode, code, name, sortOrder, active)`, PK `(groupCode, code)` |
| 장비 | `/bo/devices` (+`?type=`) | `Device(deviceId, name, deviceType, location, active)`, PK `deviceId` |

`Device.deviceType`은 `DEVICE_TYPE` 공통코드를 참조하는 값이지만
foreign key로 **묶지 않았다**. 공통코드는 런타임에 수정 가능하고, 참조가
끊겼다고 장비 행 자체를 읽지 못하게 되면 곤란하다.

**장비는 마스터 데이터만 다룬다.** 접속 상태, handshake, 프로토콜은 아직
존재하지 않는 별도의 **Device Server** 몫이다(ADR-0004). BO는 살아있는
연결을 건드리지 않는다.

## 7. Batch

Spring Batch의 chunk 지향 모델을 직접 구현한 것이다 — Spring을 뺐기
때문에 직접 썼고, `sun-moon-c-server`가 C에서 함수 포인터 구조체로 같은
패턴을 구현한 것과 대응된다.

```
BatchJob ──▶ Step (ChunkStep<I,O>)
                 ├─ ItemReader<I>      readChunk(offset, size) → 빈 리스트면 step 종료
                 ├─ ItemProcessor<I,O> 항목별 처리
                 └─ ItemWriter<O>      chunk당 한 번 (commit interval)
```

`ChunkStep`은 reader가 빈 결과를 줄 때까지 읽기 → 처리 → 쓰기를 반복하며
항목 수와 chunk 수를 집계하고, `RuntimeException`이 나면 실패한
`StepExecution`으로 담는다. `BatchJob`은 step을 순서대로 실행하고 첫
실패에서 멈춘다. 결과는 `JobExecution` / `StepExecution`으로 드러난다.

**Quartz는 트리거일 뿐이다.** `BatchScheduler`가 `JobDataMap`과
`RAMJobStore`를 통해 `Runnable`을 예약하고, Job/Step/Chunk 모델 자체는
전부 우리 것이다. 현재의 구체 job은 `OrderSummaryBatchJob`
(`OrderRepository`를 통해 chunk마다 매출을 합산)이며, Python의
`batch-service`와 C의 `batch_runner`가 구현한 것과 같은 job이다 —
언어 간 비교를 위해 의도적으로 같게 맞췄다.

## 8. Persistence와 데이터 모델

MyBatis(annotation SQL) → HikariCP → PostgreSQL, 스키마는 Flyway.
Oracle 대신 Postgres로 바꾼 이유는 무료 호스팅에 배포 가능한 쪽이
Postgres이기 때문이다(ADR-0005).

각 aggregate는 같은 3파일 구조를 따른다 — MyBatis가 채울 가변 POJO
`*Row`, SQL을 담은 `*Mapper` interface, row를 domain record로 옮기는
`MyBatis*Repository`.

| Migration | 테이블 | Key |
|---|---|---|
| `V1` | `orders` | `id` |
| `V2` | `operators`, `operator_screen_permissions` | `id`; `(operator_id, screen)` |
| `V3` | `common_codes` | `(group_code, code)` |
| `V4` | `devices` | `device_id` |

접속 정보는 `POSTGRES_JDBC_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD` /
`POSTGRES_POOL_SIZE`에서 읽는다.

**이 중 어느 것도 실제 DB에서 실행된 적이 없다.** §12의 공백 2번 참고.

## 9. 횡단 관심사

| 관심사 | 메커니즘 | 상태 |
|---|---|---|
| 설정 | 환경변수 (`RuntimeConfig`, `*Settings.fromEnv()`) | 동작 |
| 로깅 | Logback → 콘솔 | 동작 |
| Metrics | Micrometer `PrometheusMeterRegistry`, `GET /metrics`로 스크레이프 | 동작, 검증됨 |
| Tracing | OpenTelemetry SDK + 로깅 span exporter | 동작, 검증됨 |
| 검증 | Jakarta Bean Validation (Hibernate Validator), `RequestValidation` | 동작, 검증됨 |
| 복원력 | Resilience4j circuit breaker (이벤트 발행 호출에 적용) | 한 경로에만 적용됨 |
| Cache / lock | `CacheClient` port, Redisson adapter 존재 | 실제로는 fake만 사용 |
| Event bus | `EventPublisher` port, Kafka adapter 존재 | 실제로는 fake만 사용 |

## 10. 설정 항목

| 변수 | 기본값 | 용도 |
|---|---|---|
| `BO_PORT` | `8080` | BO listener |
| `API_PORT` | `8083` | Order API listener |
| `SOCKET_PORT` | `9090` | raw Socket listener |
| `BO_ADMIN_USERNAME` / `BO_ADMIN_PASSWORD` | 없음 | 최초 super admin, 운영자가 없을 때만 seed |
| `POSTGRES_JDBC_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE` | localhost / `app` / `app` / `5` | DB (fake 결선 중에는 미사용) |
| `REDIS_URL` | `redis://localhost:6379` | Redisson (fake 결선 중에는 미사용) |

## 11. 테스트 전략

23개 테스트가 모두 통과하며, 두 가지 방식이다:

- **라이브 HTTP 테스트.** `CoreRuntimeTransportsTest`, `BoAuthTest`,
  `CommonCodeCrudTest`, `DeviceCrudTest`는 실제 `CoreRuntime`을 테스트
  port로 띄우고 진짜 client로 두드린다 — `java.net.http.HttpClient`
  (WebSocket client와 cookie manager 포함), 그리고 raw
  `java.net.Socket`. mock이 없고, 실제 Netty pipeline·라우팅·session
  cookie·권한 검사를 그대로 통과한다.
- **단위 테스트** — transport가 필요 없는 부분: Batch 엔진(직접 실행과
  실제 Quartz scheduler 경유 둘 다), in-memory adapter들.

의도적으로 없는 것: MyBatis/Redisson/Kafka adapter에 대한 테스트.
Testcontainers가 자연스러운 도구지만 Docker가 필요하고, 이 환경에는
없다(ADR-0003).

## 12. 알려진 공백

운영에서 먼저 문제가 될 순서대로 정리했다.

1. **Blocking 작업이 event loop에서 실행된다.** Endpoint는 Netty
   event-loop thread에서 실행되며 repository를 동기로 호출한다. in-memory
   fake에서는 무해하지만 JDBC는 blocking이다. 실제 `MyBatis*Repository`를
   지금 상태로 결선하면 쿼리마다 event-loop thread 하나가 묶여서, 처리량이
   1,000~10,000 동시 접속 목표에 한참 못 미치게 된다. **실제 DB adapter를
   결선하기 전에 worker thread로 넘기는 처리(endpoint → executor → 결과를
   channel에 write)가 반드시 필요하다.** 이 제약은 초기에 이미 식별됐지만
   (ADR-0002의 설계 노트) 아직 구현되지 않았다.
2. **실제 DB 검증이 없다.** 모든 MyBatis mapper, Flyway migration 4개,
   `MyBatisConfig`가 컴파일은 되지만 실제 PostgreSQL에서 실행된 적이
   없다. 첫 실전은 Render+Neon 배포가 될 것이다(ADR-0007).
3. **`SessionStore`에 실제 adapter가 없다.** `InMemorySessionStore`만
   있어서 session이 프로세스와 함께 사라지고 인스턴스 간 공유도 안 된다.
   `RedisSessionStore`가 예정된 실제 adapter다.
4. **운영자 관리 화면이 없다.** 운영자와 권한은 시작 시 seed나 repository
   직접 호출(`InMemoryOperatorRepository.grantPermission`, 테스트 전용)로만
   만들 수 있다. `OPERATOR` 화면 enum 값은 있지만 그 뒤에 아무것도 없다.
5. **Cookie `Secure` 플래그가 꺼져 있다.** 로컬 개발이 평문 HTTP라서
   그렇고, TLS로 서비스되는 순간 켜야 한다.
6. **CORS 처리가 없다.** Frontend가 별도 origin에서 `credentials: include`로
   BO를 호출하는 순간 필요해진다.
7. **Socket과 WebSocket transport는 echo handler다.** transport가
   동작한다는 것만 증명하며, 프로토콜은 없다.
8. **배포되지 않았다.** Render + Neon으로 정했지만(ADR-0007) `Dockerfile`도
   아직 없고, 노트북 밖에서 돌아간 적이 없다.

## 13. 결정 색인

| ADR | 결정 |
|---|---|
| [0001](adr/0001-use-adrs-for-decisions.md) | 결정을 ADR로 기록한다 |
| [0002](adr/0002-ddd-enterprise-runtime-platform-on-netty-no-spring.md) | Spring 없는 Netty 기반 DDD runtime |
| [0003](adr/0003-fakes-by-default-for-unavailable-infra.md) | Port + in-memory fake를 기본값으로 |
| [0004](adr/0004-bo-endpoints-session-auth.md) | BO 범위, session 인증, 3단계 권한 |
| [0005](adr/0005-switch-oracle-to-postgres.md) | Oracle → PostgreSQL, 로컬 DB 미설치 |
| [0006](adr/0006-bo-auth-built-and-verified.md) | BO 인증 구현 및 검증 |
| [0007](adr/0007-deployment-target-render-neon-free-forever.md) | Render + Neon 배포 |
| [0008](adr/0008-common-code-crud-and-method-aware-routing.md) | 공통코드 CRUD, 메서드 인식 라우팅 |
| [0009](adr/0009-device-crud-and-port-separation.md) | 장비 CRUD, BO/API port 분리 |

이 저장소가 왜 존재하는지에 대한 커리어 전략 맥락은 별도의 `Alignment`
저장소에 있다.
