package com.tr069.acs.common.startup;

import com.tr069.acs.exec.config.DeviceTaskSchemaInitializer;
import com.tr069.acs.openapi.config.OpenApiSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 唯一的建表脚本能否支撑所有模块。
 *
 * <p>脚本的使用者是各模块的补建组件，它们各自声明了负责的表。这些表一旦有人漏写进
 * {@code sql/schema.sql}，只有等现场首次启动才会发现「自动补表失败」，而且那时已经
 * 上了生产设备。这里直接把各组件声明的表名取出来，逐张确认脚本里有定义。</p>
 *
 * <p>表名从这些类的 {@code TABLES} 字段反射读取，避免测试里再抄一份清单跟着一起漂移。</p>
 */
class SchemaScriptTest {

    private static final List<Class<?>> INITIALIZERS = List.of(
            BizSchemaInitializer.class,
            CapabilitySchemaInitializer.class,
            ConfigTemplateSchemaInitializer.class,
            ModelSchemaInitializer.class,
            DeviceTaskSchemaInitializer.class,
            OpenApiSchemaInitializer.class);

    /**
     * 脚本要能被各模块读到：由 pom 从 sql/schema.sql 复制到 classpath。
     */
    @Test
    void scriptIsOnClasspath() {
        assertThat(new ClassPathResource(SchemaScript.RESOURCE).exists())
                .as("classpath:%s 不存在，检查 pom.xml 是否把 sql/schema.sql 复制进去",
                        SchemaScript.RESOURCE)
                .isTrue();
    }

    /**
     * 每个模块声明的表，脚本里都得有建表语句。
     */
    @Test
    void everyModuleTableIsDefinedInTheSingleScript() {
        for (Class<?> type : INITIALIZERS) {
            List<String> tables = declaredTables(type);
            assertThat(tables).as("%s 声明了负责的表", type.getSimpleName()).isNotEmpty();
            assertThat(SchemaScript.createStatements(tables))
                    .as("%s 负责的表应在 %s 中全部有定义", type.getSimpleName(), SchemaScript.RESOURCE)
                    .hasSize(tables.size());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> declaredTables(Class<?> type) {
        try {
            Field field = type.getDeclaredField("TABLES");
            field.setAccessible(true);
            return (List<String>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读取 " + type.getSimpleName() + ".TABLES 失败，" +
                    "该字段是本地取表名的唯一依据，改名前请同步本测试", e);
        }
    }
}
