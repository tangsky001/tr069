package com.tr069.acs.config;

import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.ErrorPageRegistrar;
import org.springframework.boot.web.server.ErrorPageRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端单页应用的路由兜底。
 *
 * <p>前端构建产物打进 jar 后由 Spring Boot 静态资源托管，
 * 使用 history 模式路由时刷新子路径会 404，这里统一转发回 index.html。</p>
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * 未命中的路径（非 /api、非静态资源）回退到前端首页，交给前端路由处理。
     */
    @Bean
    public ErrorPageRegistrar spaFallbackRegistrar() {
        return new ErrorPageRegistrar() {
            @Override
            public void registerErrorPages(ErrorPageRegistry registry) {
                registry.addErrorPages(new ErrorPage(HttpStatus.NOT_FOUND, "/index.html"));
            }
        };
    }
}
