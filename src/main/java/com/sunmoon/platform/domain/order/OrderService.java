package com.sunmoon.platform.domain.order;

import com.sunmoon.platform.infrastructure.messaging.EventPublisher;
import com.sunmoon.platform.infrastructure.messaging.OrderCreatedEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final EventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, EventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    public Order create(String customerId, java.math.BigDecimal amount) {
        Order saved = orderRepository.save(new Order(null, customerId, amount));
        publish(saved);
        return saved;
    }

    @CircuitBreaker(name = "orderEvents", fallbackMethod = "publishFallback")
    @Retry(name = "orderEvents")
    void publish(Order order) {
        eventPublisher.publish(new OrderCreatedEvent(order.id(), order.customerId(), order.amount()));
    }

    @SuppressWarnings("unused")
    private void publishFallback(Order order, Throwable ex) {
        // Order is already persisted; losing the event notification is
        // acceptable for this demo scaffold. A production system would
        // write to an outbox table here instead.
    }
}
