package com.tr069.acs.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 主库（MySQL）数据源——必须显式声明，不能只靠自动配置。
 *
 * <p>原因：模板库那边注册了一个 SQLite 的 {@link DataSource} bean，
 * 而 Spring Boot 的 {@code DataSourceAutoConfiguration} 挂着
 * {@code @ConditionalOnMissingBean(DataSource.class)}——只要容器里已经存在
 * <b>任何一个</b> DataSource，它就认为「用户自己配了数据源」，
 * 不再为 {@code spring.datasource.*} 建主库。后果是 MyBatis 与主
 * {@link JdbcTemplate} 全部绑到 SQLite 上，启动时一片
 * {@code no such table: cpe_device / cpe_parameter / openapi_webhook}。</p>
 *
 * <p>所以这里把主库补回来并标 {@code @Primary}：
 * MyBatis 与各业务初始化器按类型注入时拿到的是 MySQL；
 * 模板库那份靠 {@code @Qualifier("tr069TplDataSource")} /
 * {@code @Qualifier("tr069TplJdbcTemplate")} 按名取用，两者互不干扰。</p>
 */
@Configuration
public class PrimaryDataSourceConfig {

    /**
     * bean 名沿用 {@code dataSource}，与自动配置的默认名一致，避免按名注入失效。
     *
     * <p>必须用 {@link DataSourceProperties} 来建，不能
     * {@code DataSourceBuilder.create().build()} 配 {@code @ConfigurationProperties}：
     * 后者没有 {@code setUrl()} 这类 JavaBean setter，{@code spring.datasource.url}
     * 根本绑不进去，起池时才报
     * {@code dataSource or dataSourceClassName or jdbcUrl is required}。</p>
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    /**
     * 主库 JdbcTemplate：同理，模板库的 JdbcTemplate 先注册后，
     * {@code JdbcTemplateAutoConfiguration} 也不再提供主库那份，必须显式给一个。
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
