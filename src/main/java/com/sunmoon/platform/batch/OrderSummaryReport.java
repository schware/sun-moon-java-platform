package com.sunmoon.platform.batch;

import java.math.BigDecimal;

public record OrderSummaryReport(JobExecution jobExecution, int orderCount, BigDecimal totalRevenue) {
}
