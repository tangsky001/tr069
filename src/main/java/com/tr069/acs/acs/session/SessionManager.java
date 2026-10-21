package com.tr069.acs.acs.session;

import com.tr069.acs.acs.service.DeviceRegistryService;
import com.tr069.acs.common.log.AcsCoreLog;
import com.tr069.acs.common.log.LogCategory;
import lombok.RequiredArgsConstructor;
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
 *
 * <p>本类不直接打日志：会话相关事件统一交给 {@link AcsCoreLog} 的
 * {@link LogCategory#SESSION} 分类，避免设备频繁换连接时把日志刷爆。</p>
 */
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

    /**
     * 会话对象回收倍率：静默时长超过 onlineCheckInterval 的这个倍数才移除会话。
     */
    private static final long SESSION_RECLAIM_FACTOR = 10L;

    /**
     * 疑似双会话的告警窗口(秒)：旧会话在该窗口内仍有通信，说明设备可能同时存在两条连接。
     * 取值要小于设备周期上报间隔，否则每次周期上报都会告警。只用于告警，不据此关闭会话。
     */
    @Value("${acs.session.duplicate-grace-seconds:30}")
    private long duplicateGraceSeconds;

    private final DeviceRegistryService deviceRegistry;

    private final AcsCoreLog coreLog;

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
            coreLog.entry(LogCategory.SESSION).sn(key).bizKey("新建会话").result(true)
                    .with("oui", oui).info("设备首次建立会话");
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
                coreLog.entry(LogCategory.SESSION).sn(sn).bizKey("会话接管").result(true)
                        .with("旧连接", previous).with("新连接", connId)
                        .info("设备换新连接，等待中的指令继续保留");
            } else {
                session.failAwaiting("device reconnected on a new connection");
            }
        }
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
     * 原版 is_2session1cpe 的双会话检测。
     *
     * <p><b>注意：连接标识不同不等于重复会话。</b>连接标识取自 {@code ip:源端口}，
     * 而 CPE 每次新建 TCP 连接都会换一个临时端口，周期上报（常见 60 秒）几乎每次都是新连接。
     * 若只比连接标识，每台设备每次周期上报都会被判成重复会话，会话被关、设备被判离线，
     * 上线记录每分钟抖动一次，正在等待回应的指令也会被作废。</p>
     *
     * <p>真正的判据是"旧会话是否还活着"：旧会话在宽限窗口内仍有活动，
     * 说明设备上确实同时存在两条连接（例如设备重启后立刻重连），这才需要关掉旧的。</p>
     *
     * @param sn     设备序列号
     * @param connId 本次连接标识
     * @return true 表示检测到设备已通过另一条<strong>仍在活动</strong>的连接建立了会话
     */
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
            coreLog.entry(LogCategory.SESSION).sn(sn).bizKey("双会话检测").result(true)
                    .with("旧连接", exists).with("新连接", connId)
                    .info("旧连接仍有指令在等待回应，按会话接管处理，不判重复会话");
            return false;
        }
        return !session.isExpired(duplicateGraceSeconds);
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
        coreLog.entry(LogCategory.SESSION).sn(sn).bizKey("关闭会话").code(reason)
                .result(true).info("会话关闭");
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
            // 周期上报之间这条 TCP 连接本来就会断，设备常常另开一条把指令回应发回来。
            // 所以只清连接标识，等待中的指令继续保留，真等不到由 RPC 响应定时器兜底
            session.setConnectionId(null);
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
        for (CpeSession session : snToSession.values()) {
            if (session.isExpired(onlineCheckInterval)) {
                session.setOnline(false);
                deviceRegistry.markOffline(session.getSn());
                // 静默足够久才回收会话对象：只标离线不回收的话 snToSession 只增不减，
                // 长期跑会越攒越多。设备下次上报会重新建会话，不影响业务
                if (session.isExpired(onlineCheckInterval * SESSION_RECLAIM_FACTOR)) {
                    closeSession(session.getSn(), "session expired");
                }
            }
            // 未到离线阈值的「长时间未通信」不记日志：每台设备每轮都会命中一次，
            // 记下来只会把真正的上下线事件淹没。需要排查时看 ACS_CORE 的会话分类。
        }
    }

    public int sessionCount() {
        return snToSession.size();
    }
}
