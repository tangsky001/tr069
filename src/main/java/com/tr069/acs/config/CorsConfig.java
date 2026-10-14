package com.tr069.acs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 跨域配置，供本地管理端开发联调使用。
 *
 * <p>只对管理端路径生效：北向接口是服务端到服务端调用，不需要跨域放开；
 * 放在 {@code /**} 上等于给全站开了任意来源的凭证请求。</p>
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.addExposedHeader("X-Token");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        // 兼容 vue-admin-template 的老登录路径
        source.registerCorsConfiguration("/vue-admin-template/**", config);
        return new CorsFilter(source);
    }
}
