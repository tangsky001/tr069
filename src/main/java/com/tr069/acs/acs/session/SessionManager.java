package com.tr069.acs.acs.session;

import com.tr069.acs.acs.service.DeviceRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CPE 会话注册表。
 *
 * <p>对应 Python 版 cpe.py 的 {@code m_dict_sn2cpe} 与 {@code m_dict_sessionno2cpe}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionManager {

    /**
     * SN -> 会话
     */
    private final Map<String, CpeSession> snToSession = new ConcurrentHashMap<>();

    /**
     * TCP 连接标识 -> SN，用于双会话检测
     */
    private final Map<String, String> connectionToSn = new ConcurrentHashMap<>();

    @Value("${acs.device.online-check-interval:86400}")
    private long onlineCheckInterval;

    private final DeviceRegistryService deviceRegistry;

    /**
     * 获取或创建会话。
     *
     * @param oui    设备 OUI
     * @param sn     设备序列号
     * @param connId 本次 TCP 连接标识
     * @return 会话实例
     */
    public CpeSession getOrCreate(String oui, String sn, String connId) {
        CpeSession session = snToSession.computeIfAbsent(sn, key -> {
            log.info("新设备注册: sn={}, oui={}", key, oui);
            return new CpeSession(oui, key);
        });
        session.setConnectionId(connId);
        session.touch();
        session.setOnline(true);
        connectionToSn.put(connId, sn);
        return session;
    }

    public CpeSession find(String sn) {
        return snToSession.get(sn);
    }

    /**
     * 按 TCP 连接标识反查设备序列号，对应原版 sessionno -> CPE 的映射。
     */
    public String findSnByConnection(String connId) {
        return connectionToSn.get(connId);
    }

    /**
     * 原版 is_2session1cpe 的双会话检测：同一设备出现新连接时判定为重复会话。
     *
     * @param sn     设备序列号
     * @param connId 本次连接标识
     * @return true 表示检测到设备已通过其它连接建立了会话
     */
    public boolean detectDuplicateSession(String sn, String connId) {
        CpeSession session = snToSession.get(sn);
        if (session == null) {
            return false;
        }
        String exists = session.getConnectionId();
        return exists != null && !exists.equals(connId);
    }

    /**
     * 关闭并移除会话，同时对该 SN 上所有未完成的 RPC 进行超时处理。
     */
    public void closeSession(String sn, String reason) {
        CpeSession session = snToSession.remove(sn);
        if (session == null) {
            return;
        }
        session.cancelTimers();
        session.setState(SessionState.CLOSED);
        session.setOnline(false);
        session.failAwaiting(reason);
        // 清空待下发队列，避免遗留到下一次会话
        session.getOutbox().clear();
        connectionToSn.remove(session.getConnectionId());
        deviceRegistry.markOffline(sn);
        log.info("会话关闭: sn={}, reason={}", sn, reason);
    }

    /**
     * TCP 连接断开时的清理，对应原版 connectionLost。
     */
    public void onConnectionLost(String connId) {
        String sn = connectionToSn.remove(connId);
        if (sn == null) {
            return;
        }
        CpeSession session = snToSession.get(sn);
        if (session != null && connId.equals(session.getConnectionId())) {
            log.debug("连接断开: sn={}, conn={}", sn, connId);
            session.failAwaiting("connection lost");
        }
    }

    public Collection<CpeSession> allSessions() {
        return snToSession.values();
    }

    /**
     * 周期性清理长期未通信的会话，对应原版设备离线判定。
     */
    @Scheduled(fixedDelayString = "${acs.device.session-sweep-interval:300000}")
    public void sweepExpiredSessions() {
        long now = System.currentTimeMillis();
        for (CpeSession session : snToSession.values()) {
            if (session.isExpired(onlineCheckInterval)) {
                session.setOnline(false);
                deviceRegistry.markOffline(session.getSn());
            } else if (now - session.getLastContactTime() > 180_000L) {
                log.debug("设备长时间未通信: sn={}", session.getSn());
            }
        }
    }

    public int sessionCount() {
        return snToSession.size();
    }
}
