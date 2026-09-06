package com.sunmoon.platform.batch;

import com.sunmoon.platform.domain.order.Order;
import com.sunmoon.platform.domain.order.OrderRepository;

import java.util.List;

/**
 * The Java-side reference implementation of the same Job/Step/Chunk order
 * summary job {@code sun-moon-python-platform}'s batch-service and
 * {@code sun-moon-c-server}'s batch_runner already build — sums revenue per
 * chunk, reading through {@link OrderRepository} (whatever adapter is wired
 * in: {@code InMemoryOrderRepository} by default here, or
 * {@code MyBatisOrderRepository} once Oracle is reachable).
 */
public final class OrderSummaryBatchJob {

    private final BatchJob batchJob;
    private final OrderSummaryWriter writer;

    private OrderSummaryBatchJob(BatchJob batchJob, OrderSummaryWriter writer) {
        this.batchJob = batchJob;
        this.writer = writer;
    }

    public static OrderSummaryBatchJob create(OrderRepository repository, int chunkSize) {
        OrderSummaryWriter writer = new OrderSummaryWriter();
        ChunkStep<Order, Order> step = new ChunkStep<>(
                "order-summary-step",
                repository::findPage,
                order -> order,
                writer,
                chunkSize
        );
        return new OrderSummaryBatchJob(new BatchJob("order-summary-job", List.of(step)), writer);
    }

    public OrderSummaryReport run() {
        JobExecution execution = batchJob.execute();
        return new OrderSummaryReport(execution, writer.orderCount(), writer.totalRevenue());
    }
}
