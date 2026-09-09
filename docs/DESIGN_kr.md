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

`sun-moon-*` 계열의 **Device Server**다. Netty 위에 만든 JVM 프로세스
하나가 단말(POS, KDS, DID) 연결을 붙들고, 주문 이벤트를 그 단말들에게
라우팅한다(`docs/adr/0016`). Spring은 전혀 쓰지 않는다(ADR-0002,
`Alignment`의 ADR-0010).

이 모양이 나온 건 더 큰 야심에서 시작했다 — Socket, REST, WebSocket,
Batch를 한 프로세스에서 DDD 구조로, 동시 접속 1,000~10,000을 목표로
호스팅하는 Java **Enterprise Runtime Platform**(ADR-0002). 그 야심이
지금 만들어진 방식(raw Netty, 전부 직접 구현)을 설명하고, Device Server
역할은 그 모양으로 *무엇을 하는지*에 해당한다.

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
3. **단말에게는 "뭔가 바뀌었다"만 알리고, 바뀐 내용 자체는 안 준다.**
   WebSocket은 신호만 나르고, 단말은 REST로 실제 사실을 다시 읽는다.
   그래야 잠시 끊겼던 단말이 재접속했을 때 놓친 프레임의 재생이 아니라
   **현재 상태**로 수렴한다(ADR-0016).
4. **커밋하기 전에 신원부터 확인한다.** 단말은 WebSocket 업그레이드가
   끝나기 *전에* 검사한다 — 잘못된 ID는 읽을 수 있는 HTTP 400을 받고,
   조용히 아무것도 안 오는 소켓을 받지 않는다.
5. **Ports and adapters, 기본값은 fake** — 아직 그 방식을 쓰는 부분(레거시
   order 모듈, §8)에 한해. 컴파일은 되지만 이 환경에서 **라이브 검증되지
   않은** 실제 adapter가, 기본으로 결선되고 테스트가 덮는 in-memory fake와
   같은 interface 뒤에 있다(ADR-0003, ADR-0005). JDK만 있는 머신에서
   `./gradlew run`과 `./gradlew test`가 그대로 동작한다.
6. **검증된 것만 검증됐다고 쓴다.** 문서의 주장은 "실행 중인 서버 대상으로
   테스트됨"과 "컴파일됨"을 구분한다. §11 참고.

## 3. Runtime 구성

프로세스 하나. `NioEventLoopGroup` boss(1 thread) + worker group(Netty
기본값: 코어 수 × 2) 한 쌍을 모든 listener가 공유한다.

```
                       ┌──────────────────────── JVM 하나 ────────────────────────┐
                       │                                                          │
  단말        ──8087──┼─▶ HTTP/WS listener "API"    ─┐                           │
  (POS/KDS/DID)        │  /health /metrics            │                           │
                       │  /terminals /orders  /ws     ├─▶ 공유 boss + worker      │
                       │                              │   event-loop group        │
  (raw socket) ──9011──┼─▶ Socket listener           ─┘                           │
                       │                                                          │
                       │   Redis 'order-events' ──▶ OrderEventSubscriber          │
                       │   (기동 시 구독, 접속 단말에 push)                       │
                       │                                                          │
                       │   Quartz scheduler ─▶ Batch 엔진 (자체 thread pool)      │
                       └──────────────────────────────────────────────────────────┘
                                        │
                                        ▼ (REST 중계)
                          Spring Order service (sun-moon-java-platform-order, :8083)
```

| Port | Listener | 서비스하는 것 | WebSocket | 환경변수 |
|---|---|---|---|---|
| 8087 | API | `/health`, `/metrics`, `/terminals`, `/orders`(중계), `/ws` | 있음 | `API_PORT` |
| 9011 | Socket | raw TCP (echo) | — | `SOCKET_PORT` |

계열 전체 port 체계(ADR-0013)에서 이 runtime의 자리다 — REST API는
8081~8089 대역, Socket은 언어별로 90x1. 이 runtime이 예약해 뒀던 8083은
이제 Spring Order service의 자리로 영구히 확정됐다 — ADR-0016이 정리한
지점이다(`docs/adr/0016`).

**Threading.** Event-loop thread는 codec, WebSocket handshake 검사,
router만 실행한다. `RestEndpoint.handle()`은 **제한된 크기의 worker
pool**(`platform-worker-N`, `WORKER_THREADS`로 조절, 기본값 `코어 수 ×
4`)로 넘긴다. Order 중계 endpoint가 Spring Order service에 blocking HTTP
호출을 하기 때문이다 — event loop 위에서 그대로 돌리면 그 loop가 담당하는
모든 연결이 멈춘다(ADR-0010). 응답은 `ctx.writeAndFlush`로 돌려보내며, 이
호출이 event loop로 다시 넘겨준다. Pool이 포화되면 503, endpoint가 예외를
던지면 500. 단말 WebSocket 프레임은 event loop에서 직접 처리한다
(`TerminalFrameHandler`) — 연결 등록과 push 프레임 전송 둘 다 블로킹이
없기 때문이다. Batch는 Quartz 자체 `SimpleThreadPool`에서 event loop
바깥으로 분리돼 돌아간다.

## 4. 계층 구조

Hexagonal 구조를 `com.sunmoon.platform` 아래 package로 표현했다:

```
core/               kernel, git submodule (com.sunmoon.platform.core,
                      .transport.http, .observability, .infrastructure.persistence)
                      — listener 바인딩, REST routing, event loop 밖 실행,
                      공용 MyBatis/Flyway/Micrometer/OTel 결선
api/
  TerminalEndpoints.java      GET /terminals, POST /terminals/push|broadcast
  OrderProxyEndpoints.java    GET/POST /orders(/status) — Spring Order로 중계
  CreateOrderEndpoint.java    레거시 (§12)
domain/
  terminal/           TerminalId, TerminalGroup, TerminalType, TerminalSession,
                       TerminalRegistry, DeviceDirectory (+ AcceptKnownFormatDirectory)
  order/              레거시 (§12) — Device Server 역할 이전부터 있던 것
transport/
  ws/                 DeviceServerInitializer, TerminalHandshakeHandler,
                       TerminalFrameHandler
  socket/             raw TCP handler
infrastructure/
  order/              OrderClient — Spring Order service에 HTTP로 대화
  messaging/          OrderEventSubscriber (Redis), 레거시 EventPublisher adapter
  persistence/, cache/  domain/order 뒤의 레거시 adapter
batch/              Job/Step/Chunk 엔진 + Quartz 스케줄링
Bootstrap.java      composition root — 구현체를 고르는 유일한 장소
PlatformConfig.java port, worker pool 크기, Order service URL, Redis URL, 단말 유예 시간
```

**의존 규칙.** `domain`은 JDK 외에 아무것도 의존하지 않는다. `api`/
`transport`와 `infrastructure`는 둘 다 `domain`을 의존하되 서로는 의존하지
않는다. Kernel은 이 중 아무것도 의존하지 않는다 — 이 runtime이 조립해 쓰는
library이고, 양쪽으로 쪼개진 package가 하나도 없어서 import만 보면 그
클래스가 경계의 어느 쪽인지 알 수 있다(ADR-0014). 어떤
adapter가 어떤 port를 구현하는지 아는 곳은 `Bootstrap` 하나뿐이라, 전체
시스템을 fake에서 실제 인프라로 바꾸는 건 그 파일 하나를 고치는 일이다.

**`transport/ws`가 kernel의 `HttpServerInitializer`를 안 쓰고 자기
pipeline을 직접 조립하는 이유.** Kernel은 WebSocket에 대해 frame handler
하나만 받는다 — 이게 맞다. Handshake 이전 handler까지 받게 하면 kernel이
"단말"이 뭔지 알게 되기 때문이다(ADR-0014). `TerminalHandshakeHandler`는
WebSocket 업그레이드 *전에* 돌아야 잘못된 연결을 HTTP 400으로 거부할 수
있는데, kernel의 계약에는 그 자리가 없다. 그래서
`DeviceServerInitializer`가 `HttpServerCodec` → `HttpObjectAggregator` →
`TerminalHandshakeHandler` → `WebSocketServerProtocolHandler` →
`TerminalFrameHandler` → `RestRequestRouter`를 kernel 부품과 이 저장소
자체 handler로 직접 조립한다.

Domain 모델은 Java `record`다. Port는 해당 domain package 안에 선언된
interface다(예: `domain.terminal.DeviceDirectory`).

## 5. 요청 처리 흐름

### 단말 접속

```
TCP → HttpServerCodec → HttpObjectAggregator → TerminalHandshakeHandler
    → [거부: HTTP 400] 또는 [진행] → WebSocketServerProtocolHandler
    → TerminalFrameHandler.userEventTriggered(HandshakeComplete)
    → TerminalRegistry.register(deviceId, storeId, type, channel)
```

`TerminalHandshakeHandler`는 query string의 `deviceId`, `storeId`,
`type`을 WebSocket 업그레이드 *전에* 읽고 검사한다 — 값이 없거나
잘못됐으면 HTTP 400을 응답하고 연결을 닫는다, 업그레이드는 절대 안 한다.
Handshake가 끝나면 `TerminalFrameHandler`가 연결을 등록하고 `WELCOME`
프레임을 보낸다. 같은 `(storeId, deviceId)`에 두 번째 연결이 등록되면
첫 번째를 밀어내는데, 그 연결은 WebSocket code 4001로 닫혀서 그 클라이언트가
더 이상 갖고 있지 않은 id를 두고 재시도를 계속하지 않고 멈춘다.

### 주문 이벤트가 단말에 도달

```
Order service → Redis 'order-events' → OrderEventSubscriber.onEvent()
    → audienceFor(status)로 POS/KDS/DID 결정
    → TerminalRegistry.channelsOf(storeId, type).writeAndFlush(event)
```

대상이 비어 있고 상태가 `PLACED`면 `isPresentOrRecentlySeen`을 먼저
확인한다 — 유예 시간(기본 3분, 그 매장에서 그 종류의 단말이 마지막으로
보인 시각 기준) 안이면 이벤트를 버리고 주문은 `PLACED`로 남는다(단말이
재접속 중이라고 가정). 아니면 subscriber가 Order에 다시 호출해 거절한다
(`rejectUnattended`) — 라우팅 판단은 여기서 하지만, 전이 자체는 여전히
Order 자신의 state machine을 지난다.

### 단말이 주문을 조회하거나 처리

```
단말 → GET/POST http://<device-server>/orders(/status) → OrderProxyEndpoints
    → OrderClient (blocking HTTP, event loop 밖) → Spring Order service
    → 응답을 그대로 전달 (상태 코드 포함)
```

- **Routing**(`RestRequestRouter`)은 `RouteKey(HttpMethod, path)`로
  매칭하며 query string은 제거한다(ADR-0008). Path variable은 없다 —
  대상 행의 key는 request body(`DELETE`)나 query string(`?group=`,
  `?type=`)으로 전달한다. 매칭 실패 시 404.
- Order가 낸 409(허용 안 되는 상태 전이)는 그대로 전달된다 — Device
  Server는 그 전이가 맞는지에 대해 아무 의견도 갖지 않는다.

## 6. Back Office — 이전됨

Back Office는 이제
[**sun-moon-java-platform-bo**](https://github.com/schware/sun-moon-java-platform-bo)에
있고 설계서도 그쪽에 있다. Port 체계가 service당 container 하나를 전제하게
되고, 내부 전용인 BO가 장비를 마주하는 API 옆자리에 더는 맞지 않게 되면서
분리했다(ADR-0014).

같이 간 것: operator/commoncode/device domain, session 인증과
`AuthorizedEndpoint` 권한 decorator, migration V2~V4.

**그 뒤 한 걸음 더 갔다.** BO는 잠시 이 kernel 위의 별도 Netty service로
돌았지만, 지금은 `main` 브랜치 계열의 **Spring** service다. Netty 구현은
[sun-moon-platform-bo-netty](https://github.com/schware/sun-moon-platform-bo-netty)로
archive했다. BO는 처리량 요구가 없는 관리 화면이라, session·BCrypt·행위별
권한을 직접 만들어서 얻는 것이 `spring-boot-starter-security`가 이미 주는
것 이상은 아니었다(ADR-0015). 권한 모델 자체 — 3단계, `create`와 `save`를
나눈 것 — 는 재작성 후에도 그대로 살아남았다.

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
전부 우리 것이다. 현재의 구체 job은 `OrderSummaryBatchJob`인데, **레거시**
in-memory order 데이터를 대상으로 돈다(§8, §12) — Device Server 역할
이전부터 있던 것이고, Python의 `batch-service`·C의 `batch_runner`와의
언어 간 Batch 비교용으로 남겨뒀을 뿐, 실제 주문(지금은 전부 Spring Order
service에 있다)을 읽는 게 아니다.

## 8. Persistence와 데이터 모델

**레거시다.** `domain/order`와 그 MyBatis/Postgres adapter, Flyway
migration은 Device Server 역할(ADR-0016) 이전부터 있던 것이고 실제 주문
흐름의 일부가 아니다 — 주문은 이제 전부 `sun-moon-java-platform-order`
(Spring, PostgreSQL+JSONB, 자기 migration)에 있다. 이 모듈은
`OrderSummaryBatchJob`(§7)을 돌리기 위해서만 남아 있고, 제거 후보다(§12).

아직 결선돼 있는 부분은 MyBatis(annotation SQL) → HikariCP → PostgreSQL,
schema는 Flyway를 쓴다.

| Migration | 테이블 | Key |
|---|---|---|
| `V1` | `orders` | `id` |

접속 설정은 `POSTGRES_JDBC_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD` /
`POSTGRES_POOL_SIZE`에서 온다 — 안 주면 레거시 모듈은 in-memory fake로
돌고 Device Server의 나머지 부분은 영향받지 않는다.

**이 중 어느 것도 실제 DB에서 실행된 적이 없다** — Redis Pub/Sub
경로(§9)는 실행됐다는 점과 대비된다. §12 참고.

## 9. 횡단 관심사

| 관심사 | 메커니즘 | 상태 |
|---|---|---|
| 설정 | 환경변수 (`PlatformConfig`, `*Settings.fromEnv()`) | 동작 |
| 로깅 | Logback → console | 동작 |
| Metrics | Micrometer `PrometheusMeterRegistry`, `GET /metrics`에서 스크랩 | 동작, 검증됨 |
| Tracing | OpenTelemetry SDK, logging span exporter | 동작, 검증됨 |
| 주문 이벤트 라우팅 | `OrderEventSubscriber`, Redisson Pub/Sub, topic별 `StringCodec` | **동작, 라이브 검증됨** (ADR-0016) |
| Order로의 REST 중계 | `OrderClient` (`java.net.http.HttpClient`), event loop 밖 | 동작, 검증됨 |
| 레거시 persistence | MyBatis+Postgres, `domain/order` 뒤 | 실전에서는 fake만 |
| 레거시 cache / event bus | `CacheClient`/`EventPublisher` port, Redisson/Kafka adapter | 실전에서는 fake만 |

## 10. 설정 참조

| 변수 | 기본값 | 용도 |
|---|---|---|
| `API_PORT` | `PORT`, 없으면 `8087` | 단말/REST listener (ADR-0016) |
| `SOCKET_PORT` | `9011` | raw Socket listener |
| `WORKER_THREADS` | 코어 수 × 4 | REST endpoint(Order 중계 포함)가 도는 pool (ADR-0010) |
| `ORDER_SERVICE_URL` | `http://localhost:8083/order` | `OrderClient`가 중계 요청을 보내는 곳 |
| `REDIS_URL` | 없음 | **스위치**: 설정하면 Order의 `order-events` 채널을 구독해 단말에 push; 미설정이면 단말은 접속·조회는 되지만 알림은 안 받음 |
| `TERMINAL_GRACE` | `PT3M` (ISO-8601 duration) | 끊긴 단말이 그 매장에서 얼마나 더 "있다"고 칠지 |
| `POSTGRES_JDBC_URL` | 없음 | **레거시** `domain/order` 모듈만의 스위치 — 실제 주문과 무관 |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE` | `app` / `app` / `5` | 레거시 database 자격과 pool 크기 |

## 11. 테스트 전략

여기 24개, 전부 통과 — kernel의 3개를 더하면 27개.

- **실제 transport 테스트**(`DeviceServerTest`, 8개). 실제 `CoreRuntime`을
  실제 `DeviceServerInitializer` pipeline으로 띄우고 진짜 client로
  두드린다 — `java.net.http.HttpClient`의 WebSocket client와 raw
  `java.net.Socket`. 다루는 것: handshake 수락/거부(장비 ID 없음, 매장 ID
  없음, 알 수 없는 type), 접속한 단말이 `/terminals`에 뜨고 push를
  받는 것, 오프라인 단말에 push하면 `delivered:false`로 응답하는 것, raw
  socket echo.
- **`TerminalRegistryTest`**(9개). Registry가 매장이 유인 상태인지를
  판단하고, 그 판단이 "화면 wifi가 끊겼다"와 "주문이 거절됐다" 사이의
  경계다 — socket을 거치지 않고 직접 테스트한다. 매장 구분이 지키려는
  성질 자체를 고정한 것도 포함한다:
  `oneStoresTerminalDoesNotVouchForAnother`,
  `theSameDeviceIdInTwoStoresIsTwoTerminals`.
- **단위 테스트** — transport가 필요 없는 부분: Batch 엔진(직접 실행과
  실제 Quartz scheduler 경유 둘 다), 레거시 in-memory adapter들.

일부러 없는 것: 레거시 MyBatis/Kafka adapter 테스트, `RedissonCacheClient`
테스트(Pub/Sub 경로는 아래 라이브 검증에서 실제로 확인했다). 전자에는
Testcontainers가 자연스러운 도구인데 Docker가 필요하고 이 환경에는
없다(ADR-0003).

**테스트 스위트 밖에서, 배포된 서버 위에서 라이브로 검증한 것**: 단말이
없는 매장에 주문을 넣으면 약 0.5초 만에 자동 거절된다; `store-01/pos-01`로
접속한 실제 WebSocket client가 있는 상태에서 주문을 넣으면 `PLACED`로
남고 그 client에 push 프레임이 도착한다; 이 서버의 중계를 거쳐 수락하면
Order의 `acceptedBy`가 `pos-01`로 설정되고, 이건 이 서버 자신의 응답만이
아니라 Order를 직접 조회해서도 확인했다; 이미 수락된 주문을 다시
수락하려 하면 409로 거부된다.

## 12. 알려진 공백

프로덕션에서 먼저 물릴 순서대로.

1. **장비 신원 확인이 형식 검사뿐이다.** `AcceptKnownFormatDirectory`는
   형식이 맞는 `(storeId, deviceId)`면 다 받아준다 — 그 장비가 실제로
   있는지, 그 매장 소속이 맞는지 BO에 묻지 않는다. 진짜 구현은 이 서버와
   BO 사이의 서비스 간 인증이 필요한데 아직 정해지지 않았다(`docs/adr/0016`).
   단말 쪽 방향 하나: 매장·장비 ID를 직접 입력하는 대신 ID/PASSWORD로
   로그인하고, 그 자격 증명에서 매장과 장비 종류를 서버가 결정하는 방식 —
   인증과 신원 확인을 한 단계로 합치고, 지금 운영자가 직접 맞춰 입력해야
   하는 두 개의 자유 입력 칸을 없앤다.
2. **"매장당 주문 수신 단말 하나"가 강제되지 않는다.** owner의 규칙 —
   한 매장에 단말이 여러 개 있을 수 있지만 주문을 받는 건 하나뿐 — 이
   자리가 없다. BO의 Device record에 `receivesOrders` 플래그가 있어야
   하고, `DeviceDirectory`가 형식 검사에서 실제 조회로 넓어져야 한다.
   지금은 매장당 POS 하나라는 관례로만 성립한다.
3. **레거시 order 모듈은 죽은 무게다.** `domain/order`,
   `api/CreateOrderEndpoint`, 그 MyBatis/Postgres adapter와 Flyway
   migration은 Device Server 역할 이전의 것이고, 기동 시 Batch 데모 외에
   아무것도 뒷받침하지 않는다. 실제 주문 흐름은 이들을 전혀 건드리지
   않는다. 제거 후보이지만, Device Server 전환과 정리를 한 번에 묶지
   않으려고 이번 pass에서는 하지 않았다.
4. **취소 상태가 없다.** Order의 state machine에 `CANCELLED`가 없다.
   수락 전에는 거절·만료가 되지만, 수락 후에는 무를 수 없다.
5. **KDS, DID, 그리고 채널 앱 둘(주문, 배달)이 아직 없다.** 만들어진
   건 POS 단말(`sun-moon-terminal-pos`)뿐이다. 이 서버가 KDS/DID
   대상으로 하는 라우팅은 구현되고 빈 registry 대상으로 테스트도 됐지만,
   실제 KDS·DID 화면에 push해본 적은 없다.
6. **HTTP pipelining 시 응답 순서가 뒤바뀔 수 있다.** endpoint를
   worker로 넘기면서(ADR-0010) 한 연결에서 응답을 기다리지 않고 보낸 두
   요청이 순서가 어긋난 채 끝날 수 있다. 일반적인 keep-alive client는
   응답을 기다리므로 실제로는 발생하지 않아, 고치지 않고 기록만 해둔다.
7. **raw Socket transport는 여전히 echo handler다.** transport가
   동작한다는 것만 증명하며, 지금 있는 단말은 전부 WebSocket을 쓰기
   때문에 아무도 안 쓴다.

## 13. 결정 색인

| ADR | 결정 |
|---|---|
| [0001](adr/0001-use-adrs-for-decisions.md) | 결정을 ADR로 기록한다 |
| [0002](adr/0002-ddd-enterprise-runtime-platform-on-netty-no-spring.md) | Spring 없는 Netty 기반 DDD runtime |
| [0003](adr/0003-fakes-by-default-for-unavailable-infra.md) | Port + in-memory fake를 기본값으로 |
| [0004](adr/0004-bo-endpoints-session-auth.md) | BO 범위, session 인증, 3단계 권한 |
| [0005](adr/0005-switch-oracle-to-postgres.md) | Oracle → PostgreSQL, 로컬 DB 미설치 |
| [0006](adr/0006-bo-auth-built-and-verified.md) | BO 인증 구현 및 검증 |
| [0007](adr/0007-deployment-target-render-neon-free-forever.md) | Render + Neon 배포 (0012으로 대체됨) |
| [0008](adr/0008-common-code-crud-and-method-aware-routing.md) | 공통코드 CRUD, 메서드 인식 라우팅 |
| [0009](adr/0009-device-crud-and-port-separation.md) | 장비 CRUD, BO/API port 분리 |
| [0010](adr/0010-run-endpoints-off-the-event-loop.md) | Endpoint를 worker pool에서 실행 |
| [0011](adr/0011-deployment-mechanics.md) | Dockerfile, PORT, adapter 선택, Secure cookie |
| [0012](adr/0012-deploy-to-own-debian-server.md) | Render 대신 본인 Debian 서버에 배포 |
| [0013](adr/0013-withdraw-port-8084.md) | Port 8084 철회, 계열 전체 port 체계 |
| [0014](adr/0014-split-the-kernel-from-the-domains-built-on-it.md) | Kernel 분리, BO는 자기 저장소로 |
| [0015](adr/0015-bo-returns-to-spring-and-leaves-this-lineage.md) | BO는 Spring으로, Netty 구현은 archive |
| [0016](adr/0016-the-runtime-becomes-the-device-server.md) | 이 runtime이 Device Server가 됨: 단말 WebSocket, 매장 단위 라우팅, Order 중계 |

배포 절차는 [`DEPLOYMENT.md`](DEPLOYMENT.md)에 있다.

이 저장소가 왜 존재하는지에 대한 커리어 전략 맥락은 별도의 `Alignment`
저장소에 있다.
