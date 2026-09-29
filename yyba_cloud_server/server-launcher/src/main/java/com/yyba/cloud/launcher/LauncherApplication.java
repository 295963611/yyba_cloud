package com.yyba.cloud.launcher;

import javafx.application.Application;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Toolkit;

/**
 * Spring Boot 主入口：先启动后端服务，再拉起 JavaFX 桌面界面。
 */
@SpringBootApplication(scanBasePackages = "com.yyba.cloud")
@EnableAsync
@EnableScheduling
public class LauncherApplication {

    private static ConfigurableApplicationContext springContext;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");
        configureNativeEncoding();
        ensureDataDir();
        // JavaFX 启动前初始化 AWT，否则 Windows 上 SystemTray 可能注册失败
        Toolkit.getDefaultToolkit();
        syncAwake();
        int port = BootstrapConfig.readPort();
        springContext = new SpringApplicationBuilder(LauncherApplication.class)
                .headless(false)
                .properties("server.port=" + port)
                .run(args);
        Application.launch(LauncherApp.class, args);
    }

    public static ConfigurableApplicationContext getSpringContext() {
        return springContext;
    }

    public static void shutdown() {
        try {
            if (springContext != null) {
                springContext.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            springContext = null;
            System.exit(0);   // 兜底：保证 JVM 一定终止
        }
    }


    private static void configureNativeEncoding() {
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("file.encoding", "UTF-8");
    }

    private static void ensureDataDir() {
        try {
            Path dataDir = Path.of(System.getProperty("user.home"), ".yyba_cloud");
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            throw new IllegalStateException("创建数据目录失败", e);
        }
    }

    /** 触发 AWT 事件线程就绪，避免与 JavaFX 混用时托盘注册失败。 */
    private static void syncAwake() {
        try {
            java.awt.EventQueue.invokeAndWait(() -> {});
        } catch (Exception ignored) {
        }
    }
}
