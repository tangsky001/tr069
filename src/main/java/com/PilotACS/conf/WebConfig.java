/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.347
 *
 */

package com.PilotACS.conf;

import com.PilotACS.acl.OltAclInterceptor;
import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.common.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/** Web 配置 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final OltAclInterceptor oltAclInterceptor;
    private final CpeProperties cpeProperties;

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api", HandlerTypePredicate.forAnnotation(
                        org.springframework.web.bind.annotation.RestController.class)
                .and(clazz -> !clazz.getPackageName().startsWith("org.springdoc")));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/doc/index.html").setViewName("redirect:/doc.html");
        registry.addRedirectViewController("/app/login", "/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<String> excludes = new ArrayList<>(List.of(
                "/api/user/login",
                "/error",
                "/favicon.ico",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/doc.html",
                "/doc/index.html",
                "/webjars/**",
                "/knife4j/**"));
        cpeProperties.getPaths().forEach(p -> {
            String path = p.startsWith("/") ? p : "/" + p;
            excludes.add(path);
            excludes.add(path + "/**");
        });
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(excludes);
        registry.addInterceptor(oltAclInterceptor)
                .addPathPatterns("/api/olt/**");
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
