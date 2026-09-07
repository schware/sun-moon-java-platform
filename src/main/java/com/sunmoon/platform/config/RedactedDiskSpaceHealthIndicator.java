package com.sunmoon.platform.config;

import org.springframework.boot.actuate.autoconfigure.system.DiskSpaceHealthIndicatorProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.system.DiskSpaceHealthIndicator;
import org.springframework.stereotype.Component;

import java.io.File;

// Overrides Spring Boot's auto-configured "diskSpaceHealthIndicator" bean
// (same name — DiskSpaceHealthContributorAutoConfiguration backs off once
// a bean by that name already exists) so /actuator/health doesn't leak the
// server's home directory in the "path" detail.
@Component("diskSpaceHealthIndicator")
public class RedactedDiskSpaceHealthIndicator extends DiskSpaceHealthIndicator {

    private final File path;

    public RedactedDiskSpaceHealthIndicator(DiskSpaceHealthIndicatorProperties properties) {
        super(properties.getPath(), properties.getThreshold());
        this.path = properties.getPath();
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        super.doHealthCheck(builder);
        builder.withDetail("path", PathRedactor.redact(path.getAbsolutePath()));
    }
}
