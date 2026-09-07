package com.sunmoon.platform.domain.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotBlank String customerId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount) {
}
