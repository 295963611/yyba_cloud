package com.yyba.cloud.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // 勿用 /** 兜底静态资源，否则会拦截 /media/* API 并返回 404
    registry.addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/assets/");
    registry.addResourceHandler("/favicon.ico")
        .addResourceLocations("classpath:/static/favicon.ico");
    registry.addResourceHandler("/index.html")
        .addResourceLocations("classpath:/static/index.html");
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/").setViewName("forward:/index.html");
    registry.addViewController("/login").setViewName("forward:/index.html");
    registry.addViewController("/album").setViewName("forward:/index.html");
    registry.addViewController("/video").setViewName("forward:/index.html");
    registry.addViewController("/audio").setViewName("forward:/index.html");
    registry.addViewController("/files").setViewName("forward:/index.html");
    registry.addViewController("/settings").setViewName("forward:/index.html");
  }
}
