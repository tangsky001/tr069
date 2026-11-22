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

    /** 无 Cookie 时按来源地址找最近会话 */
    public CwmpSession byRemoteAddr(String addr) {
        if (addr == null || addr.isBlank()) {
            return null;
        }
        CwmpSession best = null;
        int matches = 0;
        for (CwmpSession s : bySn.values()) {
            if (s.isEnded() || !addr.equals(s.getRemoteAddr())) {
                continue;
            }
            matches++;
            if (best == null || s.getLastActivity() > best.getLastActivity()) {
                best = s;
            }
        }
        return matches == 1 ? best : null;
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
