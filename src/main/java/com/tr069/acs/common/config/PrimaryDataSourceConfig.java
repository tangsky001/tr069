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
 */
@Configuration
public class PrimaryDataSourceConfig {

    /**
     * bean 名沿用 {@code dataSource}，与自动配置的默认名一致，避免按名注入失效。
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
