package com.sunmoon.platform;

import com.sunmoon.platform.api.auth.ApiGuard;
import com.sunmoon.platform.api.PairingEndpoints;
import com.sunmoon.platform.domain.pairing.PairingService;
import com.sunmoon.platform.api.CreateOrderEndpoint;
import com.sunmoon.platform.api.MenuProxyEndpoints;
import com.sunmoon.platform.api.MasterEndpoints;
import com.sunmoon.platform.api.PosOperationEndpoints;
import com.sunmoon.platform.api.PosSystemEndpoints;
import com.sunmoon.platform.api.ReprocessEndpoints;
import com.sunmoon.platform.api.SaleEndpoints;
import com.sunmoon.platform.api.KdsAuthority;
import com.sunmoon.platform.api.OrderProxyEndpoints;
import com.sunmoon.platform.api.TerminalEndpoints;
import com.sunmoon.platform.domain.terminal.AcceptKnownFormatDirectory;
import com.sunmoon.platform.domain.terminal.DeviceDirectory;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalType;
import com.sunmoon.platform.observability.MetricsRegistry;
import io.micrometer.core.instrument.Gauge;
import com.sunmoon.platform.batch.BatchScheduler;
import com.sunmoon.platform.batch.OrderSummaryBatchJob;
import com.sunmoon.platform.batch.OrderSummaryReport;
import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.ListenerSpec;
import com.sunmoon.platform.domain.order.OrderRepository;
import com.sunmoon.platform.domain.sale.JsonSaleDecomposer;
import com.sunmoon.platform.domain.sale.DecomposedSale;
import com.sunmoon.platform.domain.sale.SaleDecomposeException;
import com.sunmoon.platform.domain.reprocess.MasterReprocessor;
import com.sunmoon.platform.domain.reprocess.ProgramReprocessor;
import com.sunmoon.platform.domain.reprocess.ReprocessService;
import com.sunmoon.platform.domain.sale.SaleRepository;
import com.sunmoon.platform.domain.pos.MasterDispatchService;
import com.sunmoon.platform.domain.pos.NoticeService;
import com.sunmoon.platform.domain.pos.ProgramDeployService;
import com.sunmoon.platform.domain.pos.PosBusinessService;
import com.sunmoon.platform.domain.pos.PosLiveService;
import com.sunmoon.platform.infrastructure.pos.JdbcMasterRepository;
import com.sunmoon.platform.infrastructure.pos.JdbcPosBusinessRepository;
import com.sunmoon.platform.infrastructure.pos.JdbcNoticeRepository;
import com.sunmoon.platform.infrastructure.pos.JdbcPosLiveRepository;
import com.sunmoon.platform.infrastructure.pos.JdbcProgramRepository;
import com.sunmoon.platform.infrastructure.pos.TerminalNoticeBroadcaster;
import com.sunmoon.platform.infrastructure.sale.JdbcSaleRepository;
import com.sunmoon.platform.infrastructure.messaging.EventPublisher;
import com.sunmoon.platform.infrastructure.messaging.InMemoryEventPublisher;
import com.sunmoon.platform.infrastructure.messaging.MenuEventSubscriber;
import com.sunmoon.platform.infrastructure.messaging.OrderEventSubscriber;
import com.sunmoon.platform.infrastructure.appserver.AppServerClient;
import com.sunmoon.platform.infrastructure.order.OrderClient;
import com.sunmoon.platform.infrastructure.cache.RedissonClientFactory;
import com.sunmoon.platform.infrastructure.persistence.InMemoryOrderRepository;
import com.sunmoon.platform.infrastructure.persistence.MyBatisOrderRepository;
import com.sunmoon.platform.infrastructure.persistence.OrderMapper;
import com.sunmoon.platform.infrastructure.persistence.FlywayMigrator;
import com.sunmoon.platform.infrastructure.persistence.MyBatisConfig;
import com.sunmoon.platform.infrastructure.persistence.PostgresConnectionSettings;
import com.sunmoon.platform.transport.http.HealthCheckEndpoint;
import com.sunmoon.platform.transport.http.MetricsEndpoint;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.http.RouteKey;
import com.sunmoon.platform.transport.socket.SocketServerInitializer;
import com.sunmoon.platform.transport.ws.DeviceServerInitializer;
import io.netty.handler.codec.http.HttpMethod;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Composition root. No DI container (Spring dropped — see docs/adr/0002):
 * every wire-up happens here, by hand. Persistence/Cache/Messaging default
 * to their in-memory fake adapters — there's no live Postgres/Redis/Kafka
 * in this dev environment, by choice (docs/adr/0003, docs/adr/0005).
 *
 * <p>This runtime is the device-facing half of what used to be one
 * process: REST, WebSocket, raw Socket and Batch. Back Office moved to
 * {@code sun-moon-platform-bo} and is a sibling, not a dependency — both
 * are built on {@code sun-moon-platform-core}, which arrives here as the
 * {@code core/} submodule (docs/adr/0014).
 */
public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    public static void main(String[] args) throws Exception {
        PlatformConfig config = PlatformConfig.fromEnv();

        // 매출 원본을 담을 곳. POSTGRES_JDBC_URL 이 없으면 null 이고,
        // /sales 는 503으로 그 사실을 말합니다 — 조용히 404를 내면
        // POS가 주소를 잘못 쓴 줄 알고 영원히 재시도합니다.
        DataSource salesDataSource = buildDataSource();
        JdbcSaleRepository salesJdbc = salesDataSource == null ? null : new JdbcSaleRepository(salesDataSource);
        SaleRepository sales = salesJdbc;
        // 원본 수명주기 — TR 과 ETC 가 같은 구현을 표 이름만 바꿔 씁니다
        // (§3.2, §3.3). TR 의 것은 매출 저장소가 이미 들고 있어서 그걸
        // 그대로 씁니다 — 두 벌을 만들면 배치와 재처리가 서로 다른
        // 객체를 보게 됩니다(상태는 없어서 틀리지는 않지만, 읽는 사람이
        // "왜 둘이지" 를 넘겨야 합니다).
        com.sunmoon.platform.infrastructure.intake.JdbcRawLogStore trLogs =
                salesJdbc == null ? null : salesJdbc.rawLogs();
        com.sunmoon.platform.infrastructure.intake.JdbcRawLogStore etcLogs = salesDataSource == null ? null
                : new com.sunmoon.platform.infrastructure.intake.JdbcRawLogStore(
                        salesDataSource, "etc_sale_log", "etc_no", "ETC 매출");

        OrderRepository orders = buildOrderRepository(salesDataSource);
        EventPublisher eventPublisher = new InMemoryEventPublisher();

        // The composition root owns the worker pool endpoints run on (docs/adr/0010),
        // so the kernel doesn't have to know it exists.
        ExecutorService blockingWorkExecutor = Executors.newFixedThreadPool(
                config.workerThreads(), Thread.ofPlatform().name("platform-worker-", 0).daemon(true).factory());
        log.info("{} worker threads for blocking endpoint work", config.workerThreads());

        // The Device Server's two halves: terminals hold WebSockets here,
        // and everything about them is answered over REST on the same port.
        TerminalRegistry terminals = new TerminalRegistry();
        /*
         * 단말이 BO에 등록된 장비인지 확인할 것인가 (2026-09-24).
         *
         * **기본값은 확인하지 않는 것입니다.** 확인하려면 이 데이터베이스에
         * v_bo_devices 뷰가 걸려 있어야 하는데, 그건 superuser 가
         * postgres_fdw 를 잡아야 만들어집니다(docs/adr/0008). 켜는 것을
         * 기본으로 두면, 뷰가 아직 없는 서버에 이 버전을 올리는 순간
         * 전 매장 단말이 조용히 못 붙습니다.
         *
         * 그래서 순서가 이렇습니다: 배포 → SQL 실행 → DEVICE_DIRECTORY=bo
         * 로 다시 띄우기. 배포와 권한 작업이 갈라져 있는 것은 이 서버에서
         * sudo 가 사람 손을 타기 때문이기도 합니다.
         */
        DeviceDirectory formatOnly = new AcceptKnownFormatDirectory();
        DeviceDirectory directory = "bo".equalsIgnoreCase(System.getenv("DEVICE_DIRECTORY"))
                && salesDataSource != null
                ? new com.sunmoon.platform.infrastructure.terminal.BoDeviceDirectory(
                        salesDataSource, formatOnly)
                : formatOnly;
        log.info("단말 확인 방식: {}", directory.getClass().getSimpleName());
        registerTerminalGauges(terminals);

        // POS 운영 (§3.4~3.6). 매출과 같은 데이터베이스, 같은 조건 —
        // POSTGRES_JDBC_URL 이 없으면 셋 다 없고, 해당 주소는 503 입니다.
        PosBusinessService posBusiness = salesDataSource == null ? null
                : new PosBusinessService(new JdbcPosBusinessRepository(salesDataSource));
        PosLiveService posLive = salesDataSource == null ? null : new PosLiveService(
                terminals,
                new JdbcPosLiveRepository(salesDataSource),
                new JdbcPosBusinessRepository(salesDataSource),
                // 서버가 본 것: 그 매장 그 날 분해에 실패한 매출 건수.
                // POS는 자기가 보낸 매출이 서버에서 깨졌는지 모릅니다.
                (storeId, businessDate) -> sales.totals(storeId, businessDate).failed(),
                config.heartbeatGrace(), config.clockTolerance(), java.time.Clock.systemUTC());
        MasterDispatchService posMasters = salesDataSource == null ? null
                : new MasterDispatchService(new JdbcMasterRepository(salesDataSource));
        ProgramDeployService posPrograms = salesDataSource == null ? null
                : new ProgramDeployService(new JdbcProgramRepository(salesDataSource));
        // 긴급 메시지는 이 서버가 직접 받은 것이라 Redis 를 거치지
        // 않습니다 — 자기 안에서 일어난 일을 한 바퀴 돌리면 Redis 가
        // 죽은 날 이 서버만으로 할 수 있는 일까지 못 하게 됩니다.
        NoticeService posNotices = salesDataSource == null ? null : new NoticeService(
                new JdbcNoticeRepository(salesDataSource),
                new TerminalNoticeBroadcaster(terminals), java.time.Clock.systemUTC());

        // 실패를 되돌리는 자리 (§5.8). 네 종류가 저장소는 다르지만
        // 운영자가 묻는 것은 하나라, 한 곳을 지나갑니다.
        //
        // TR 과 ETC 는 같은 클래스입니다(2026-09-27) — 둘 다 "원본이 있으니
        // 상태만 되돌리면 배치가 다시 푼다" 이고, 글자 하나 빼고 같았습니다.
        // 마스터·프로그램은 합치지 않았습니다: 저 둘은 장비가 가져가는
        // 것이라 "다시 한다" 의 뜻이 다릅니다.
        // 라벨 (store-app.md §7). 저장소가 있어야 합니다 — 없으면 프린터 주소는 503.
        com.sunmoon.platform.infrastructure.print.JdbcPrintJobStore printJobs = salesDataSource == null ? null
                : new com.sunmoon.platform.infrastructure.print.JdbcPrintJobStore(salesDataSource);
        com.sunmoon.platform.infrastructure.print.BoPrinters boPrinters = salesDataSource == null ? null
                : new com.sunmoon.platform.infrastructure.print.BoPrinters(salesDataSource);
        com.sunmoon.platform.infrastructure.print.LabelPush labelPush =
                new com.sunmoon.platform.infrastructure.print.LabelPush(terminals);

        ReprocessService reprocess = salesDataSource == null ? null : new ReprocessService(java.util.List.of(
                new com.sunmoon.platform.domain.reprocess.RawLogReprocessor("SALE", "매출 분해", trLogs),
                new com.sunmoon.platform.domain.reprocess.RawLogReprocessor("ETC", "ETC 매출 분해", etcLogs),
                new MasterReprocessor(new JdbcMasterRepository(salesDataSource)),
                new ProgramReprocessor(new JdbcProgramRepository(salesDataSource)),
                new com.sunmoon.platform.domain.reprocess.PrintJobReprocessor(printJobs, printJobs::failed,
                        (store, jobId) -> labelPush.push(store, java.util.List.of(jobId)))));

        OrderClient orderClient = new OrderClient(config.orderServiceUrl());
        AppServerClient appServerClient = new AppServerClient(config.appServerUrl());
        subscribeToEvents(config, terminals, orderClient);
        if (config.redisUrl() != null && printJobs != null) {
            try {
                new com.sunmoon.platform.infrastructure.print.LabelJobCreator(
                        com.sunmoon.platform.infrastructure.cache.RedissonClientFactory.create(config.redisUrl()),
                        orderClient, printJobs, boPrinters, labelPush).start();
            } catch (RuntimeException e) {
                log.error("라벨 작업을 만들 이벤트를 듣지 못합니다 — 라벨이 자동으로 안 나옵니다", e);
            }
        }

        // 주방의 명령은 KDS-MAIN 이 (§4.2). MAIN 이 언제까지 붙어 있었는지를 5초마다 적습니다.
        KdsAuthority kdsAuthority = new KdsAuthority(terminals, config.terminalGrace(), java.time.Clock.systemUTC());
        Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("kds-main-watch").daemon(true).factory())
                .scheduleAtFixedRate(() -> {
                    try {
                        kdsAuthority.observe();
                    } catch (RuntimeException e) {
                        log.warn("KDS-MAIN 연결 확인 실패", e);
                    }
                }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);

        // HTTP 주소마다 누가 불러도 되는지 (store-app.md §2.2). 기본 warn — 막지 않고 적습니다.
        // 기기 토큰은 페어링이 발급합니다 (§3). 데이터베이스가 없으면 기억만 — 재기동하면 다시 페어링.
        PairingService pairing = new PairingService(salesDataSource == null
                ? new com.sunmoon.platform.infrastructure.pairing.InMemoryPairingStore()
                : new com.sunmoon.platform.infrastructure.pairing.JdbcPairingStore(salesDataSource),
                java.time.Clock.systemUTC());
        ApiGuard apiGuard = new ApiGuard(ApiGuard.modeFrom(System.getenv("API_AUTH")),
                System.getenv("ADMIN_TOKEN"), pairing);

        List<ListenerSpec> listeners = List.of(
                new ListenerSpec("Device Server", config.apiPort(), new DeviceServerInitializer(
                        apiGuard.protect(apiRoutes(eventPublisher, terminals, orderClient,
                                appServerClient, sales, etcLogs, posBusiness, posLive, posMasters,
                                posPrograms, posNotices, reprocess, new PairingEndpoints(pairing),
                                kdsAuthority, printJobs == null ? null
                                        : new com.sunmoon.platform.api.PrintEndpoints(
                                                printJobs, boPrinters, labelPush, kdsAuthority))),
                        blockingWorkExecutor, directory, terminals, apiGuard)),
                new ListenerSpec("Socket", config.socketPort(), new SocketServerInitializer()));

        runStartupBatchJob(orders);
        scheduleIntake(salesDataSource, sales, trLogs, etcLogs);

        new CoreRuntime(listeners).start();
    }

    /**
     * How many terminals are connected, as a metric.
     *
     * <p>The one number on this server that is about the shop rather than
     * about the machine, and the one most worth graphing next to CPU: a
     * counter that goes dark has a cause, and "the CPU was pinned by two
     * concurrent docker builds" has already been that cause once.
     *
     * <p>Gauges read the registry on each scrape rather than being
     * incremented on connect and decremented on close. A counter kept by
     * hand drifts the first time a disconnect path is missed — and there
     * are several, since a channel can close for several reasons.
     */
    private static void registerTerminalGauges(TerminalRegistry terminals) {
        Gauge.builder("terminals_connected", terminals, TerminalRegistry::size)
                .description("Terminals currently holding a WebSocket to this server")
                .register(MetricsRegistry.get());

        for (TerminalType type : TerminalType.values()) {
            Gauge.builder("terminals_connected_by_type", terminals, r -> countOf(r, type))
                    .tag("type", type.name())
                    .description("Connected terminals of one kind, across every store")
                    .register(MetricsRegistry.get());
        }
    }

    private static double countOf(TerminalRegistry terminals, TerminalType type) {
        return terminals.connected().stream().filter(session -> session.type() == type).count();
    }

    /**
     * The one place fakes and the real adapter are chosen between. Presence
     * of {@code POSTGRES_JDBC_URL} is the switch — no separate mode flag to
     * keep in sync, and a machine with only a JDK still runs the whole
     * platform on in-memory fakes (docs/adr/0003, docs/adr/0005).
     */
    private static DataSource buildDataSource() {
        if (System.getenv("POSTGRES_JDBC_URL") == null) {
            log.info("POSTGRES_JDBC_URL not set — using in-memory fake repositories");
            return null;
        }
        PostgresConnectionSettings settings = PostgresConnectionSettings.fromEnv();
        DataSource dataSource = MyBatisConfig.buildDataSource(settings);
        // 마이그레이션은 여기서 한 번만 돕니다. 주문과 매출이 같은
        // 데이터베이스를 쓰므로, 두 곳에서 부르면 Flyway 가 같은 이력을
        // 두 번 보게 됩니다.
        FlywayMigrator.migrate(dataSource);
        log.info("POSTGRES_JDBC_URL set — using PostgreSQL ({})", settings.jdbcUrl());
        return dataSource;
    }

    private static OrderRepository buildOrderRepository(DataSource dataSource) {
        if (dataSource == null) {
            return new InMemoryOrderRepository();
        }
        // This runtime's mappers, passed in — the kernel lists no domain (docs/adr/0014).
        SqlSessionFactory sqlSessionFactory = MyBatisConfig.buildSqlSessionFactory(dataSource, OrderMapper.class);
        return new MyBatisOrderRepository(sqlSessionFactory);
    }

    /**
     * The Device Server's REST surface. The routes that matter are about
     * terminals; {@code /orders} is a leftover from when this runtime was
     * going to own orders, kept only until the flow proves out against the
     * Spring Order service on 8083.
     */
    private static Map<RouteKey, RestEndpoint> apiRoutes(
            EventPublisher eventPublisher, TerminalRegistry terminals, OrderClient orderClient,
            AppServerClient appServerClient, SaleRepository sales,
            com.sunmoon.platform.domain.intake.RawLogStore etcLogs,
            PosBusinessService posBusiness, PosLiveService posLive, MasterDispatchService posMasters,
            ProgramDeployService posPrograms, NoticeService posNotices,
            ReprocessService reprocess, PairingEndpoints pairing, KdsAuthority kdsAuthority,
            com.sunmoon.platform.api.PrintEndpoints printing) {

        TerminalEndpoints terminalEndpoints = new TerminalEndpoints(terminals);
        OrderProxyEndpoints orderEndpoints = new OrderProxyEndpoints(orderClient, kdsAuthority);
        MenuProxyEndpoints menuEndpoints = new MenuProxyEndpoints(appServerClient);
        SaleEndpoints saleEndpoints = sales == null ? null : new SaleEndpoints(sales);
        com.sunmoon.platform.api.EtcSaleEndpoints etcEndpoints =
                etcLogs == null ? null : new com.sunmoon.platform.api.EtcSaleEndpoints(etcLogs);
        PosOperationEndpoints posEndpoints = posBusiness == null ? null
                : new PosOperationEndpoints(posBusiness, posLive);
        MasterEndpoints masterEndpoints = posMasters == null ? null : new MasterEndpoints(posMasters);
        PosSystemEndpoints systemEndpoints = posPrograms == null ? null
                : new PosSystemEndpoints(posPrograms, posNotices);
        ReprocessEndpoints reprocessEndpoints = reprocess == null ? null
                : new ReprocessEndpoints(reprocess);

        return Map.ofEntries(
                Map.entry(new RouteKey(HttpMethod.GET, "/health"), new HealthCheckEndpoint()),
                Map.entry(new RouteKey(HttpMethod.GET, "/metrics"), new MetricsEndpoint()),

                Map.entry(new RouteKey(HttpMethod.GET, "/terminals"), terminalEndpoints.list()),
                Map.entry(new RouteKey(HttpMethod.POST, "/terminals/push"), terminalEndpoints.pushToDevice()),
                Map.entry(new RouteKey(HttpMethod.POST, "/terminals/broadcast"), terminalEndpoints.broadcastToType()),

                // The terminals' view of Order, on this server's own origin.
                Map.entry(new RouteKey(HttpMethod.GET, "/orders"), orderEndpoints.list()),
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/status"), orderEndpoints.changeStatus()),

                // 수령 확인 — 손님이 번호를 내밀고 가져가는 순간. 상태
                // 전이와 따로인 이유는 번호 검사가 붙어 있기 때문입니다.
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/pickup"), orderEndpoints.handOver()),

                // 재호출 — DID에 번호를 다시 띄웁니다. 상태 전이가
                // 아닙니다: 한 번 부르든 다섯 번 부르든 PRODUCED 입니다.
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/recall"), orderEndpoints.recall()),
                // KDS-MAIN 의 권한 (store-app.md §4). 역할 확인은 KdsAuthority.
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/start"), orderEndpoints.start()),
                // KIOSK — 캐셔 없는 POS (store-app.md §9).
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/kiosk"), orderEndpoints.kiosk()),
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/force-produced"), orderEndpoints.forceProduced()),
                Map.entry(new RouteKey(HttpMethod.GET, "/kds/mode"), orderEndpoints.kdsMode()),

                // POS 수신 완료 — 상태 전이가 아니라 전달 사실입니다.
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/received"), orderEndpoints.markReceived()),

                // POS 매출 — 이 서버가 처음으로 갖는 자기 업무입니다
                // (업무 정의서 §3.1~3.2). 받기만 하고 풀지 않습니다:
                // POS는 손님을 세워 두고 기다립니다.
                Map.entry(new RouteKey(HttpMethod.POST, "/sales"),
                        saleEndpoints == null ? noSaleStore() : saleEndpoints.receive()),
                Map.entry(new RouteKey(HttpMethod.GET, "/sales/totals"),
                        saleEndpoints == null ? noSaleStore() : saleEndpoints.totals()),
                // ETC 매출 (§3.3) — TR 과 같은 모양, 같은 조건(데이터베이스가 없으면 503).
                Map.entry(new RouteKey(HttpMethod.POST, "/etc-sales"),
                        etcEndpoints == null ? noSaleStore() : etcEndpoints.receive()),
                Map.entry(new RouteKey(HttpMethod.GET, "/etc-sales/totals"),
                        etcEndpoints == null ? noSaleStore() : etcEndpoints.totals()),

                // POS 개점·마감 (§3.4). Order 쪽 /business-days 와 다릅니다 —
                // 저쪽은 매장이 주문을 받는가이고, 이쪽은 그 단말이 매출을
                // 올릴 수 있는가입니다. POS 셋 중 하나만 마감한 매장은
                // 여전히 영업 중입니다.
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/business"),
                        posEndpoints == null ? noPosStore() : posEndpoints.receiveBusinessIrt()),
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/business"),
                        posEndpoints == null ? noPosStore() : posEndpoints.businessStates()),
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/business/history"),
                        posEndpoints == null ? noPosStore() : posEndpoints.businessHistory()),

                // Live Check 5종 (§3.5). 연결 하나로 정상 여부를 판단하지
                // 않기 위해 축을 갈라서 답합니다.
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/heartbeat"),
                        posEndpoints == null ? noPosStore() : posEndpoints.heartbeat()),
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/live"),
                        posEndpoints == null ? noPosStore() : posEndpoints.liveCheck()),

                // 마스터 IRT 배포 (§3.6). 내용이 아니라 버전과 적용 결과.
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/masters"),
                        masterEndpoints == null ? noPosStore() : masterEndpoints.plan()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/masters/ack"),
                        masterEndpoints == null ? noPosStore() : masterEndpoints.ack()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/masters/version"),
                        masterEndpoints == null ? noPosStore() : masterEndpoints.bumpVersion()),
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/masters/pending"),
                        masterEndpoints == null ? noPosStore() : masterEndpoints.pending()),

                // 재처리 (§5.8). 매출 분해·마스터 적용·프로그램 설치 —
                // 세 종류가 저장소는 달라도 사람이 묻는 것은 하나입니다:
                // 지금 손봐야 할 것이 있나.
                Map.entry(new RouteKey(HttpMethod.GET, "/reprocess"),
                        reprocessEndpoints == null ? noPosStore() : reprocessEndpoints.list()),
                Map.entry(new RouteKey(HttpMethod.POST, "/reprocess/one"),
                        reprocessEndpoints == null ? noPosStore() : reprocessEndpoints.retryOne()),
                Map.entry(new RouteKey(HttpMethod.POST, "/reprocess/all"),
                        reprocessEndpoints == null ? noPosStore() : reprocessEndpoints.retryAll()),

                // System Date 동기화 (§3.5). 갓 켜진 POS는 보고할 상태가
                // 없는데 시각은 그때 제일 필요합니다.
                // 프린터와 라벨 (store-app.md §6.3, §7) — 페어링된 기기만.
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/printers"),
                        printing == null ? noPosStore() : printing.printers()),
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/print-jobs"),
                        printing == null ? noPosStore() : printing.list()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/print-jobs/ack"),
                        printing == null ? noPosStore() : printing.ack()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/print-jobs/reprint"),
                        printing == null ? noPosStore() : printing.reprint()),

                // 기기 페어링 (store-app.md §3.4). 청중은 ApiGuard 표에.
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/pairing-codes"), pairing.issueCode()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/pair"), pairing.pair()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/devices/revoke"), pairing.revoke()),
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/devices"), pairing.list()),

                Map.entry(new RouteKey(HttpMethod.GET, "/pos/time"),
                        posEndpoints == null ? noPosStore() : posEndpoints.serverTime()),

                // 프로그램 배포 (§3.5). 마스터와 같은 규칙 — 깔린 버전은
                // 장비가 말해야 올라갑니다.
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/programs"),
                        systemEndpoints == null ? noPosStore() : systemEndpoints.programPlan()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/programs/release"),
                        systemEndpoints == null ? noPosStore() : systemEndpoints.publishRelease()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/programs/ack"),
                        systemEndpoints == null ? noPosStore() : systemEndpoints.programAck()),
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/programs/status"),
                        systemEndpoints == null ? noPosStore() : systemEndpoints.programStatus()),

                // 공지·긴급 메시지 (§3.5). 먼저 적고 그다음에 밉니다 —
                // 밀기만 하면 그때 꺼져 있던 장비는 영영 모릅니다.
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/notices"),
                        systemEndpoints == null ? noPosStore() : systemEndpoints.activeNotices()),
                Map.entry(new RouteKey(HttpMethod.GET, "/pos/notices/all"),
                        systemEndpoints == null ? noPosStore() : systemEndpoints.allNotices()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/notices"),
                        systemEndpoints == null ? noPosStore() : systemEndpoints.publishNotice()),
                Map.entry(new RouteKey(HttpMethod.POST, "/pos/notices/ack"),
                        systemEndpoints == null ? noPosStore() : systemEndpoints.acknowledgeNotice()),

                // The terminal's local master: what this store sells today
                // and what it has run out of. Read from APP-SERVER, which
                // reads BO — the terminal knows neither.
                Map.entry(new RouteKey(HttpMethod.GET, "/menus"), menuEndpoints.menus()),

                // 개점/마감 — POS presses these, BO only reads the result.
                Map.entry(new RouteKey(HttpMethod.GET, "/business-days/status"), orderEndpoints.businessDayStatus()),
                Map.entry(new RouteKey(HttpMethod.POST, "/business-days/open"), orderEndpoints.openBusinessDay()),
                Map.entry(new RouteKey(HttpMethod.POST, "/business-days/close"), orderEndpoints.closeBusinessDay()),

                // Left from when this runtime was going to own orders; it
                // writes to nothing now and goes when the flow is proven.
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/legacy"), new CreateOrderEndpoint(eventPublisher)));
    }

    /**
     * Subscribes to the two channels terminals care about, when there is a
     * Redis to subscribe to: Order's {@code order-events} and BO's
     * {@code menu-events} (결품).
     *
     * <p>Absent {@code REDIS_URL} the server still runs: terminals connect
     * and can still fetch orders and menus, they simply are not nudged.
     * That keeps a machine with only a JDK runnable, the same bargain as
     * the fake repositories (docs/adr/0003).
     *
     * <p>One Redisson client for both. Two would be two connection pools
     * to the same server for no gain — and the subscriptions are
     * independent of each other, not of the connection.
     */
    private static void subscribeToEvents(
            PlatformConfig config, TerminalRegistry terminals, OrderClient orderClient) {

        if (config.redisUrl() == null) {
            log.info("REDIS_URL not set — terminals will not be pushed order or menu events");
            return;
        }
        try {
            var redisson = RedissonClientFactory.create(config.redisUrl());
            new OrderEventSubscriber(redisson, terminals, orderClient, config.terminalGrace()).start();
            new MenuEventSubscriber(redisson, terminals).start();
        } catch (RuntimeException e) {
            // Loud, but not fatal: the terminals are still usable by polling,
            // and a Device Server that refuses to start because Redis is
            // down would take the screens with it.
            log.error("could not subscribe to events — terminals will not be nudged", e);
        }
    }

    /**
     * 매출 저장소가 없을 때의 답.
     *
     * <p>404가 아니라 503인 이유: 주소는 맞고 <b>서버가 아직 그 일을
     * 할 수 없는</b> 상태입니다. 404를 내면 POS가 주소를 잘못 쓴 줄
     * 알고, 조용히 영원히 재시도하거나 사람이 엉뚱한 곳을 고칩니다.
     */
    /** 같은 이유로 503 입니다 — 주소는 맞고 저장소가 없습니다. */
    private static RestEndpoint noPosStore() {
        return request -> com.sunmoon.platform.transport.http.JsonResponses.of(
                io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE,
                Map.of("error", "POS 운영 저장소가 설정되지 않았습니다 (POSTGRES_JDBC_URL)"));
    }

    private static RestEndpoint noSaleStore() {
        return request -> com.sunmoon.platform.transport.http.JsonResponses.of(
                io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE,
                Map.of("error", "매출 저장소가 설정되지 않았습니다 (POSTGRES_JDBC_URL)"));
    }

    /**
     * 쌓인 매출 원본을 주기적으로 풉니다 (업무 정의서 §3.2).
     *
     * <p>받는 것과 따로 도는 이유: POS는 매장에서 손님을 세워 두고
     * 기다립니다. 분해가 느리다고 수신이 느려지면 안 됩니다.
     */
    private static void scheduleIntake(
            javax.sql.DataSource salesDataSource, SaleRepository sales,
            com.sunmoon.platform.infrastructure.intake.JdbcRawLogStore trLogs,
            com.sunmoon.platform.infrastructure.intake.JdbcRawLogStore etcLogs) throws Exception {
        if (sales == null) {
            log.info("매출 저장소가 없어 분해 배치를 걸지 않습니다");
            return;
        }

        // TR — 분해하고, 금액이 맞는지 보고, 표 넷에 넣습니다.
        //
        // 금액이 안 맞는 것을 SaleDecomposeException 으로 던지는 이유:
        // 공통 배치가 그 예외만 보고 FAILED 로 낙인찍습니다. 다른 예외로
        // 던지면 "저장 사고" 로 읽혀 30초마다 다시 풀립니다 — 2026-09-18의
        // 388번 사고로 돌아갑니다.
        JsonSaleDecomposer trDecomposer = new JsonSaleDecomposer();
        com.sunmoon.platform.domain.intake.IntakeJob tr = new com.sunmoon.platform.domain.intake.IntakeJob(
                "TR 매출", trLogs, raw -> {
                    DecomposedSale sale = trDecomposer.decompose(raw.payload());
                    if (!sale.balanced()) {
                        throw new SaleDecomposeException("금액이 맞지 않습니다: " + sale.imbalance());
                    }
                    sales.store(raw.id(), sale);
                }, 200);

        // ETC — 이름·값으로 풀어 한 표에 넣습니다 (§3.3 은 구조를 미뤘습니다).
        com.sunmoon.platform.domain.intake.IntakeJob etc = new com.sunmoon.platform.domain.intake.IntakeJob(
                "ETC 매출", etcLogs,
                new com.sunmoon.platform.infrastructure.etc.JdbcEtcSaleHandler(
                        salesDataSource, etcLogs, new com.sunmoon.platform.domain.etc.JsonEtcDecomposer()),
                200);

        // 같은 Quartz 스케줄러에 이름만 다르게 겁니다. 30초는 TR 이 원래
        // 쓰던 주기이고, POS 가 거래를 올리는 주기(30초)와 같아서 올린
        // 것이 다음 분해에 곧바로 실립니다.
        BatchScheduler scheduler = new BatchScheduler();
        scheduler.scheduleEvery("tr-sale-decompose", 30, tr::run);
        scheduler.scheduleEvery("etc-sale-decompose", 30, etc::run);
    }

    private static void runStartupBatchJob(OrderRepository orderRepository) throws Exception {
        BatchScheduler scheduler = new BatchScheduler();
        scheduler.scheduleOnce("order-summary-on-startup", () -> {
            OrderSummaryReport report = OrderSummaryBatchJob.create(orderRepository, 2).run();
            log.info("order-summary-job finished: {} orders, revenue={}, successful={}",
                    report.orderCount(), report.totalRevenue(), report.jobExecution().successful());
        });
    }

    private Bootstrap() {
    }
}
