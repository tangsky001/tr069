package com.acsr.acs.support;

import com.acsr.acs.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动初始化：默认管理员账号。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

  private final UserService userService;

  @Override
  public void run(ApplicationArguments args) {
    try {
      userService.initDefaultAdmin();
    } catch (Exception e) {
      log.warn("初始化默认管理员失败（可能数据库未就绪）: {}", e.getMessage());
    }
  }
}
