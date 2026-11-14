/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.563
 *
 */

package com.PilotACS.conf;

import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import jakarta.servlet.MultipartConfigElement;

// 上传大小限制：代码写死 1GB，不落配置文件
@Configuration
public class MultipartLimitConfig {

    private static final DataSize MAX_SIZE = DataSize.ofGigabytes(1);

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(MAX_SIZE);
        factory.setMaxRequestSize(MAX_SIZE);
        return factory.createMultipartConfig();
    }
}
