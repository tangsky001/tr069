package com.tr069.acs.acs.session;

import com.tr069.acs.acs.service.DeviceEventRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** CPE 会话注册表 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionManager {

    /** 设备事件落台账（异常会话审计用） */
    private final DeviceEventRecorder eventRecorder;

    /** SN -> 会话 */
    private final Map<String, CpeSession> snToSession = new ConcurrentHashMap<>();

    /** TCP 连接标识 -> SN，用于双会话检测 */
    private final Map<String, String> connectionToSn = new ConcurrentHashMap<>();

    /** CWMP 会话标识 -> SN，来自 ACS 下发 Set-Cookie 的 session 值 */
    private final Map<String, String> sessionIdToSn = new ConcurrentHashMap<>();

    @Value("${acs.device.online-check-interval:86400}")
    private long onlineCheckInterval;

    /** 会话对象回收倍率：静默时长超过 onlineCheckInterval 的这个倍数才移除会话 */
    private static final long SESSION_RECLAIM_FACTOR = 10L;

    /** 疑似双会话的告警窗口(秒)：旧会话在该窗口内仍有通信，说明设备可能同时存在两条连接 */
    @Value("${acs.session.duplicate-grace-seconds:30}")
    private long duplicateGraceSeconds;

    /** 获取或创建会话 */
    public CpeSession getOrCreate(String oui, String sn, String connId) {
        CpeSession session = snToSession.computeIfAbsent(sn, key -> {
            log.info("新设备注册: sn={}, oui={}", key, oui);
            return new CpeSession(oui, key);
        });
        // 设备换了 TCP 连接（CPE 每次新建连接源端口都不同，周期性 Inform 基本都会换）：
        // 旧连接的映射必须清掉，否则 connectionToSn 会随每次上报一直堆积
        String previous = session.getConnectionId();
        if (previous != null && !previous.equals(connId)) {
            connectionToSn.remove(previous);
            if (session.hasPendingAwaiting()) {
                // 指令已下发、设备从新连接回来：CWMP 反向通道下回应很可能就走这条新连接，
                // 会话对象没变，等待中的指令继续留着，由 RPC 响应定时器兜底超时
                log.info("设备换新连接，等待中的指令继续保留: sn={}, {} -> {}", sn, previous, connId);
            } else {
                session.failAwaiting("device reconnected on a new connection");
            }
        }
        // 每轮 Inform 都是一次新的 CWMP 会话，换发新的会话号。旧号若不注销，
        // 设备带着过期 Cookie 来还能蹭进下一轮会话，本轮的 state 就串了
        String previousSessionId = session.getSessionId();
        if (previousSessionId != null) {
            sessionIdToSn.remove(previousSessionId);
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        session.setSessionId(sessionId);
        sessionIdToSn.put(sessionId, sn);

        session.setConnectionId(connId);
        session.touch();
        session.setOnline(true);
        connectionToSn.put(connId, sn);
        return session;
    }

    /** 按 CWMP 会话号反查序列号，对应 Cookie 里那个 session 字段 */
    public String findSnBySessionId(String sessionId) {
        return sessionId == null ? null : sessionIdToSn.get(sessionId);
    }

    /** 把会话重新绑到当前这条 TCP 连接上 */
    public void rebindConnection(String sn, String connId) {
        CpeSession session = snToSession.get(sn);
        if (session == null) {
            return;
        }
        String previous = session.getConnectionId();
        if (previous != null && !previous.equals(connId)) {
            connectionToSn.remove(previous);
            connectionToSn.put(connId, sn);
            session.setConnectionId(connId);
        }
        session.touch();
    }

    public CpeSession find(String sn) {
        return snToSession.get(sn);
    }

    /** 按 TCP 连接标识反查设备序列号 */
    public String findSnByConnection(String connId) {
        return connectionToSn.get(connId);
    }

    /** 双会话检测 */
    public boolean detectDuplicateSession(String sn, String connId) {
        CpeSession session = snToSession.get(sn);
        if (session == null) {
            return false;
        }
        String exists = session.getConnectionId();
        if (exists == null || exists.equals(connId)) {
            return false;
        }
        // 旧连接上还有已下发未回应的指令：这是 ACS 主动下发后设备从新连接回来
        // （反向通道、被邀请重连、NAT 换连接），属于正常的会话接管，不是双会话。
        // 判成重复会把会话关掉、设备打离线、正在等待的探测指令当场作废——
        // 表现就是适配测试做到一半设备"掉线"。
        if (session.hasPendingAwaiting()) {
            log.info("设备换新连接但仍有指令在等待回应，按会话接管处理: sn={}, {} -> {}", sn, exists, connId);
            return false;
        }
        return !session.isExpired(duplicateGraceSeconds);
    }

    /** 关闭并移除会话，同时对该 SN 上所有未完成的 RPC 进行超时处理 */
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
        sessionIdToSn.remove(session.getSessionId());
        // 这里不标记台账离线：CWMP 是短连接，光猫报完一轮就把 TCP 断了，
        // 连接结束不等于设备离线。台账是否在线只看最近上报时间，
        // 由 DeviceRegistryService 按上报窗口统一判定
        log.info("会话关闭: sn={}, reason={}", sn, reason);
    }

    /** TCP 连接断开时的清理 */
    public void onConnectionLost(String connId) {
        String sn = connectionToSn.remove(connId);
        if (sn == null) {
            return;
        }
        CpeSession session = snToSession.get(sn);
        if (session != null && connId.equals(session.getConnectionId())) {
            log.debug("连接断开: sn={}, conn={}", sn, connId);
            // 周期上报之间这条 TCP 连接本来就会断，设备常常另开一条把指令回应发回来。
            // 所以只清连接标识，等待中的指令继续保留，真等不到由 RPC 响应定时器兜底
            session.setConnectionId(null);
        }
    }

    public Collection<CpeSession> allSessions() {
        return snToSession.values();
    }

    /** 周期性清理长期未通信的会话 */
    @Scheduled(fixedDelayString = "${acs.device.session-sweep-interval:300000}")
    public void sweepExpiredSessions() {
        long now = System.currentTimeMillis();
        for (CpeSession session : snToSession.values()) {
            if (session.isExpired(onlineCheckInterval)) {
                // 会话异常审计（FreeACS SessionDataCacheCleanup 的思路）：
                // 到期回收时还有指令在等回应/下发在进行，说明这个会话是断掉的不是走完的，落一条事件
                if (session.hasPendingAwaiting() || session.isDeliveryInProgress()) {
                    String detail = session.hasPendingAwaiting()
                            ? "会话异常终止：指令 " + session.getAwaiting().getRpcName() + " 一直未等到回应"
                            : "会话异常终止：下发进行中设备失联";
                    log.warn("会话异常终止: sn={}, {}", session.getSn(), detail);
                    eventRecorder.recordEvent(session.getSn(), "session", "abnormal-terminate", detail);
                }
                session.setOnline(false);
                // 台账离线不在这里判：交给上报窗口那套规则，见 DeviceRegistryService
                // 静默足够久才回收会话对象：只标离线不回收的话 snToSession 只增不减，
                // 长期跑会越攒越多。设备下次上报会重新建会话，不影响业务
                if (session.isExpired(onlineCheckInterval * SESSION_RECLAIM_FACTOR)) {
                    closeSession(session.getSn(), "session expired");
                }
            } else if (now - session.getLastContactTime() > 180_000L) {
                log.debug("设备长时间未通信: sn={}", session.getSn());
            }
        }
    }

    public int sessionCount() {
        return snToSession.size();
    }
}
