package com.acsr.acs.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端静态资源由后端同源发布（resources/public，随 jar 打包）。
 * 这里做 SPA 深链接回退：一级 / 二级、不含扩展名的路径转发到 index.html，
 * 由 Vue Router 接管；静态资源（/static/js/*.js 等）带扩展名，不受影响。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/{path:[^.]+}").setViewName("forward:/index.html");
    registry.addViewController("/{a:[^.]+}/{b:[^.]+}").setViewName("forward:/index.html");
  }
}
