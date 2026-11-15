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
 * 活跃会话注册表：同设备互斥（对应 genieacs 的 cwmp_session_<deviceId> 锁）
 */
@Component
public class SessionRegistry {

    private final Map<String, CwmpSession> bySn = new ConcurrentHashMap<>();
    private final Map<String, CwmpSession> bySessionId = new ConcurrentHashMap<>();

    public synchronized CwmpSession create(String sn) {
        // 同设备旧会话先作废（设备重新 Inform 场景）
        CwmpSession old = bySn.get(sn);
        if (old != null) {
            old.setEnded(true);
            bySessionId.remove(old.getSessionId());
        }
        CwmpSession s = new CwmpSession(UUID.randomUUID().toString().replace("-", ""), sn);
        bySn.put(sn, s);
        bySessionId.put(s.getSessionId(), s);
        return s;
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
