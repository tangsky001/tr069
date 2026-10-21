package com.tr069.acs.openapi.config;

import com.tr069.acs.common.startup.SchemaScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 北向事件推送两张表的自动建表，与其它自动建表同一套路。
 *
 * <p>必须 implements InitializingBean 而不是 ApplicationRunner：后者要等容器刷新结束才执行，
 * 而 {@code @Scheduled} 的 Webhook 重试在刷新结束的那一刻就已经开始计时——两者是并发的，
 * 于是重试线程抢在前面查了还没建出来的投递表，日志里就是一条
 * {@code Table 'openapi_webhook_delivery' doesn't exist} 的堆栈。放在 Bean 初始化阶段执行，
 * 一定早于任何定时任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiSchemaInitializer implements InitializingBean {

    private static final List<String> TABLES =
            List.of("openapi_webhook", "openapi_webhook_delivery");

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.openapi.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        try {
            if (allTablesExist()) {
                return;
            }
            for (String sql : SchemaScript.createStatements(TABLES)) {
                jdbcTemplate.execute(sql);
            }
        } catch (Exception e) {
            log.error("北向事件表自动建表失败，请手工执行 classpath:{}（对应仓库 sql/schema.sql）: {}",
                    SchemaScript.RESOURCE, e.getMessage());
        }
    }

    /**
     * 两张表都得在：只查订阅表的话，若订阅表已存在而投递表缺失（手工建过、或上一版脚本只建了一半），
     * 这里会误判成「已存在」直接跳过，投递记录就永远建不出来，只有到推送时才炸。
     */
    private boolean allTablesExist() {
        for (String table : TABLES) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, table);
            if (count == null || count == 0) {
                return false;
            }
        }
        return true;
    }

}
