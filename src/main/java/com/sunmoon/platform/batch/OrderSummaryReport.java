package com.sunmoon.platform.batch;

import java.math.BigDecimal;

public record OrderSummaryReport(int orderCount, BigDecimal totalRevenue) {
}
