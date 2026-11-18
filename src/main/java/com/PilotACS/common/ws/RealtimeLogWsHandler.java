/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.common.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时日志 WebSocket（独立于概览 WebSocket，不共用任何逻辑）：
 * 前端打开实时日志页即建立会话，关闭页面即结束会话；
 * 后端控制台日志经 RealtimeLogAppender 汇入 publish()，逐条推给所有在线会话。
 * <p>消息格式：{type: "realtime-log", data: {text, level}}，连接建立后先补发最近历史 {type: "realtime-log-backlog", data: [...]}
 */
@Component
public class RealtimeLogWsHandler extends TextWebSocketHandler {

    /** 新会话接入时补发的最近日志条数 */
    private static final int BACKLOG_SIZE = 500;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    /** 最近日志环形缓冲，新会话接入时补发，避免页面只有打开之后的日志 */
    private final Deque<Map<String, Object>> backlog = new ArrayDeque<>(BACKLOG_SIZE);
    private final ObjectMapper objectMapper;

    public RealtimeLogWsHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        sessions.add(session);
        List<Map<String, Object>> snapshot;
        synchronized (backlog) {
            snapshot = new ArrayList<>(backlog);
        }
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                Map.of("type", "realtime-log-backlog", "data", snapshot))));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /**
     * 由 RealtimeLogAppender 调用：一行日志推给所有在线会话并写入环形缓冲。
     * 本方法与 Appender 均不落日志，避免 推送→日志→再推送 的自激循环
     */
    public void publish(String level, String text) {
        Map<String, Object> line = Map.of("text", text, "level", level);
        synchronized (backlog) {
            if (backlog.size() >= BACKLOG_SIZE) {
                backlog.pollFirst();
            }
            backlog.addLast(line);
        }
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("type", "realtime-log", "data", line));
        } catch (Exception e) {
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
}
