package com.tr069.acs.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 在线文档界面的偏好设置（语言、页脚），写在代码里而不是 {@code application.yml}。
 *
 * <p>配置文件里只应该留「开关」—— {@code springdoc.enabled} 一处决定文档发不发布。
 * 语言 / 页脚这类是产品的一部分，不是现场该调的东西：留在外面，改错一个缩进就退回
 * 英文界面、带上官方页脚。</p>
 *
 * <p>做法是往 Environment 里追加一个属性源，走的仍是 Knife4j 自己的
 * {@code @ConfigurationProperties("knife4j")} 绑定，不依赖它自动配置里的 bean 怎么定义，
 * Knife4j 升级内部结构也不会失效。这里用 {@code addLast}，优先级最低：真要在配置里覆盖，
 * 在 {@code application.yml} 里照样写会赢。</p>
 */
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

    /** 排最后：任何地方显式配置的值都比这里的默认值优先。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
