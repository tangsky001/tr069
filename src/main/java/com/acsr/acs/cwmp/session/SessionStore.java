package com.acsr.acs.cwmp.session;

import com.acsr.acs.config.AcsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 会话仓库：按 sessionId 与 unitId 双索引，定时清理过期会话。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStore {

  private final Map<String, CpeSession> byId = new ConcurrentHashMap<>();
  private final Map<String, String> unitToSessionId = new ConcurrentHashMap<>();
  private final AcsProperties properties;

  public CpeSession getById(String id) {
    return id == null ? null : byId.get(id);
  }

  public CpeSession getByUnitId(String unitId) {
    String sid = unitToSessionId.get(unitId);
    return sid == null ? null : byId.get(sid);
  }

  public CpeSession create(String id) {
    CpeSession session = new CpeSession();
    session.setId(id);
    byId.put(id, session);
    return session;
  }

  /** 会话与设备绑定（Inform 之后调用）。 */
  public void bindUnit(String sessionId, String unitId) {
    if (sessionId == null || unitId == null) {
      return;
    }
    unitToSessionId.put(unitId, sessionId);
    CpeSession session = byId.get(sessionId);
    if (session != null) {
      byId.put(unitId, session);
    }
  }

  public void remove(String id) {
    CpeSession session = byId.remove(id);
    if (session != null && session.getUnitId() != null) {
      unitToSessionId.remove(session.getUnitId());
      byId.remove(session.getUnitId());
    }
  }

  public Collection<CpeSession> all() {
    return new ArrayList<>(byId.values());
  }

  /** 清理过期会话。 */
  public int cleanup() {
    long timeout = properties.getSessionTimeoutSeconds();
    List<String> expired = new ArrayList<>();
    byId.forEach((k, v) -> {
      if (v.isExpired(timeout)) {
        expired.add(k);
      }
    });
    expired.forEach(this::remove);
    if (!expired.isEmpty()) {
      log.info("清理过期会话 {} 个", expired.size());
    }
    return expired.size();
  }
}
