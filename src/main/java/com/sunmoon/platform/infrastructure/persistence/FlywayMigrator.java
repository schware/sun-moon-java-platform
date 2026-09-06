package com.sunmoon.platform.infrastructure.persistence;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/** Runs migrations from {@code src/main/resources/db/migration}. Not exercised against a live Oracle instance here — see docs/adr/0003. */
public final class FlywayMigrator {

    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();
    }

    private FlywayMigrator() {
    }
}
