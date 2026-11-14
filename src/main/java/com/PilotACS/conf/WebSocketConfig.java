/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.639
 *
 */

package com.PilotACS.conf;

import com.PilotACS.common.ws.DashboardWsAuthInterceptor;
import com.PilotACS.common.ws.DashboardWsHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 首页概览 WebSocket 注册：路径 /ws/dashboard
 * <p>注意 /api 前缀只对 @RestController 生效，这里就是 /ws/dashboard，前端连接时带 ?token=
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final DashboardWsHandler dashboardWsHandler;
    private final DashboardWsAuthInterceptor authInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(dashboardWsHandler, "/ws/dashboard")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
