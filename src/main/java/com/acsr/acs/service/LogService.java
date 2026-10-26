package com.acsr.acs.service;

import com.acsr.acs.mapper.CwmpLogMapper;
import com.acsr.acs.mapper.OpAuditMapper;
import com.acsr.acs.model.entity.CwmpLog;
import com.acsr.acs.model.entity.OpAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 协议日志与审计日志（写 MySQL，异步落盘避免拖慢协议链路）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

  private final CwmpLogMapper cwmpLogMapper;
  private final OpAuditMapper opAuditMapper;

  private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "acs-log-writer");
    t.setDaemon(true);
    return t;
  });

  public void cwmp(String unitId, String sessionId, String direction, String method,
                   String eventCodes, String faultCode, String faultString,
                   String ip, Long costMs, String xml) {
    CwmpLog entry = new CwmpLog();
    entry.setUnitId(unitId);
    entry.setSessionId(sessionId);
    entry.setDirection(direction);
    entry.setMethod(method);
    entry.setEventCodes(eventCodes);
    entry.setFaultCode(faultCode);
    entry.setFaultString(faultString);
    entry.setIpAddress(ip);
    entry.setCostMs(costMs);
    entry.setXml(xml == null ? null : (xml.length() > 60000 ? xml.substring(0, 60000) : xml));
    writer.submit(() -> {
      try {
        cwmpLogMapper.insert(entry);
      } catch (Exception e) {
        log.warn("写协议日志失败: {}", e.getMessage());
      }
    });
  }

  public void audit(Long operatorId, String action, String target, String detail, String ip) {
    OpAudit entry = new OpAudit();
    entry.setOperatorId(operatorId);
    entry.setAction(action);
    entry.setTarget(target);
    entry.setDetail(detail);
    entry.setIp(ip);
    writer.submit(() -> {
      try {
        opAuditMapper.insert(entry);
      } catch (Exception e) {
        log.warn("写审计日志失败: {}", e.getMessage());
      }
    });
  }
}
