package com.sunmoon.platform.infrastructure.persistence;

import com.sunmoon.platform.domain.order.Order;
import com.sunmoon.platform.domain.order.OrderRepository;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;

/**
 * Real adapter over PostgreSQL via MyBatis. Not live-verified — no local
 * Postgres in this dev environment, by choice; verification deferred to
 * deploy time (see docs/adr/0005). Swap this in for
 * {@link InMemoryOrderRepository} once a real Postgres instance is
 * reachable (point {@code PostgresConnectionSettings.fromEnv()} at it, run
 * the Flyway migrations in {@code db/migration/}, then construct this instead).
 */
public final class MyBatisOrderRepository implements OrderRepository {

    private final SqlSessionFactory sqlSessionFactory;

    public MyBatisOrderRepository(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    @Override
    public List<Order> findPage(int offset, int limit) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            List<OrderRow> rows = session.getMapper(OrderMapper.class).findPage(offset, limit);
            return rows.stream()
                    .map(row -> new Order(row.getId(), row.getCustomerId(), row.getAmount()))
                    .toList();
        }
    }
}
