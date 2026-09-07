package com.sunmoon.platform.batch;

import com.sunmoon.platform.domain.order.Order;
import com.sunmoon.platform.domain.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

// Deliberately simple: a single startup pass, not full Spring Batch
// (Job/Step/Chunk + JobRepository schema). See README "Status" for why.
@Component
public class OrderSummaryStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderSummaryStartupRunner.class);

    private final OrderRepository orderRepository;

    public OrderSummaryStartupRunner(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Order> orders = orderRepository.findAll();
        BigDecimal totalRevenue = orders.stream()
                .map(Order::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        OrderSummaryReport report = new OrderSummaryReport(orders.size(), totalRevenue);
        log.info("order-summary-job finished: {} orders, revenue={}", report.orderCount(), report.totalRevenue());
    }
}
