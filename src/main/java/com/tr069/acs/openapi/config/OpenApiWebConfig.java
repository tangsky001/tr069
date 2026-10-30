package com.tr069.acs.openapi.config;

import com.tr069.acs.config.AdminAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 北向接口鉴权注册。 */
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
