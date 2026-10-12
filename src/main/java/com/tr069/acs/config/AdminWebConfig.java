package com.tr069.acs.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理端接口鉴权注册。
 *
 * <p>只拦 {@code /api/**}：设备侧 CWMP 接口与前端静态资源不在此前缀下。</p>
 *
 * <p>登录、登出必须排除——那时还没有令牌，拦了就永远拿不到令牌。</p>
 */
@Configuration
@RequiredArgsConstructor
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/user/login", "/api/user/logout");
    }
}
