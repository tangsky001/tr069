/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.conf;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * MySQL 主库显式声明（@Primary）。
 * <p>背景：cpe 模块引入第二个 DataSource（SQLite template.pack）后，
 * Spring Boot 的 DataSource/JdbcTemplate/事务自动配置会因
 * ConditionalOnMissingBean / ConditionalOnSingleCandidate 全部失效，
 * 必须在这里按原 auto-config 的语义显式补上主库三件套。
 */
@Configuration
public class DataSourceConfig {

    /**
     * MySQL 主库连接池（Hikari），标注 @Primary：MyBatis-Plus/JdbcTemplate/事务都用它。
     * DataSourceProperties 用 auto-config 注册的那份（@EnableConfigurationProperties 无条件注册），
     * 这里不要再手写，否则同名 bean 冲突。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /** 主库 JdbcTemplate（bean 名 jdbcTemplate，与原 auto-config 一致） */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @Primary
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
