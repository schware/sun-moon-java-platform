package com.sunmoon.platform.domain.order;

import java.util.List;

/**
 * Port (in the hexagonal-architecture sense): the domain/application layer
 * depends on this interface, never on MyBatis or Oracle directly. Real
 * adapter: {@code infrastructure.persistence.MyBatisOrderRepository}. Fake
 * adapter used everywhere this environment can't reach a live Oracle
 * instance: {@code infrastructure.persistence.InMemoryOrderRepository} — see
 * docs/adr/0003.
 */
public interface OrderRepository {
    List<Order> findPage(int offset, int limit);
}
