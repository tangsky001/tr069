package com.acsr.acs.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** MySQL 台账库建表/初始化数据（双数据源下 Spring Boot 的 sql.init 会失效，这里显式执行）。 */
@Slf4j
@Component
public class MySqlSchemaInitializer implements InitializingBean {

  private final JdbcTemplate jdbc;

  public MySqlSchemaInitializer(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void afterPropertiesSet() {
    if (tableExists("sys_user")) {
      return;
    }
    run("db/schema-mysql.sql");
    run("db/data-mysql.sql");
    log.info("MySQL 台账库初始化完成");
  }

  private boolean tableExists(String table) {
    try {
      Integer n = jdbc.queryForObject(
          "SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
          Integer.class, table);
      return n != null && n > 0;
    } catch (Exception e) {
      return false;
    }
  }

  private void run(String location) {
    try {
      String sql = FileCopyUtils.copyToString(
          new InputStreamReader(new ClassPathResource(location).getInputStream(), StandardCharsets.UTF_8));
      List<String> statements = List.of(sql.split(";\\s*\\R"));
      int ok = 0;
      for (String statement : statements) {
        String s = statement.trim();
        if (s.isEmpty() || s.startsWith("--")) {
          continue;
        }
        try {
          jdbc.execute(s);
          ok++;
        } catch (Exception e) {
          log.warn("执行 {} 中的语句失败（忽略）: {}", location, e.getMessage());
        }
      }
      log.info("已执行 {}: {} 条语句", location, ok);
    } catch (Exception e) {
      log.error("加载脚本失败: " + location, e);
    }
  }
}
