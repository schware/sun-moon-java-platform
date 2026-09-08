package com.sunmoon.platform;

import com.sunmoon.platform.batch.BatchScheduler;
import com.sunmoon.platform.batch.OrderSummaryBatchJob;
import com.sunmoon.platform.batch.OrderSummaryReport;
import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.RuntimeConfig;
import com.sunmoon.platform.domain.operator.OperatorRepository;
import com.sunmoon.platform.domain.order.OrderRepository;
import com.sunmoon.platform.infrastructure.auth.InMemorySessionStore;
import com.sunmoon.platform.infrastructure.auth.PasswordHasher;
import com.sunmoon.platform.infrastructure.auth.SessionStore;
import com.sunmoon.platform.infrastructure.messaging.EventPublisher;
import com.sunmoon.platform.infrastructure.messaging.InMemoryEventPublisher;
import com.sunmoon.platform.infrastructure.persistence.InMemoryOperatorRepository;
import com.sunmoon.platform.infrastructure.persistence.InMemoryOrderRepository;
import com.sunmoon.platform.transport.http.CreateOrderEndpoint;
import com.sunmoon.platform.transport.http.HealthCheckEndpoint;
import com.sunmoon.platform.transport.http.MetricsEndpoint;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.http.bo.LoginEndpoint;
import com.sunmoon.platform.transport.http.bo.LogoutEndpoint;
import com.sunmoon.platform.transport.http.bo.MeEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Composition root. No DI container (Spring dropped — see docs/adr/0002):
 * every wire-up happens here, by hand. Persistence/Cache/Messaging default
 * to their in-memory fake adapters — there's no live Postgres/Redis/Kafka in
 * this dev environment, by choice (see docs/adr/0003, docs/adr/0005); swap
 * the `new InMemoryXxx()` lines below for the real adapters once that
 * infrastructure exists (deploy time, not this machine).
 */
public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    public static void main(String[] args) throws Exception {
        RuntimeConfig config = RuntimeConfig.fromEnv();

        OrderRepository orderRepository = new InMemoryOrderRepository();
        EventPublisher eventPublisher = new InMemoryEventPublisher();
        OperatorRepository operatorRepository = new InMemoryOperatorRepository();
        SessionStore sessionStore = new InMemorySessionStore();

        seedSuperAdminIfNeeded(operatorRepository);

        Map<String, RestEndpoint> httpRoutes = new HashMap<>(Map.of(
                "/health", new HealthCheckEndpoint(),
                "/metrics", new MetricsEndpoint(),
                "/orders", new CreateOrderEndpoint(eventPublisher)
        ));
        httpRoutes.put("/bo/auth/login", new LoginEndpoint(operatorRepository, sessionStore));
        httpRoutes.put("/bo/auth/logout", new LogoutEndpoint(sessionStore));
        httpRoutes.put("/bo/auth/me", new MeEndpoint(sessionStore));

        runStartupBatchJob(orderRepository);

        CoreRuntime runtime = new CoreRuntime(config, httpRoutes);
        runtime.start();
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
