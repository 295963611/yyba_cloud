package com.yyba.cloud.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 放宽 Tomcat 对 PUT/POST 请求体的限制。
 * 默认 maxPostSize 约 2MB，会导致 20MB 分片或较大单分片上传失败。
 * max-swallow-size 见 application.yml 中 server.tomcat.max-swallow-size。
 */
@Configuration
public class TomcatUploadConfig {

    private static final int HUNDRED_MB = 100 * 1024 * 1024;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatUploadCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector ->
                connector.setMaxPostSize(HUNDRED_MB));
    }
}
