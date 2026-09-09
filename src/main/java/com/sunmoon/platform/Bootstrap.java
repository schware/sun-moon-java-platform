package com.sunmoon.platform;

import com.sunmoon.platform.api.CreateOrderEndpoint;
import com.sunmoon.platform.api.OrderProxyEndpoints;
import com.sunmoon.platform.api.TerminalEndpoints;
import com.sunmoon.platform.domain.terminal.AcceptKnownFormatDirectory;
import com.sunmoon.platform.domain.terminal.DeviceDirectory;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.batch.BatchScheduler;
import com.sunmoon.platform.batch.OrderSummaryBatchJob;
import com.sunmoon.platform.batch.OrderSummaryReport;
import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.ListenerSpec;
import com.sunmoon.platform.domain.order.OrderRepository;
import com.sunmoon.platform.infrastructure.messaging.EventPublisher;
import com.sunmoon.platform.infrastructure.messaging.InMemoryEventPublisher;
import com.sunmoon.platform.infrastructure.messaging.OrderEventSubscriber;
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

        OrderRepository orders = buildOrderRepository();
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

        OrderClient orderClient = new OrderClient(config.orderServiceUrl());
        subscribeToOrderEvents(config, terminals, orderClient);

        List<ListenerSpec> listeners = List.of(
                new ListenerSpec("Device Server", config.apiPort(), new DeviceServerInitializer(
                        apiRoutes(eventPublisher, terminals, orderClient),
                        blockingWorkExecutor, directory, terminals)),
                new ListenerSpec("Socket", config.socketPort(), new SocketServerInitializer()));

        runStartupBatchJob(orders);

        new CoreRuntime(listeners).start();
    }

    /**
     * The one place fakes and the real adapter are chosen between. Presence
     * of {@code POSTGRES_JDBC_URL} is the switch — no separate mode flag to
     * keep in sync, and a machine with only a JDK still runs the whole
     * platform on in-memory fakes (docs/adr/0003, docs/adr/0005).
     */
    private static OrderRepository buildOrderRepository() {
        if (System.getenv("POSTGRES_JDBC_URL") == null) {
            log.info("POSTGRES_JDBC_URL not set — using in-memory fake repositories");
            return new InMemoryOrderRepository();
        }

        PostgresConnectionSettings settings = PostgresConnectionSettings.fromEnv();
        DataSource dataSource = MyBatisConfig.buildDataSource(settings);
        FlywayMigrator.migrate(dataSource);
        // This runtime's mappers, passed in — the kernel lists no domain (docs/adr/0014).
        SqlSessionFactory sqlSessionFactory = MyBatisConfig.buildSqlSessionFactory(dataSource, OrderMapper.class);
        log.info("POSTGRES_JDBC_URL set — using PostgreSQL repositories ({})", settings.jdbcUrl());
        return new MyBatisOrderRepository(sqlSessionFactory);
    }

    /**
     * The Device Server's REST surface. The routes that matter are about
     * terminals; {@code /orders} is a leftover from when this runtime was
     * going to own orders, kept only until the flow proves out against the
     * Spring Order service on 8083.
     */
    private static Map<RouteKey, RestEndpoint> apiRoutes(
            EventPublisher eventPublisher, TerminalRegistry terminals, OrderClient orderClient) {

        TerminalEndpoints terminalEndpoints = new TerminalEndpoints(terminals);
        OrderProxyEndpoints orderEndpoints = new OrderProxyEndpoints(orderClient);

        return Map.ofEntries(
                Map.entry(new RouteKey(HttpMethod.GET, "/health"), new HealthCheckEndpoint()),
                Map.entry(new RouteKey(HttpMethod.GET, "/metrics"), new MetricsEndpoint()),

                Map.entry(new RouteKey(HttpMethod.GET, "/terminals"), terminalEndpoints.list()),
                Map.entry(new RouteKey(HttpMethod.POST, "/terminals/push"), terminalEndpoints.pushToDevice()),
                Map.entry(new RouteKey(HttpMethod.POST, "/terminals/broadcast"), terminalEndpoints.broadcastToType()),

                // The terminals' view of Order, on this server's own origin.
                Map.entry(new RouteKey(HttpMethod.GET, "/orders"), orderEndpoints.list()),
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/status"), orderEndpoints.changeStatus()),

                // Left from when this runtime was going to own orders; it
                // writes to nothing now and goes when the flow is proven.
                Map.entry(new RouteKey(HttpMethod.POST, "/orders/legacy"), new CreateOrderEndpoint(eventPublisher)));
    }

    /**
     * Subscribes to Order's events, when there is a Redis to subscribe to.
     *
     * <p>Absent {@code REDIS_URL} the server still runs: terminals connect
     * and can still fetch orders, they simply are not nudged. That keeps a
     * machine with only a JDK runnable, the same bargain as the fake
     * repositories (docs/adr/0003).
     */
    private static void subscribeToOrderEvents(
            PlatformConfig config, TerminalRegistry terminals, OrderClient orderClient) {

        if (config.redisUrl() == null) {
            log.info("REDIS_URL not set — terminals will not be pushed order events");
            return;
        }
        try {
            new OrderEventSubscriber(
                    RedissonClientFactory.create(config.redisUrl()), terminals, orderClient,
                    config.terminalGrace()).start();
        } catch (RuntimeException e) {
            // Loud, but not fatal: the terminals are still usable by polling,
            // and a Device Server that refuses to start because Redis is
            // down would take the screens with it.
            log.error("could not subscribe to order events — terminals will not be nudged", e);
        }
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
