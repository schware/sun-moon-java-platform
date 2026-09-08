package com.sunmoon.platform.infrastructure.persistence;

public record PostgresConnectionSettings(String jdbcUrl, String username, String password, int maxPoolSize) {

    public static PostgresConnectionSettings fromEnv() {
        var env = System.getenv();
        return new PostgresConnectionSettings(
                env.getOrDefault("POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/sunmoon"),
                env.getOrDefault("POSTGRES_USER", "app"),
                env.getOrDefault("POSTGRES_PASSWORD", "app"),
                Integer.parseInt(env.getOrDefault("POSTGRES_POOL_SIZE", "5"))
        );
    }
}
