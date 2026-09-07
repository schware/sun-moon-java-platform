package com.sunmoon.platform.domain.order;

import java.util.List;

public interface OrderRepository {

    Order save(Order order);

    List<Order> findAll();
}
