package com.sunmoon.platform.batch;

import com.sunmoon.platform.domain.order.Order;

import java.math.BigDecimal;
import java.util.List;

final class OrderSummaryWriter implements ItemWriter<Order> {

    private int orderCount = 0;
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    @Override
    public void writeChunk(List<Order> items) {
        orderCount += items.size();
        for (Order order : items) {
            totalRevenue = totalRevenue.add(order.amount());
        }
    }

    int orderCount() {
        return orderCount;
    }

    BigDecimal totalRevenue() {
        return totalRevenue;
    }
}
