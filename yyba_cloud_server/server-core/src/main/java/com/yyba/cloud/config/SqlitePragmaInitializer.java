package com.yyba.cloud.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

@Slf4j
@Component
@RequiredArgsConstructor
public class SqlitePragmaInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private final DataSource dataSource;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=60000");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            try (ResultSet rs = statement.executeQuery("PRAGMA journal_mode")) {
                if (rs.next()) {
                    log.info("SQLite journal_mode={}", rs.getString(1));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to apply SQLite pragmas: {}", e.getMessage());
        }
    }
}
