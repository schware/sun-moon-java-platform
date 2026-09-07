package com.sunmoon.platform.infrastructure.messaging;

import java.math.BigDecimal;

public record OrderCreatedEvent(Long orderId, String customerId, BigDecimal amount) {
}
