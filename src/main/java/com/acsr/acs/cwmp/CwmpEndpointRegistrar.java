package com.acsr.acs.cwmp;

import com.acsr.acs.config.AcsProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;

/** 把 application.yml 里配置的其它 CPE 上报路径注册到同一个协议处理器。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CwmpEndpointRegistrar implements InitializingBean {

  private final RequestMappingHandlerMapping handlerMapping;
  private final CwmpController controller;
  private final AcsProperties properties;

  @Override
  public void afterPropertiesSet() {
    Method method = ReflectionUtils.findMethod(CwmpController.class, "handle", HttpServletRequest.class);
    if (method == null) {
      return;
    }
    for (String path : properties.getCwmpPaths()) {
      if (path == null || path.isBlank() || "/cwmp".equals(path)) {
        continue;
      }
      try {
        RequestMappingInfo info = RequestMappingInfo
            .paths(path)
            .methods(RequestMethod.POST)
            .build();
        handlerMapping.registerMapping(info, controller, method);
        log.info("已注册 CPE 上报路径: POST {}", path);
      } catch (Exception e) {
        log.warn("注册 CPE 上报路径 {} 失败: {}", path, e.getMessage());
      }
    }
  }
}
