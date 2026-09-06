package com.sunmoon.platform.infrastructure.persistence;

public record OracleConnectionSettings(String jdbcUrl, String username, String password, int maxPoolSize) {

    public static OracleConnectionSettings fromEnv() {
        var env = System.getenv();
        return new OracleConnectionSettings(
                env.getOrDefault("ORACLE_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"),
                env.getOrDefault("ORACLE_USER", "app"),
                env.getOrDefault("ORACLE_PASSWORD", "app"),
                Integer.parseInt(env.getOrDefault("ORACLE_POOL_SIZE", "5"))
        );
    }
}
