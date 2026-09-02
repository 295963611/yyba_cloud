package com.yyba.cloud.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class SqliteDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setJdbcUrl(ensureSqlitePragmas(dataSource.getJdbcUrl()));
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(30_000);
        dataSource.setMaxLifetime(1_800_000);
        dataSource.setConnectionInitSql(
                "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=60000; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;");
        dataSource.setPoolName("sqlite");
        dataSource.setAutoCommit(true);
        return dataSource;
    }

    private String ensureSqlitePragmas(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String result = url;
        if (!result.contains("busy_timeout=")) {
            result = appendParam(result, "busy_timeout", "30000");
        }
        if (!result.contains("journal_mode=")) {
            result = appendParam(result, "journal_mode", "WAL");
        }
        return result;
    }

    private String appendParam(String url, String key, String value) {
        return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
    }
}
