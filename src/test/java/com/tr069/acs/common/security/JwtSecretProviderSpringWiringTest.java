package com.tr069.acs.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 容器能不能把 JwtSecretProvider 造出来。
 *
 * <p>只直接 new 是不够的：本类有两个构造函数，去掉 {@code @Autowired} 后单测照样绿，
 * 而 Spring 会退回无参构造并在启动时抛 {@code No default constructor found}——
 * 服务直接起不来。这里走真实的构造函数选择路径，把这个坑钉住。</p>
 *
 * <p>之所以用 ApplicationContextRunner 而不是 @SpringBootTest：
 * 后者要连 MySQL、要监听 8888/8899/9090，跑个单测不该有这些副作用。</p>
 */
class JwtSecretProviderSpringWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                // 解析 ${admin.jwt.secret:} 占位符，缺省时拿到空串
                if (context instanceof GenericApplicationContext generic) {
                    generic.registerBean(PropertySourcesPlaceholderConfigurer.class);
                    generic.registerBean(JwtSecretProvider.class);
                }
            });

    /**
     * Spring 能按构造函数把 bean 造出来，并且拿到可用的 HS256 密钥。
     */
    @Test
    void constructsBeanViaContainer() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JwtSecretProvider.class);
            assertThat(context.getBean(JwtSecretProvider.class).getKey().getEncoded())
                    .hasSizeGreaterThanOrEqualTo(32);
        });
    }
}
