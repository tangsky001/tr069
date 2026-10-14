package com.tr069.acs.openapi.config;

import com.tr069.acs.config.AdminAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 北向接口鉴权注册。
 *
 * <p>鉴权直接复用管理端的 {@link AdminAuthInterceptor}：北向同样要求先登录换 JWT，
 * 令牌经 {@code X-Token} 或 {@code Authorization: Bearer} 传递。同一把令牌既能进管理页面
 * 也能调北向接口，无需再维护一套 appKey / secret。</p>
 *
 * <p>注意该拦截器原样返回管理端的 401 响应（业务码 50008），北向调用方按 HTTP 401 处理即可。</p>
 */
@Configuration
@RequiredArgsConstructor
public class OpenApiWebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    @Value("${acs.openapi.path-prefix:/openapi/v1}")
    private String pathPrefix;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String prefix = pathPrefix == null || pathPrefix.isBlank() ? "/openapi/v1" : pathPrefix.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        registry.addInterceptor(adminAuthInterceptor).addPathPatterns(prefix + "/**");
    }
}
