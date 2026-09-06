package com.sunmoon.platform.infrastructure.persistence;

import com.sunmoon.platform.domain.order.Order;
import com.sunmoon.platform.domain.order.OrderRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake adapter — an in-memory stand-in for {@code MyBatisOrderRepository},
 * used as the default so this Platform runs and its Batch job is verifiable
 * without a live Oracle instance (see docs/adr/0003). Seeds a small fixed
 * dataset; swap in the real MyBatis adapter once Oracle is actually available.
 */
public final class InMemoryOrderRepository implements OrderRepository {

    private final List<Order> orders;

    public InMemoryOrderRepository() {
        this(defaultSeedData());
    }

    public InMemoryOrderRepository(List<Order> orders) {
        this.orders = List.copyOf(orders);
    }

    @Override
    public List<Order> findPage(int offset, int limit) {
        if (offset >= orders.size()) {
            return List.of();
        }
        int toIndex = Math.min(offset + limit, orders.size());
        return orders.subList(offset, toIndex);
    }

    private static List<Order> defaultSeedData() {
        List<Order> seed = new ArrayList<>();
        seed.add(new Order(1, "cust-1", new BigDecimal("120.00")));
        seed.add(new Order(2, "cust-2", new BigDecimal("45.50")));
        seed.add(new Order(3, "cust-1", new BigDecimal("30.25")));
        seed.add(new Order(4, "cust-3", new BigDecimal("99.99")));
        seed.add(new Order(5, "cust-2", new BigDecimal("15.00")));
        return seed;
    }
}
