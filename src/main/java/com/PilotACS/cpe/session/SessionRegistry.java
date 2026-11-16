/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.session;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃会话注册表：同设备互斥
 */
@Component
public class SessionRegistry {

    private final Map<String, CwmpSession> bySn = new ConcurrentHashMap<>();
    private final Map<String, CwmpSession> bySessionId = new ConcurrentHashMap<>();
    /** create() 顶替掉的旧会话，供调用方做收尾（在飞任务回队等） */
    private volatile CwmpSession lastSuperseded;

    public synchronized CwmpSession create(String sn) {
        // 同设备旧会话先摘除（设备重新 Inform 场景）。
        // 注意：不能在这里 setEnded —— endSession 靠 isEnded 幂等，提前置位会跳过收尾；
        // 旧会话由调用方 endSession(lastSuperseded) 完整收尾（任务回队/参数落库/快照刷新）
        CwmpSession old = bySn.get(sn);
        lastSuperseded = null;
        if (old != null) {
            bySessionId.remove(old.getSessionId());
            lastSuperseded = old;
        }
        CwmpSession s = new CwmpSession(UUID.randomUUID().toString().replace("-", ""), sn);
        bySn.put(sn, s);
        bySessionId.put(s.getSessionId(), s);
        return s;
    }

    /** 刚被 create() 顶替的旧会话；无则 null */
    public CwmpSession lastSuperseded() {
        return lastSuperseded;
    }

    public CwmpSession bySessionId(String sessionId) {
        return sessionId == null ? null : bySessionId.get(sessionId);
    }

    public CwmpSession bySn(String sn) {
        return sn == null ? null : bySn.get(sn);
    }

    public void remove(CwmpSession s) {
        bySn.remove(s.getSn(), s);
        bySessionId.remove(s.getSessionId(), s);
    }

    public Map<String, CwmpSession> allBySn() {
        return bySn;
    }
}
