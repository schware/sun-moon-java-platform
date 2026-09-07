package com.sunmoon.platform.config;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    // disk_free_bytes/disk_total_bytes (from Micrometer's DiskSpaceMetrics
    // binder) are tagged with the JVM's absolute working directory — same
    // leak as the health "path" detail (see RedactedDiskSpaceHealthIndicator),
    // just reaching /actuator/prometheus through a different Spring Boot
    // subsystem. A MeterFilter rewrites the tag value at the registry level
    // regardless of which binder produced it.
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> redactDiskSpacePathTag() {
        return registry -> registry.config()
                .meterFilter(MeterFilter.replaceTagValues("path", PathRedactor::redact));
    }
}
