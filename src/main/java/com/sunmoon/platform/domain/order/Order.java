package com.sunmoon.platform.domain.order;

import java.math.BigDecimal;

public record Order(Long id, String customerId, BigDecimal amount) {

    public Order withId(long id) {
        return new Order(id, customerId, amount);
    }
}
