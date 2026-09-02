package com.yyba.cloud.launcher;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebServerLifecycleService {

    private final ApplicationContext applicationContext;

    private volatile boolean running = true;

    public boolean isRunning() {
        return running;
    }

    public void start() {
        getWebContext().getWebServer().start();
        running = true;
    }

    public void stop() {
        getWebContext().getWebServer().stop();
        running = false;
    }

    public int getPort() {
        ServletWebServerApplicationContext ctx = getWebContext();
        return ctx.getWebServer().getPort();
    }

    /**
     * ServletWebServerApplicationContext 是 Spring 容器本身，不是容器内的 Bean。
     */
    private ServletWebServerApplicationContext getWebContext() {
        if (applicationContext instanceof ServletWebServerApplicationContext servletContext) {
            return servletContext;
        }
        throw new IllegalStateException("当前应用上下文不是 Web 服务类型");
    }
}
