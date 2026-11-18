/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.639
 *
 */

package com.PilotACS.conf;

import com.PilotACS.common.ws.DashboardWsAuthInterceptor;
import com.PilotACS.common.ws.DashboardWsHandler;
import com.PilotACS.common.ws.RealtimeLogWsAuthInterceptor;
import com.PilotACS.common.ws.RealtimeLogWsHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册：概览 /ws/dashboard、实时日志 /ws/realtime-log
 * <p>注意 /api 前缀只对 @RestController 生效，这里就是 /ws/xxx，前端连接时带 ?token=
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final DashboardWsHandler dashboardWsHandler;
    private final DashboardWsAuthInterceptor authInterceptor;
    private final RealtimeLogWsHandler realtimeLogWsHandler;
    private final RealtimeLogWsAuthInterceptor realtimeLogAuthInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(dashboardWsHandler, "/ws/dashboard")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns("*");
        // 实时日志：独立 Handler + 独立握手鉴权，与概览不共用逻辑
        registry.addHandler(realtimeLogWsHandler, "/ws/realtime-log")
                .addInterceptors(realtimeLogAuthInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
