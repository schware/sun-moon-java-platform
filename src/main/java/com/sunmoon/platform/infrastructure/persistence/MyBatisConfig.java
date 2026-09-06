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
 * Wires MyBatis to Oracle via HikariCP. Compiles and is structurally
 * correct, but has not been exercised against a live Oracle instance in
 * this environment (no Docker/Oracle available here) — see docs/adr/0003.
 */
public final class MyBatisConfig {

    public static DataSource buildDataSource(OracleConnectionSettings settings) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(settings.jdbcUrl());
        hikariConfig.setUsername(settings.username());
        hikariConfig.setPassword(settings.password());
        hikariConfig.setDriverClassName("oracle.jdbc.OracleDriver");
        hikariConfig.setMaximumPoolSize(settings.maxPoolSize());
        return new HikariDataSource(hikariConfig);
    }

    public static SqlSessionFactory buildSqlSessionFactory(DataSource dataSource) {
        Environment environment = new Environment("oracle", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(OrderMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private MyBatisConfig() {
    }
}
