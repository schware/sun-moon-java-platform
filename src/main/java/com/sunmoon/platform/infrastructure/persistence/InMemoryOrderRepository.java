package com.sunmoon.platform.infrastructure.persistence;

import com.sunmoon.platform.domain.order.Order;
import com.sunmoon.platform.domain.order.OrderRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Default repository — no Oracle/MyBatis adapter is wired yet, so every
// environment (including production) runs against this in-memory store
// until a real adapter is added. See README "Status".
@Repository
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    public InMemoryOrderRepository() {
        // Seed a few orders so the startup batch summary has something to
        // report against a fresh in-memory store.
        save(new Order(null, "cust-1", new BigDecimal("42.50")));
        save(new Order(null, "cust-2", new BigDecimal("108.00")));
        save(new Order(null, "cust-3", new BigDecimal("15.75")));
    }

    @Override
    public Order save(Order order) {
        Order withId = order.withId(sequence.incrementAndGet());
        orders.put(withId.id(), withId);
        return withId;
    }

    @Override
    public List<Order> findAll() {
        return List.copyOf(orders.values());
    }
}
