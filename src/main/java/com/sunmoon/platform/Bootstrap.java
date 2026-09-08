package com.sunmoon.platform;

import com.sunmoon.platform.batch.BatchScheduler;
import com.sunmoon.platform.batch.OrderSummaryBatchJob;
import com.sunmoon.platform.batch.OrderSummaryReport;
import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.RuntimeConfig;
import com.sunmoon.platform.domain.commoncode.CommonCodeRepository;
import com.sunmoon.platform.domain.device.DeviceRepository;
import com.sunmoon.platform.domain.operator.Action;
import com.sunmoon.platform.domain.operator.OperatorRepository;
import com.sunmoon.platform.domain.operator.Screen;
import com.sunmoon.platform.domain.order.OrderRepository;
import com.sunmoon.platform.infrastructure.auth.InMemorySessionStore;
import com.sunmoon.platform.infrastructure.auth.PasswordHasher;
import com.sunmoon.platform.infrastructure.auth.SessionStore;
import com.sunmoon.platform.infrastructure.messaging.EventPublisher;
import com.sunmoon.platform.infrastructure.messaging.InMemoryEventPublisher;
import com.sunmoon.platform.infrastructure.persistence.InMemoryCommonCodeRepository;
import com.sunmoon.platform.infrastructure.persistence.InMemoryDeviceRepository;
import com.sunmoon.platform.infrastructure.persistence.InMemoryOperatorRepository;
import com.sunmoon.platform.infrastructure.persistence.InMemoryOrderRepository;
import com.sunmoon.platform.transport.http.CreateOrderEndpoint;
import com.sunmoon.platform.transport.http.HealthCheckEndpoint;
import com.sunmoon.platform.transport.http.HttpListenerSpec;
import com.sunmoon.platform.transport.http.MetricsEndpoint;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.http.RouteKey;
import com.sunmoon.platform.transport.http.bo.AuthorizedEndpoint;
import com.sunmoon.platform.transport.http.bo.LoginEndpoint;
import com.sunmoon.platform.transport.http.bo.LogoutEndpoint;
import com.sunmoon.platform.transport.http.bo.MeEndpoint;
import com.sunmoon.platform.transport.http.bo.commoncode.CreateCommonCodeEndpoint;
import com.sunmoon.platform.transport.http.bo.commoncode.DeleteCommonCodeEndpoint;
import com.sunmoon.platform.transport.http.bo.commoncode.ListCommonCodeEndpoint;
import com.sunmoon.platform.transport.http.bo.commoncode.SaveCommonCodeEndpoint;
import com.sunmoon.platform.transport.http.bo.device.CreateDeviceEndpoint;
import com.sunmoon.platform.transport.http.bo.device.DeleteDeviceEndpoint;
import com.sunmoon.platform.transport.http.bo.device.ListDeviceEndpoint;
import com.sunmoon.platform.transport.http.bo.device.SaveDeviceEndpoint;
import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Composition root. No DI container (Spring dropped — see docs/adr/0002):
 * every wire-up happens here, by hand. Persistence/Cache/Messaging default
 * to their in-memory fake adapters — there's no live Postgres/Redis/Kafka in
 * this dev environment, by choice (see docs/adr/0003, docs/adr/0005); swap
 * the `new InMemoryXxx()` lines below for the real adapters once that
 * infrastructure exists (deploy time, not this machine — see docs/adr/0007).
 */
public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    public static void main(String[] args) throws Exception {
        RuntimeConfig config = RuntimeConfig.fromEnv();

        OrderRepository orderRepository = new InMemoryOrderRepository();
        EventPublisher eventPublisher = new InMemoryEventPublisher();
        OperatorRepository operatorRepository = new InMemoryOperatorRepository();
        CommonCodeRepository commonCodeRepository = new InMemoryCommonCodeRepository();
        DeviceRepository deviceRepository = new InMemoryDeviceRepository();
        SessionStore sessionStore = new InMemorySessionStore();

        seedSuperAdminIfNeeded(operatorRepository);

        List<HttpListenerSpec> listeners = List.of(
                new HttpListenerSpec("BO", config.boPort(),
                        boRoutes(operatorRepository, commonCodeRepository, deviceRepository, sessionStore), false),
                new HttpListenerSpec("Order API", config.apiPort(), apiRoutes(eventPublisher), true));

        runStartupBatchJob(orderRepository);

        new CoreRuntime(listeners, config.socketPort()).start();
    }

    /** BO port: the BO screens plus the operational endpoints, since this is the port published when deployed (docs/adr/0007). */
    private static Map<RouteKey, RestEndpoint> boRoutes(
            OperatorRepository operatorRepository,
            CommonCodeRepository commonCodeRepository,
            DeviceRepository deviceRepository,
            SessionStore sessionStore) {

        return Map.ofEntries(
                Map.entry(new RouteKey(HttpMethod.GET, "/health"), new HealthCheckEndpoint()),
                Map.entry(new RouteKey(HttpMethod.GET, "/metrics"), new MetricsEndpoint()),

                Map.entry(new RouteKey(HttpMethod.POST, "/bo/auth/login"), new LoginEndpoint(operatorRepository, sessionStore)),
                Map.entry(new RouteKey(HttpMethod.POST, "/bo/auth/logout"), new LogoutEndpoint(sessionStore)),
                Map.entry(new RouteKey(HttpMethod.GET, "/bo/auth/me"), new MeEndpoint(sessionStore)),

                Map.entry(new RouteKey(HttpMethod.GET, "/bo/common-code"),
                        new AuthorizedEndpoint(Screen.COMMON_CODE, Action.VIEW, sessionStore,
                                new ListCommonCodeEndpoint(commonCodeRepository))),
                Map.entry(new RouteKey(HttpMethod.POST, "/bo/common-code"),
                        new AuthorizedEndpoint(Screen.COMMON_CODE, Action.CREATE, sessionStore,
                                new CreateCommonCodeEndpoint(commonCodeRepository))),
                Map.entry(new RouteKey(HttpMethod.PUT, "/bo/common-code"),
                        new AuthorizedEndpoint(Screen.COMMON_CODE, Action.SAVE, sessionStore,
                                new SaveCommonCodeEndpoint(commonCodeRepository))),
                Map.entry(new RouteKey(HttpMethod.DELETE, "/bo/common-code"),
                        new AuthorizedEndpoint(Screen.COMMON_CODE, Action.DELETE, sessionStore,
                                new DeleteCommonCodeEndpoint(commonCodeRepository))),

                Map.entry(new RouteKey(HttpMethod.GET, "/bo/devices"),
                        new AuthorizedEndpoint(Screen.DEVICE, Action.VIEW, sessionStore,
                                new ListDeviceEndpoint(deviceRepository))),
                Map.entry(new RouteKey(HttpMethod.POST, "/bo/devices"),
                        new AuthorizedEndpoint(Screen.DEVICE, Action.CREATE, sessionStore,
                                new CreateDeviceEndpoint(deviceRepository))),
                Map.entry(new RouteKey(HttpMethod.PUT, "/bo/devices"),
                        new AuthorizedEndpoint(Screen.DEVICE, Action.SAVE, sessionStore,
                                new SaveDeviceEndpoint(deviceRepository))),
                Map.entry(new RouteKey(HttpMethod.DELETE, "/bo/devices"),
                        new AuthorizedEndpoint(Screen.DEVICE, Action.DELETE, sessionStore,
                                new DeleteDeviceEndpoint(deviceRepository))));
    }

    /** Order API port — the business-facing REST surface, plus the WebSocket transport. Not the port BO is published on. */
    private static Map<RouteKey, RestEndpoint> apiRoutes(EventPublisher eventPublisher) {
        return Map.of(
                new RouteKey(HttpMethod.GET, "/health"), new HealthCheckEndpoint(),
                new RouteKey(HttpMethod.POST, "/orders"), new CreateOrderEndpoint(eventPublisher));
    }

    /**
     * The bootstrap problem for a session-based, no-Spring BO: something has
     * to create the first 전체관리자 account, since BO itself requires
     * being logged in to manage operators (docs/adr/0004). Reads
     * {@code BO_ADMIN_USERNAME}/{@code BO_ADMIN_PASSWORD} once, only when
     * the operator table/store is empty — never seeds a guessable default.
     */
    private static void seedSuperAdminIfNeeded(OperatorRepository operatorRepository) {
        if (operatorRepository.existsAny()) {
            return;
        }
        String username = System.getenv("BO_ADMIN_USERNAME");
        String password = System.getenv("BO_ADMIN_PASSWORD");
        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            log.warn("No BO operator accounts exist and BO_ADMIN_USERNAME/BO_ADMIN_PASSWORD are not set — "
                    + "BO login will be unusable until an operator is seeded.");
            return;
        }
        operatorRepository.create(username, PasswordHasher.hash(password), "Super Admin", true);
        log.info("Seeded initial BO super admin operator '{}'", username);
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
