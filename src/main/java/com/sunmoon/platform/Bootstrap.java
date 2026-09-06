package com.sunmoon.platform;

import com.sunmoon.platform.batch.BatchScheduler;
import com.sunmoon.platform.batch.OrderSummaryBatchJob;
import com.sunmoon.platform.batch.OrderSummaryReport;
import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.RuntimeConfig;
import com.sunmoon.platform.domain.order.OrderRepository;
import com.sunmoon.platform.infrastructure.messaging.EventPublisher;
import com.sunmoon.platform.infrastructure.messaging.InMemoryEventPublisher;
import com.sunmoon.platform.infrastructure.persistence.InMemoryOrderRepository;
import com.sunmoon.platform.transport.http.CreateOrderEndpoint;
import com.sunmoon.platform.transport.http.HealthCheckEndpoint;
import com.sunmoon.platform.transport.http.MetricsEndpoint;
import com.sunmoon.platform.transport.http.RestEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Composition root. No DI container (Spring dropped — see docs/adr/0002):
 * every wire-up happens here, by hand. Persistence/Cache/Messaging default
 * to their in-memory fake adapters — there's no live Oracle/Redis/Kafka in
 * most dev environments this runs in yet (see docs/adr/0003); swap the
 * `new InMemoryXxx()` lines below for the real adapters once that
 * infrastructure exists.
 */
public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    public static void main(String[] args) throws Exception {
        RuntimeConfig config = RuntimeConfig.fromEnv();

        OrderRepository orderRepository = new InMemoryOrderRepository();
        EventPublisher eventPublisher = new InMemoryEventPublisher();

        Map<String, RestEndpoint> httpRoutes = Map.of(
                "/health", new HealthCheckEndpoint(),
                "/metrics", new MetricsEndpoint(),
                "/orders", new CreateOrderEndpoint(eventPublisher)
        );

        runStartupBatchJob(orderRepository);

        CoreRuntime runtime = new CoreRuntime(config, httpRoutes);
        runtime.start();
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
