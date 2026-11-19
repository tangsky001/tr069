/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.960
 *
 */

package com.PilotACS.acl;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/** 设备级 ACL 拦截器：在 API 层统一拦截 /olt/{id}** 端点。 */
@Component
@RequiredArgsConstructor
public class OltAclInterceptor implements HandlerInterceptor {

    /** 设备 id 在请求体/由 service 内部处理的端点前缀，拦截器直接放行 */
    private static final Set<String> OPEN_SEGMENTS = Set.of(
            "list", "save", "delete", "options", "test", "web-accounts");

    private final DeviceAclService aclService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        LoginUser user = UserContext.get();
        if (user == null || aclService.isAdmin(user)) {
            return true;
        }
        String uri = request.getRequestURI();
        int idx = uri.indexOf("/olt/");
        if (idx < 0) {
            return true;
        }
        String sub = uri.substring(idx + 5);
        if (sub.isBlank()) {
            return true;
        }
        String first = sub.split("/")[0];
        if (OPEN_SEGMENTS.contains(first) || first.startsWith("session-pool")) {
            return true;
        }
        Long oltId;
        try {
            oltId = Long.parseLong(first);
        } catch (NumberFormatException e) {
            return true;
        }
        String action = "GET".equalsIgnoreCase(request.getMethod()) ? "view" : "operate";
        if (!aclService.allowed(user, oltId, action)) {
            reject(response, "无该设备的" + ("view".equals(action) ? "查看" : "操作") + "权限");
            return false;
        }
        return true;
    }

    private void reject(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(Result.FORBIDDEN, msg)));
    }
}
