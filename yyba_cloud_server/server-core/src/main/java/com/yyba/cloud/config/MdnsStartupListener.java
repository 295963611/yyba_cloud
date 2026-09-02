package com.yyba.cloud.config;

import com.yyba.cloud.service.MdnsRegistrar;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MdnsStartupListener implements ApplicationListener<ApplicationReadyEvent> {

    private final MdnsRegistrar mdnsRegistrar;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof ServletWebServerApplicationContext webContext)) {
            return;
        }
        int port = webContext.getWebServer().getPort();
        mdnsRegistrar.register(port);
    }
}
