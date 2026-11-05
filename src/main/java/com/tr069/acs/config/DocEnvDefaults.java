package com.tr069.acs.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/** 在线文档界面的偏好设置（语言、页脚），写在代码里而不是 {@code application.yml} */
public class DocEnvDefaults implements EnvironmentPostProcessor, Ordered {

    private static final Map<String, Object> DEFAULTS = Map.of(
            "knife4j.setting.language", "zh_cn",
            // 关掉 Knife4j 自带的官方页脚，换成下面这句自己的
            "knife4j.setting.enable-footer", false,
            "knife4j.setting.enable-footer-custom", true,
            "knife4j.setting.footer-custom-content", "TR069 ACS 接口文档");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource("docEnvDefaults", DEFAULTS));
    }

    /** 排最后：任何地方显式配置的值都比这里的默认值优先 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
