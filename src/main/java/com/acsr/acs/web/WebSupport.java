package com.acsr.acs.web;

import com.acsr.acs.service.LogService;
import com.acsr.acs.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 管理端公共能力：当前登录人、来源 IP、审计。 */
@Component
@RequiredArgsConstructor
public class WebSupport {

  private final UserService userService;
  private final LogService logService;

  public String currentUsername() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null ? null : auth.getName();
  }

  public Long currentUserId() {
    String username = currentUsername();
    if (username == null) {
      return null;
    }
    var user = userService.findByUsername(username);
    return user == null ? null : user.getId();
  }

  public static String clientIp(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String x = request.getHeader("X-Forwarded-For");
    if (x != null && !x.isBlank()) {
      return x.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  public void audit(HttpServletRequest request, String action, String target, String detail) {
    logService.audit(currentUserId(), action, target, detail, clientIp(request));
  }
}
