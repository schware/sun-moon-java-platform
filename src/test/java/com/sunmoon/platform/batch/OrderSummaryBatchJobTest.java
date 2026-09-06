package com.sunmoon.platform.batch;

import com.sunmoon.platform.infrastructure.persistence.InMemoryOrderRepository;
import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderSummaryBatchJobTest {

    @Test
    void runsDirectlyAndSumsRevenueAcrossChunks() {
        OrderSummaryBatchJob job = OrderSummaryBatchJob.create(new InMemoryOrderRepository(), 2);

        OrderSummaryReport report = job.run();

        assertTrue(report.jobExecution().successful());
        assertEquals(5, report.orderCount());
        assertEquals(0, new BigDecimal("310.74").compareTo(report.totalRevenue()));
        assertEquals(3, report.jobExecution().stepExecutions().get(0).chunksWritten()); // 2 + 2 + 1
    }

    @Test
    void quartzTriggersTheSameJob() throws SchedulerException, InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<OrderSummaryReport> result = new AtomicReference<>();

        BatchScheduler scheduler = new BatchScheduler();
        try {
            scheduler.scheduleOnce("test-order-summary", () -> {
                OrderSummaryBatchJob job = OrderSummaryBatchJob.create(new InMemoryOrderRepository(), 2);
                result.set(job.run());
                done.countDown();
            });

            assertTrue(done.await(5, TimeUnit.SECONDS), "Quartz never fired the job");
            assertEquals(5, result.get().orderCount());
        } finally {
            scheduler.shutdown();
        }
    }
}
