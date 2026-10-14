package com.tr069.acs.common.startup;

import com.tr069.acs.exec.config.DeviceTaskSchemaInitializer;
import com.tr069.acs.openapi.config.OpenApiSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自带建表逻辑必须在容器刷新结束前跑完。
 *
 * <p>ApplicationRunner 是在 {@code SpringApplication.run} 的收尾阶段才被调用的，那时容器早已
 * 刷新结束，{@code @Scheduled} 的定时任务也已经启动，两者是并发的。于是 Webhook 重试线程会
 * 抢在这些 Runner 前面查 {@code openapi_webhook_delivery}，日志里就是一句
 * {@code Table 'openapi_webhook_delivery' doesn't exist} 外加几十行堆栈。</p>
 *
 * <p>改成 InitializingBean 后，建表发生在 Bean 初始化阶段，一定早于任何定时任务。
 * 这里把这个规则钉住，防止以后有人图省事改回 ApplicationRunner。</p>
 */
class SchemaInitializerOrderTest {

    /** 本项目中所有「启动阶段幂等补表」的组件。 */
    private static final List<Class<?>> INITIALIZERS = List.of(
            BizSchemaInitializer.class,
            CapabilitySchemaInitializer.class,
            ConfigTemplateSchemaInitializer.class,
            ModelSchemaInitializer.class,
            DeviceTaskSchemaInitializer.class,
            OpenApiSchemaInitializer.class);

    /**
     * 走 Bean 初始化回调，才能在定时任务启动之前完成建表。
     */
    @Test
    void runsDuringBeanInitialization() {
        for (Class<?> type : INITIALIZERS) {
            assertThat(InitializingBean.class)
                    .as("%s 必须实现 InitializingBean", type.getSimpleName())
                    .isAssignableFrom(type);
            assertThat(ApplicationRunner.class.isAssignableFrom(type))
                    .as("%s 不能改成 ApplicationRunner，否则会晚于定时任务", type.getSimpleName())
                    .isFalse();
        }
    }
}
