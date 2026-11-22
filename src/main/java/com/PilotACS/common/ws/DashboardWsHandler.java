/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.306
 *
 */

package com.PilotACS.common.ws;

import com.PilotACS.common.log.Logs;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 首页概览 WebSocket */
@Component
@RequiredArgsConstructor
public class DashboardWsHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        Logs.debug(Logs.Module.SYSTEM, "概览 WebSocket 连接建立", "用户", session.getAttributes().get("username"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /** 广播消息给所有在线会话 */
    public void broadcast(Object payload) {
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            Logs.warn(Logs.Module.SYSTEM, "概览推送消息序列化失败", "原因", e.getMessage());
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                }
            } catch (IOException e) {
                sessions.remove(session);
            }
        }
    }

    /** 消息包装：type + data */
    public static Map<String, Object> envelope(String type, Object data) {
        return Map.of("type", type, "data", data);
    }
}
