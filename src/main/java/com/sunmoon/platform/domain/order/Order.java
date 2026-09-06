package com.sunmoon.platform.domain.order;

import java.math.BigDecimal;

public record Order(long id, String customerId, BigDecimal amount) {
}
