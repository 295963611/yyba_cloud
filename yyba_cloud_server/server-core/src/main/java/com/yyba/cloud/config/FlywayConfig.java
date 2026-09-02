package com.yyba.cloud.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 开发阶段迁移脚本可能仅调整注释等非结构性内容，已应用版本的 checksum 会变化。
 * 启动时先 repair 同步 flyway_schema_history，再执行 migrate。
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
