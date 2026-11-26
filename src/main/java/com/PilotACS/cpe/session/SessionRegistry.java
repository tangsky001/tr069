/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.session;

import com.PilotACS.cpe.model.TaskRun;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 活跃会话注册表：同设备互斥 + per-SN 处理锁。 */
@Component
public class SessionRegistry {

    private final Map<String, CwmpSession> bySn = new ConcurrentHashMap<>();
    private final Map<String, CwmpSession> bySessionId = new ConcurrentHashMap<>();
    private static final int LOCK_STRIPES = 1024;
    private static final int LOCK_STRIPE_MASK = LOCK_STRIPES - 1;
    private final ReentrantLock[] lockStripes = java.util.stream.IntStream.range(0, LOCK_STRIPES)
            .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    /** 新会话 + 被顶替的旧会话 */
    public record Created(CwmpSession session, CwmpSession superseded) {
    }

    public synchronized Created create(String sn) {
        CwmpSession old = bySn.get(sn);
        CwmpSession superseded = null;
        if (old != null && !old.isEnded()) {
            bySessionId.remove(old.getSessionId());
            superseded = old;
        }
        CwmpSession s = new CwmpSession(UUID.randomUUID().toString().replace("-", ""), sn);
        bySn.put(sn, s);
        bySessionId.put(s.getSessionId(), s);
        return new Created(s, superseded);
    }

    public CwmpSession bySessionId(String sessionId) {
        return sessionId == null ? null : bySessionId.get(sessionId);
    }

    public CwmpSession bySn(String sn) {
        return sn == null ? null : bySn.get(sn);
    }

    /** 该会话是否仍是现役会话 */
    public boolean isCurrent(CwmpSession s) {
        return s != null && !s.isEnded() && bySn.get(s.getSn()) == s;
    }

    /**
     * 无 Cookie 时按来源地址找最近活跃会话。
     * 多台设备共用出口 IP（NAT/CGNAT）时返回最近活跃的那台而不是 null：
     * 返回 null 会让设备收 204/400 只能重新 Inform，形成会话重建循环；
     * 绑错的报文会被 SN 校验兜住（handleKnown 按 SN 重建）。
     */
    public CwmpSession byRemoteAddr(String addr) {
        if (addr == null || addr.isBlank()) {
            return null;
        }
        CwmpSession best = null;
        for (CwmpSession s : bySn.values()) {
            if (s.isEnded() || !addr.equals(s.getRemoteAddr())) {
                continue;
            }
            if (best == null || s.getLastActivity() > best.getLastActivity()) {
                best = s;
            }
        }
        return best;
    }

    public void remove(CwmpSession s) {
        bySn.remove(s.getSn(), s);
        bySessionId.remove(s.getSessionId(), s);
    }

    public Map<String, CwmpSession> allBySn() {
        return bySn;
    }

    /** per-SN 处理锁 */
    public ReentrantLock lockFor(String sn) {
        String key = sn == null ? "?" : sn;
        int h = key.hashCode();
        h ^= (h >>> 16);
        return lockStripes[h & LOCK_STRIPE_MASK];
    }

    /** 活会话执行中的任务 id 集合 */
    public Set<Long> runningTaskIds() {
        Set<Long> out = new HashSet<>();
        for (CwmpSession s : bySn.values()) {
            TaskRun cur = s.getCurrent();
            if (cur != null && !cur.isCompleted() && cur.getTaskId() > 0) {
                out.add(cur.getTaskId());
            }
        }
        return out;
    }

    /** sn → 最近一次设备实际上报 Inform 的时刻（毫秒），用于校验 CR 是否真的唤醒了设备 */
    private final Map<String, Long> lastInformAt = new ConcurrentHashMap<>();

    public void markInform(String sn) {
        if (sn != null && !sn.isBlank()) {
            lastInformAt.put(sn, System.currentTimeMillis());
        }
    }

    public long lastInformAt(String sn) {
        Long t = sn == null ? null : lastInformAt.get(sn);
        return t == null ? 0L : t;
    }

    /** sn → 唤醒标记过期时刻（毫秒） */
    private final Map<String, Long> pendingKicks = new ConcurrentHashMap<>();

    /** CR 踢成功后挂标记 */
    public void markKickPending(String sn, long ttlMs) {
        if (sn != null && !sn.isBlank()) {
            pendingKicks.put(sn, System.currentTimeMillis() + ttlMs);
        }
    }

    /** Inform 到达时取走标记 */
    public boolean consumeKick(String sn) {
        if (sn == null) {
            return false;
        }
        Long deadline = pendingKicks.remove(sn);
        return deadline != null && deadline >= System.currentTimeMillis();
    }

    /** 清理过期唤醒标记 */
    public void sweepExpiredKicks() {
        long now = System.currentTimeMillis();
        pendingKicks.entrySet().removeIf(e -> e.getValue() < now);
    }
}
