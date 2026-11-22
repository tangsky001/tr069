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

/** 实时日志 WebSocket */
@Component
public class RealtimeLogWsHandler extends TextWebSocketHandler {

    /** 新会话补发的日志条数 */
    private static final int BACKLOG_SIZE = 500;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    /** 最近日志环形缓冲 */
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

    /** 推送一行日志并写入环形缓冲 */
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
