package com.PilotACS.conf;

import com.PilotACS.common.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Web 配置：/api 前缀、拦截器、跨域、文档路径
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    // 所有 RestController 统一加 /api 前缀（springdoc 文档控制器除外）
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api", HandlerTypePredicate.forAnnotation(
                        org.springframework.web.bind.annotation.RestController.class)
                .and(clazz -> !clazz.getPackageName().startsWith("org.springdoc")));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 前端代理 /doc → 8888，重定向到 Knife4j 文档页
        registry.addViewController("/doc/index.html").setViewName("redirect:/doc.html");
        // 二维码里的 /app/login 是装维 APP 扫码用的；用浏览器误开时跳到管理端首页
        registry.addRedirectViewController("/app/login", "/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/user/login",
                        "/error",
                        "/favicon.ico",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/doc.html",
                        "/doc/index.html",
                        "/webjars/**",
                        "/knife4j/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
