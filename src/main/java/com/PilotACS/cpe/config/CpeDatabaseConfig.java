/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.config;

import com.PilotACS.common.log.Logs;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * CPE 参数树/机型模板库专用 SQLite 数据源（template.pack）。
 * <p>库文件随打包内置在 resources/data/template.pack，首次启动（或运行目录没有时）
 * 释放到 acs.cpe.sqlite-path 指定的运行目录路径再打开。
 * <p>存储职责：设备参数树（能力树）+ 机型模板库留在 SQLite；
 * 设备台账/下发任务在 MySQL（见 CpeMysqlSchemaInitializer 与 sql/mysql.sql）。
 */
@Configuration
public class CpeDatabaseConfig {

    @Bean(destroyMethod = "close")
    public DataSource cpeDataSource(CpeProperties props) {
        File file = resolveDatabaseFile(props);
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5000);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + file.getAbsolutePath());

        // Hikari 包一层，控制连接数：SQLite 单写多读，4 个连接足够
        HikariDataSource hikari = new HikariDataSource();
        hikari.setDataSource(ds);
        hikari.setMaximumPoolSize(4);
        hikari.setMinimumIdle(1);
        hikari.setPoolName("cpe-sqlite");
        Logs.info(Logs.Module.CPE, "SQLite 参数树/模板库就绪", "文件", file.getAbsolutePath());
        return hikari;
    }

    @Bean
    public JdbcTemplate cpeJdbcTemplate(@Qualifier("cpeDataSource") DataSource cpeDataSource) {
        return new JdbcTemplate(cpeDataSource);
    }

    @Bean
    public PlatformTransactionManager cpeTxManager(@Qualifier("cpeDataSource") DataSource cpeDataSource) {
        return new DataSourceTransactionManager(cpeDataSource);
    }

    /** SQLite 事务模板（整树重写用），供 CpeStore 显式注入 */
    @Bean
    public org.springframework.transaction.support.TransactionTemplate cpeTxTemplate(
            @Qualifier("cpeTxManager") PlatformTransactionManager cpeTxManager) {
        return new org.springframework.transaction.support.TransactionTemplate(cpeTxManager);
    }

    /** 运行目录没有库文件时，从包内 resources/data/template.pack 释放一份 */
    private File resolveDatabaseFile(CpeProperties props) {
        File file = new File(props.getSqlitePath());
        if (file.exists()) {
            return file;
        }
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            ClassPathResource resource = new ClassPathResource("data/template.pack");
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Logs.info(Logs.Module.CPE, "内置模板库已释放到运行目录",
                        "来源", "classpath:data/template.pack", "目标", file.getAbsolutePath());
            } else {
                Logs.info(Logs.Module.CPE, "包内未内置 template.pack，启动时新建空库",
                        "文件", file.getAbsolutePath());
            }
        } catch (Exception e) {
            throw new IllegalStateException("template.pack 释放失败: " + file.getAbsolutePath(), e);
        }
        return file;
    }
}
