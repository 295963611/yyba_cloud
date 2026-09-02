package com.yyba.cloud.launcher;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Spring Boot 启动前从 SQLite 读取端口等引导配置。
 */
public final class BootstrapConfig {

    private static final int DEFAULT_PORT = 8080;

    private BootstrapConfig() {
    }

    public static int readPort() {
        Path dbPath = Path.of(System.getProperty("user.home"), ".yyba_cloud", "yyba_cloud.db");
        if (!dbPath.toFile().exists()) {
            return DEFAULT_PORT;
        }
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT config_value FROM app_config WHERE config_key = 'server.port'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString(1).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_PORT;
    }
}
