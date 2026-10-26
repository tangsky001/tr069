package com.acsr.acs.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 双数据源：
 * - MySQL（@Primary）：台账、账号、任务、审计、日志
 * - SQLite：仅光猫适配数据（能力树 / 参数映射 / 命中记录）
 */
@Slf4j
@Configuration
public class DataSourceConfig {

  @Bean("mysqlDataSource")
  @Primary
  @ConfigurationProperties("spring.datasource.mysql")
  public DataSource mysqlDataSource() {
    return DataSourceBuilder.create().build();
  }

  @Bean("sqliteDataSource")
  @ConfigurationProperties("spring.datasource.sqlite")
  public DataSource sqliteDataSource() {
    return DataSourceBuilder.create().build();
  }

  /** SQLite 文件路径（jdbc-url 形如 jdbc:sqlite:./data/tr069_tpl.pack）。 */
  @org.springframework.beans.factory.annotation.Value("${spring.datasource.sqlite.jdbc-url}")
  private String sqliteUrl;

  /** 取 SQLite 文件所在目录，确保目录存在。 */
  private String sqliteDir() {
    String path = sqliteUrl.startsWith("jdbc:sqlite:") ? sqliteUrl.substring("jdbc:sqlite:".length()) : sqliteUrl;
    int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return idx <= 0 ? "." : path.substring(0, idx);
  }

  @Bean("mysqlJdbcTemplate")
  public JdbcTemplate mysqlJdbcTemplate(@Qualifier("mysqlDataSource") DataSource ds) {
    return new JdbcTemplate(ds);
  }

  @Bean("sqliteJdbcTemplate")
  public JdbcTemplate sqliteJdbcTemplate(@Qualifier("sqliteDataSource") DataSource ds) {
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    initSqlite(jdbc);
    return jdbc;
  }

  /** 打开 WAL 并建立适配库表结构。 */
  private void initSqlite(JdbcTemplate jdbc) {
    try {
      java.nio.file.Files.createDirectories(java.nio.file.Path.of(sqliteDir()));
      jdbc.execute("PRAGMA journal_mode=WAL");
      jdbc.execute("PRAGMA synchronous=NORMAL");
      jdbc.execute("PRAGMA busy_timeout=5000");

      jdbc.execute(
          """
          CREATE TABLE IF NOT EXISTS device_template (
            template_id      INTEGER PRIMARY KEY AUTOINCREMENT,
            device_brand     VARCHAR(50)  NOT NULL DEFAULT '',
            device_model     VARCHAR(100) NOT NULL,
            firmware_version VARCHAR(50)  NOT NULL DEFAULT '',
            capability_xml   TEXT         NOT NULL,
            adapt_meta_yml   TEXT         NOT NULL DEFAULT '',
            template_hash    VARCHAR(64)  NOT NULL DEFAULT '',
            create_time      DATETIME     NOT NULL,
            update_time      DATETIME     NOT NULL,
            remark           VARCHAR(255) DEFAULT ''
          )
          """);
      jdbc.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_fw ON device_template(device_model, firmware_version)");

      jdbc.execute(
          """
          CREATE TABLE IF NOT EXISTS adapt_param_map (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            template_id INTEGER      NOT NULL,
            inform_path VARCHAR(255),
            get_path    VARCHAR(255),
            set_path    VARCHAR(255),
            value_type  VARCHAR(16)  DEFAULT 'xsd:string',
            transform   VARCHAR(255)
          )
          """);
      jdbc.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS idx_tpl_set ON adapt_param_map(template_id, set_path)");

      jdbc.execute(
          """
          CREATE TABLE IF NOT EXISTS device_adapted (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            serial_number VARCHAR(64) NOT NULL,
            template_id   INTEGER     NOT NULL,
            last_match_at DATETIME,
            success_count INTEGER     DEFAULT 0
          )
          """);
      jdbc.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS idx_dev_adapted ON device_adapted(serial_number)");
      log.info("SQLite 适配库初始化完成 (data/tr069_tpl.pack, WAL)");
    } catch (Exception e) {
      log.error("SQLite 适配库初始化失败", e);
    }
  }
}
