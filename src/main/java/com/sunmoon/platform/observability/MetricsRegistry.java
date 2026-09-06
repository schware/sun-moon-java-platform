package com.sunmoon.platform.observability;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/** One process-wide Micrometer registry, scraped in Prometheus text format via GET /metrics. No Prometheus server needed to verify this — the registry renders the scrape text itself. */
public final class MetricsRegistry {

    private static final PrometheusMeterRegistry REGISTRY = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    public static PrometheusMeterRegistry get() {
        return REGISTRY;
    }

    private MetricsRegistry() {
    }
}
