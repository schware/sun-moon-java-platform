package com.sunmoon.platform.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;

/**
 * Wires MyBatis to PostgreSQL via HikariCP. Compiles and is structurally
 * correct, but has not been exercised against a live instance in this
 * environment — no local Postgres install, by choice; verification is
 * deferred to deploy time — see docs/adr/0005.
 */
public final class MyBatisConfig {

    public static DataSource buildDataSource(PostgresConnectionSettings settings) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(settings.jdbcUrl());
        hikariConfig.setUsername(settings.username());
        hikariConfig.setPassword(settings.password());
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setMaximumPoolSize(settings.maxPoolSize());
        return new HikariDataSource(hikariConfig);
    }

    /** Mappers are passed in rather than listed here: the kernel must not know which domains exist (docs/adr/0014). */
    public static SqlSessionFactory buildSqlSessionFactory(DataSource dataSource, Class<?>... mappers) {
        Environment environment = new Environment("postgres", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        for (Class<?> mapper : mappers) {
            configuration.addMapper(mapper);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private MyBatisConfig() {
    }
}
