package com.sunmoon.platform;

import com.sunmoon.platform.api.CreateOrderEndpoint;
import com.sunmoon.platform.api.MenuProxyEndpoints;
import com.sunmoon.platform.api.MasterEndpoints;
import com.sunmoon.platform.api.PosOperationEndpoints;
import com.sunmoon.platform.api.SaleEndpoints;
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
import com.sunmoon.platform.domain.sale.SaleDecomposeJob;
import com.sunmoon.platform.domain.sale.SaleRepository;
import com.sunmoon.platform.domain.pos.MasterDispatchService;
import com.sunmoon.platform.domain.pos.PosBusinessService;
import com.sunmoon.platform.domain.pos.PosLiveService;
import com.sunmoon.platform.infrastructure.pos.JdbcMasterRepository;
import com.sunmoon.platform.infrastructure.pos.JdbcPosBusinessRepository;
import com.sunmoon.platform.infrastructure.pos.JdbcPosLiveRepository;
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
        SaleRepository sales = salesDataSource == null ? null : new JdbcSaleRepository(salesDataSource);

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
        DeviceDirectory directory = new AcceptKnownFormatDirectory();
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
                config.heartbeatGrace(), java.time.Clock.systemUTC());
        MasterDispatchService posMasters = salesDataSource == null ? null
                : new MasterDispatchService(new JdbcMasterRepository(salesDataSource));

        OrderClient orderClient = new OrderClient(config.orderServiceUrl());
        AppServerClient appServerClient = new AppServerClient(config.appServerUrl());
        subscribeToEvents(config, terminals, orderClient);

        List<ListenerSpec> listeners = List.of(
                new ListenerSpec("Device Server", config.apiPort(), new DeviceServerInitializer(
                        apiRoutes(eventPublisher, terminals, orderClient, appServerClient, sales,
                                posBusiness, posLive, posMasters),
                        blockingWorkExecutor, directory, terminals)),
                new ListenerSpec("Socket", config.socketPort(), new SocketServerInitializer()));

        runStartupBatchJob(orders);
        scheduleSaleDecomposition(sales);

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
            PosBusinessService posBusiness, PosLiveService posLive, MasterDispatchService posMasters) {

        TerminalEndpoints terminalEndpoints = new TerminalEndpoints(terminals);
        OrderProxyEndpoints orderEndpoints = new OrderProxyEndpoints(orderClient);
        MenuProxyEndpoints menuEndpoints = new MenuProxyEndpoints(appServerClient);
        SaleEndpoints saleEndpoints = sales == null ? null : new SaleEndpoints(sales);
        PosOperationEndpoints posEndpoints = posBusiness == null ? null
                : new PosOperationEndpoints(posBusiness, posLive);
        MasterEndpoints masterEndpoints = posMasters == null ? null : new MasterEndpoints(posMasters);

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

                // POS 수신 완료 — 상태 전이가 아니라 전달 사실입니다.
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/received"), orderEndpoints.markReceived()),

                // POS 매출 — 이 서버가 처음으로 갖는 자기 업무입니다
                // (업무 정의서 §3.1~3.2). 받기만 하고 풀지 않습니다:
                // POS는 손님을 세워 두고 기다립니다.
                Map.entry(new RouteKey(HttpMethod.POST, "/sales"),
                        saleEndpoints == null ? noSaleStore() : saleEndpoints.receive()),
                Map.entry(new RouteKey(HttpMethod.GET, "/sales/totals"),
                        saleEndpoints == null ? noSaleStore() : saleEndpoints.totals()),

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
    private static void scheduleSaleDecomposition(SaleRepository sales) throws Exception {
        if (sales == null) {
            log.info("매출 저장소가 없어 분해 배치를 걸지 않습니다");
            return;
        }
        SaleDecomposeJob job = new SaleDecomposeJob(sales, new JsonSaleDecomposer(), 200);
        new BatchScheduler().scheduleEvery("tr-sale-decompose", 30, job::run);
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
