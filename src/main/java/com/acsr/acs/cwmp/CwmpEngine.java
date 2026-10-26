package com.acsr.acs.cwmp;

import com.acsr.acs.adapt.AdaptService;
import com.acsr.acs.cwmp.session.CpeSession;
import com.acsr.acs.cwmp.session.SessionStore;
import com.acsr.acs.cwmp.xml.CwmpMethod;
import com.acsr.acs.cwmp.xml.ParameterInfoStruct;
import com.acsr.acs.cwmp.xml.ParameterValueStruct;
import com.acsr.acs.cwmp.xml.SoapParser;
import com.acsr.acs.cwmp.xml.SoapWriter;
import com.acsr.acs.mapper.FileStoreMapper;
import com.acsr.acs.model.entity.CpeDevice;
import com.acsr.acs.model.entity.DispatchTask;
import com.acsr.acs.model.entity.FileStore;
import com.acsr.acs.service.DispatchService;
import com.acsr.acs.service.DeviceService;
import com.acsr.acs.service.LogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CWMP 会话引擎：接收 CPE 的一轮 HTTP 请求，处理、决策、生成响应。
 *
 * <p>与 FreeACS 一致：ACS 不主动建连，所有 RPC 都夹带在 CPE 下一次 HTTP POST 的响应里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CwmpEngine {

  private static final String PARAM_RESET = "System.X_ACSR-COM.Reset";
  private static final String PARAM_RESTART = "System.X_ACSR-COM.Restart";

  private static final List<String> SUPPORTED_METHODS = List.of(
      "Inform", "GetRPCMethods", "TransferComplete", "AutonomousTransferComplete",
      "GetParameterNames", "GetParameterValues", "SetParameterValues",
      "GetParameterAttributes", "SetParameterAttributes",
      "AddObject", "DeleteObject", "Download", "Upload", "Reboot", "FactoryReset", "ScheduleInform");

  private final SessionStore sessionStore;
  private final DeviceService deviceService;
  private final ProvisioningService provisioning;
  private final AdaptService adaptService;
  private final DispatchService dispatchService;
  private final LogService logService;
  private final FileStoreMapper fileStoreMapper;
  private final com.acsr.acs.config.AcsProperties properties;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 一轮交互的结果。empty=true 表示会话结束，返回 HTTP 204。 */
  public record CwmpResult(String xml, boolean empty) {

    public static CwmpResult end() {
      return new CwmpResult(null, true);
    }
  }

  // ============================ 主流程 ============================

  public CwmpResult handle(byte[] body, String remoteIp, String sessionKey) {
    long start = System.currentTimeMillis();
    SoapParser in;
    try {
      in = SoapParser.parse(body);
    } catch (Exception e) {
      log.warn("解析 CPE 报文失败: {}", e.getMessage());
      return CwmpResult.end();
    }
    CwmpMethod inMethod = in.getMethod();
    String unitId = inMethod == CwmpMethod.Inform
        ? DeviceService.buildUnitId(in.getDeviceIdStruct()) : null;

    CpeSession session = resolveSession(sessionKey, unitId);
    session.touch();
    session.setRemoteIp(remoteIp);

    logService.cwmp(session.getUnitId(), session.getId(), "IN", inMethod.name(),
        session.getEventCodes(), in.getFault() == null ? null : in.getFault().getFaultCode(),
        in.getFault() == null ? null : in.getFault().getFaultString(), remoteIp, null, in.getRawXml());
    log.debug("收到 CPE[{}] 方法 {} (response={})", session.getUnitId(), inMethod, in.isIncomingResponse());

    try {
      processRequest(session, in, remoteIp);
      CpeSession.PendingRpc rpc = plan(session, in);
      String xml = build(session, rpc, in);
      CwmpMethod out = rpc == null ? CwmpMethod.Empty : rpc.getMethod();
      session.setMethodBeforePrevious(session.getPreviousMethod());
      session.setPreviousMethod(out);
      session.setCurrentRpc(rpc);

      // Download / Upload 属于异步任务，等 CPE 的 TransferComplete 再归集终态
      if (rpc != null && rpc.getTaskId() != null
          && (out == CwmpMethod.Download || out == CwmpMethod.Upload)) {
        DispatchTask t = dispatchService.get(rpc.getTaskId());
        if (t != null) {
          dispatchService.markWaitResult(t);
        }
      }

      long cost = System.currentTimeMillis() - start;
      logService.cwmp(session.getUnitId(), session.getId(), "OUT", out.name(), null, null, null,
          remoteIp, cost, xml);

      if (rpc == null || out == CwmpMethod.Empty) {
        endSession(session);
        return CwmpResult.end();
      }
      return new CwmpResult(xml, false);
    } catch (Exception e) {
      log.error("处理 CWMP 会话异常", e);
      endSession(session);
      return CwmpResult.end();
    }
  }

  private CpeSession resolveSession(String key, String unitId) {
    CpeSession session = sessionStore.getById(key);
    if (session == null && unitId != null) {
      // CPE 换了 TCP 连接/未带 cookie：按 unitId 恢复会话
      session = sessionStore.getByUnitId(unitId);
      if (session != null) {
        sessionStore.bindUnit(key, unitId);
      }
    }
    if (session == null) {
      session = sessionStore.create(key);
      if (unitId != null) {
        sessionStore.bindUnit(key, unitId);
      }
    }
    return session;
  }

  // ============================ 请求处理 ============================

  private void processRequest(CpeSession s, SoapParser in, String ip) {
    if (in.isIncomingResponse()) {
      completeCurrentRpc(s, in);
    }
    CwmpMethod m = in.getMethod();
    switch (m) {
      case Inform -> onInform(s, in, ip);
      case GetParameterNames -> {
        if (in.isIncomingResponse()) {
          onGpnResponse(s, in);
        }
      }
      case GetParameterValues -> {
        if (in.isIncomingResponse()) {
          onGpvResponse(s, in);
        }
      }
      case TransferComplete -> onTransferComplete(s, in);
      case AutonomousTransferComplete -> log.info("设备 {} 上报 AutonomousTransferComplete", s.getUnitId());
      default -> {
      }
    }
  }

  /** Inform：识别设备、登记台账、匹配适配库。 */
  private void onInform(CpeSession s, SoapParser in, String ip) {
    var deviceId = in.getDeviceIdStruct();
    String unitId = DeviceService.buildUnitId(deviceId);
    s.setUnitId(unitId);
    s.setSerialNumber(deviceId.getSerialNumber());
    s.setOui(deviceId.getOui());
    s.setProductClass(deviceId.getProductClass());
    s.setManufacturer(deviceId.getManufacturer());
    s.setModel(deviceId.getProductClass());
    s.setCwmpVersion(in.getCwmpVersion());

    // 事件
    for (var ev : in.getEventList()) {
      String code = ev.getEventCode() == null ? "" : ev.getEventCode();
      s.setBooted(s.isBooted() || code.startsWith("1"));
      s.setPeriodic(s.isPeriodic() || code.startsWith("2"));
      s.setValueChange(s.isValueChange() || code.startsWith("4"));
      s.setKicked(s.isKicked() || code.startsWith("6"));
      s.setTransferComplete(s.isTransferComplete() || code.startsWith("7"));
      s.setDiagnosticsComplete(s.isDiagnosticsComplete() || code.startsWith("8"));
      s.setAutonomousTransferComplete(s.isAutonomousTransferComplete() || code.startsWith("10"));
      s.setFactoryReset(s.isFactoryReset() || code.startsWith("0"));
      if (ev.getCommandKey() != null && !ev.getCommandKey().isBlank()) {
        s.setEventCommandKey(ev.getCommandKey());
      }
    }
    StringBuilder codes = new StringBuilder();
    in.getEventList().forEach(e -> {
      if (codes.length() > 0) {
        codes.append(",");
      }
      codes.append(e.getEventCode());
    });
    s.setEventCodes(codes.toString());

    // 参数列表 -> keyRoot + 内置参数
    List<ParameterValueStruct> values = in.getParameterList().getParameterValueList();
    s.setKeyRoot(ProvisioningService.detectKeyRoot(values));
    String connUrlName = null;
    String connUrlValue = null;
    for (ParameterValueStruct pvs : values) {
      if (pvs.getName() == null) {
        continue;
      }
      if (pvs.getName().endsWith("DeviceInfo.SoftwareVersion")) {
        s.setSoftwareVersion(pvs.getValue());
      } else if (pvs.getName().endsWith("ManagementServer.PeriodicInformInterval")) {
        s.getCpeParams().put("PII", pvs.getValue());
      } else if (pvs.getName().endsWith("ManagementServer.ConnectionRequestURL")) {
        s.getCpeParams().put("ConnectionRequestURL", pvs.getValue());
        connUrlName = pvs.getName();
        connUrlValue = pvs.getValue();
      } else if (pvs.getName().contains("ParameterKey")) {
        s.setParameterKeyFromCpe(pvs.getValue());
      }
    }

    // 台账登记 / 更新
    CpeDevice device = deviceService.register(deviceId, unitId, ip);
    s.setDevice(device);
    deviceService.touchOnline(unitId, ip);
    // 记录反向连接地址，供管理端唤醒设备
    if (connUrlName != null && connUrlValue != null && !connUrlValue.isBlank()) {
      deviceService.setParam(unitId, connUrlName, connUrlValue, "R");
    }

    // 适配库匹配（SQLite）
    s.setFirmwareVersion(s.getSoftwareVersion());
    if (device != null && device.getFirmwareVersion() == null && s.getSoftwareVersion() != null) {
      device.setFirmwareVersion(s.getSoftwareVersion());
      deviceService.updateDevice(device);
    }
    var tpl = adaptService.match(s.getModel(), s.getSoftwareVersion());
    if (tpl.isPresent()) {
      s.setTemplateId(tpl.get().getTemplateId());
      s.setAdapted(true);
      s.setNeedDiscovery(false);
      s.setCapability(provisioning.capabilityFlags(tpl.get().getTemplateId()));
      adaptService.recordHit(s.getSerialNumber(), tpl.get().getTemplateId());
      log.info("设备 {} 命中适配模板 {} (model={}, fw={})",
          unitId, tpl.get().getTemplateId(), s.getModel(), s.getSoftwareVersion());
    } else {
      s.setAdapted(false);
      s.setNeedDiscovery(properties.isDiscoveryMode());
      log.info("设备 {} 未适配 (model={}, fw={})，discoveryMode={}",
          unitId, s.getModel(), s.getSoftwareVersion(), properties.isDiscoveryMode());
    }

    // ACS 目标参数
    s.setFromDB(provisioning.loadDesiredParams(s));

    // 下载类任务在 7 TRANSFER COMPLETE 时归集
    if (s.isTransferComplete() && s.getUnitId() != null) {
      DispatchTask waiting = firstWaitingTask(s.getUnitId());
      if (waiting != null) {
        dispatchService.finish(waiting, DispatchService.SUCCESS, "TransferComplete", null);
      }
    }
  }

  /** GetParameterNamesResponse：能力采集入库（SQLite）。 */
  private void onGpnResponse(CpeSession s, SoapParser in) {
    List<ParameterInfoStruct> infos = in.getParameterList().getParameterInfoList();
    if (infos == null || infos.isEmpty()) {
      return;
    }
    Long templateId = adaptService.collect(s.getManufacturer(), s.getModel(), s.getSoftwareVersion(),
        infos, "Inform 后自动采集");
    s.setTemplateId(templateId);
    s.setAdapted(true);
    s.setNeedDiscovery(false);
    s.setCapability(provisioning.capabilityFlags(templateId));
    if (s.getDevice() != null) {
      deviceService.syncUnitTypeParams(s.getDevice().getUnitTypeId(), s.getCapability());
    }
    s.setFromDB(provisioning.loadDesiredParams(s));
  }

  /** GetParameterValuesResponse：比较差异，回写只读参数。 */
  private void onGpvResponse(CpeSession s, SoapParser in) {
    s.setValuesFromCPE(in.getParameterList().getParameterValueList());
    String root = s.getKeyRoot();
    for (ParameterValueStruct pvs : s.getValuesFromCPE()) {
      if (pvs.getName() == null || root == null) {
        continue;
      }
      if (pvs.getName().equals(root + ProvisioningService.SUFFIX_SOFTWARE_VERSION)) {
        s.setSoftwareVersion(pvs.getValue());
      } else if (pvs.getName().equals(root + ProvisioningService.SUFFIX_PII)) {
        s.getCpeParams().put("PII", pvs.getValue());
      } else if (pvs.getName().equals(root + ProvisioningService.SUFFIX_CONN_URL)) {
        s.getCpeParams().put("ConnectionRequestURL", pvs.getValue());
      }
    }
    provisioning.diff(s);
    provisioning.flush(s);
    if (s.getDevice() != null && s.getSoftwareVersion() != null
        && !Objects.equals(s.getDevice().getFirmwareVersion(), s.getSoftwareVersion())) {
      s.getDevice().setFirmwareVersion(s.getSoftwareVersion());
      deviceService.updateDevice(s.getDevice());
    }
  }

  private void onTransferComplete(CpeSession s, SoapParser in) {
    String commandKey = in.getCommandKey();
    String status = in.getStatus();
    DispatchTask task = firstWaitingTask(s.getUnitId());
    if (task == null) {
      return;
    }
    boolean ok = status == null || "0".equals(status) || "1".equals(status);
    String detail = "commandKey=" + commandKey + ", status=" + status;
    dispatchService.finish(task, ok ? DispatchService.SUCCESS : DispatchService.FAILED, detail,
        ok ? null : "CPE 返回 TransferComplete status=" + status);
  }

  /** 归集上一轮 RPC 的结果（成功/失败 + 返回值）。 */
  private void completeCurrentRpc(CpeSession s, SoapParser in) {
    CpeSession.PendingRpc rpc = s.getCurrentRpc();
    if (rpc == null) {
      return;
    }
    s.setCurrentRpc(null);
    Long taskId = rpc.getTaskId();
    boolean fault = in.getFault() != null;
    String result = null;
    if (!fault) {
      switch (rpc.getMethod()) {
        case GetParameterValues, GetParameterAttributes -> result = toJson(in.getParameterList().getParameterValueList());
        case GetParameterNames -> result = toJson(in.getParameterList().getParameterInfoList());
        case AddObject, DeleteObject -> result = "{\"instanceNumber\":\"" + in.getInstanceNumber() + "\"}";
        case SetParameterValues -> result = "{\"status\":0}";
        default -> result = "{\"status\":0}";
      }
    }
    if (taskId == null) {
      return;
    }
    DispatchTask task = dispatchService.get(taskId);
    if (task == null) {
      return;
    }
    if (fault) {
      dispatchService.finish(task, DispatchService.FAILED, null,
          "CPE Fault " + in.getFault().getFaultCode() + " " + in.getFault().getFaultString());
    } else {
      // 写入成功：把下发的值固化到台账
      if (rpc.getMethod() == CwmpMethod.SetParameterValues && !rpc.getParams().isEmpty()) {
        deviceService.writeParams(s.getUnitId(), rpc.getParams(), "RW");
      }
      dispatchService.finish(task, DispatchService.SUCCESS, result, null);
    }
  }

  // ============================ 决策 ============================

  private CpeSession.PendingRpc plan(CpeSession s, SoapParser in) {
    CwmpMethod m = in.getMethod();
    CwmpMethod prev = s.getPreviousMethod();

    // 空包：CPE 没有更多请求，由 ACS 根据上一轮的方法决定下一步
    if (m == CwmpMethod.Empty) {
      boolean afterInform = prev == CwmpMethod.Inform
          || prev == CwmpMethod.TransferComplete
          || prev == CwmpMethod.AutonomousTransferComplete
          || prev == CwmpMethod.GetRPCMethods;
      return afterInform ? afterInform(s) : null;
    }
    // CPE 主动请求：回对应的 Response
    if (!in.isIncomingResponse()) {
      return switch (m) {
        case Inform, TransferComplete, AutonomousTransferComplete, GetRPCMethods ->
            new CpeSession.PendingRpc(m);
        default -> null;
      };
    }
    // CPE 对 ACS 上一轮 RPC 的响应
    return switch (m) {
      case GetParameterNames -> new CpeSession.PendingRpc(CwmpMethod.GetParameterValues);
      case GetParameterValues -> afterGpv(s);
      default -> s.hasRpc() ? s.pollRpc() : null;
    };
  }

  private CpeSession.PendingRpc afterInform(CpeSession s) {
    if (s.isNeedDiscovery()) {
      return new CpeSession.PendingRpc(CwmpMethod.GetParameterNames);
    }
    if (s.getDevice() == null) {
      return null;
    }
    return new CpeSession.PendingRpc(CwmpMethod.GetParameterValues);
  }

  /** GPV 响应之后：RESET/RESTART -> 任务 -> 常规差异下发。 */
  private CpeSession.PendingRpc afterGpv(CpeSession s) {
    if (s.getUnitId() != null) {
      if (consumeFlag(s, PARAM_RESET)) {
        return new CpeSession.PendingRpc(CwmpMethod.FactoryReset);
      }
      if (consumeFlag(s, PARAM_RESTART)) {
        return new CpeSession.PendingRpc(CwmpMethod.Reboot);
      }
      // 队列任务（一次会话最多取 5 个，串行执行）
      DispatchTask task = dispatchService.next(s.getUnitId());
      int n = 0;
      while (task != null && n < 5) {
        CpeSession.PendingRpc rpc = buildTaskRpc(s, task);
        if (rpc != null) {
          s.pushRpc(rpc);
          dispatchService.markSending(task);
        }
        task = dispatchService.next(s.getUnitId());
        n++;
        if (task != null && task.getId().equals(rpc == null ? null : rpc.getTaskId())) {
          break; // 防止异常情况下死循环
        }
      }
    }
    if (!s.getToCPE().isEmpty()) {
      CpeSession.PendingRpc spv = new CpeSession.PendingRpc(CwmpMethod.SetParameterValues);
      spv.getParams().addAll(s.getToCPE());
      s.pushRpc(spv);
    }
    return s.hasRpc() ? s.pollRpc() : null;
  }

  /** 判断并清除一次性系统标记（System.X_ACSR-COM.Restart / Reset）。 */
  private boolean consumeFlag(CpeSession s, String name) {
    ParameterValueStruct pvs = s.getFromDB().get(name);
    if (pvs != null && "1".equals(pvs.getValue())) {
      deviceService.setParam(s.getUnitId(), name, "0", "X");
      return true;
    }
    return false;
  }

  /** 把下发任务转换成 CWMP RPC。 */
  private CpeSession.PendingRpc buildTaskRpc(CpeSession s, DispatchTask task) {
    String rpcName = task.getRpcName();
    CwmpMethod method = CwmpMethod.fromString(rpcName);
    if (method == CwmpMethod.Empty) {
      dispatchService.finish(task, DispatchService.FAILED, null, "不支持的 RPC: " + rpcName);
      return null;
    }
    CpeSession.PendingRpc rpc = new CpeSession.PendingRpc(method);
    rpc.setTaskId(task.getId());
    JsonNode payload = parsePayload(task.getParamPayload());
    switch (method) {
      case GetParameterValues, GetParameterAttributes, GetParameterNames -> {
        List<String> names = readNames(payload);
        if (names.isEmpty() && s.getKeyRoot() != null) {
          names.add(s.getKeyRoot());
        }
        rpc.setNames(names);
        if (method == CwmpMethod.GetParameterNames) {
          rpc.setObjectName(names.isEmpty() ? s.getKeyRoot() : names.get(0));
        }
      }
      case SetParameterValues -> rpc.getParams().addAll(readParams(payload));
      case AddObject, DeleteObject -> {
        String objectName = payload == null ? null : payload.path("objectName").asText(null);
        if (objectName == null && payload != null && payload.isTextual()) {
          objectName = payload.asText();
        }
        rpc.setObjectName(objectName == null ? "" : objectName);
      }
      case Download, Upload -> {
        Long fileId = payload == null ? null : payload.path("fileId").asLong(0L);
        FileStore file = fileId == null || fileId == 0 ? null : fileStoreMapper.selectById(fileId);
        if (file == null) {
          dispatchService.finish(task, DispatchService.FAILED, null, "文件不存在: fileId=" + fileId);
          return null;
        }
        rpc.setUrl(properties.getPublicUrl() + "/api/files/" + file.getId() + "/download");
        rpc.setFileType("SOFTWARE".equalsIgnoreCase(file.getType())
            ? "1 Firmware Upgrade Image" : "3 Vendor Configuration File");
        rpc.setCommandKey(file.getVersion() == null ? file.getName() : file.getVersion());
        rpc.setFileSize(file.getLength() == null ? 0 : file.getLength());
        rpc.setTargetFileName(file.getTargetName());
      }
      case ScheduleInform -> {
        rpc.setCommandKey(payload == null ? "" : payload.path("commandKey").asText(""));
      }
      default -> {
      }
    }
    return rpc;
  }

  // ============================ 响应构造 ============================

  private String build(CpeSession s, CpeSession.PendingRpc rpc, SoapParser in) {
    if (rpc == null) {
      return null;
    }
    CwmpMethod out = rpc.getMethod();
    boolean responseForm = !in.isIncomingResponse() && out == in.getMethod();
    String body;
    switch (out) {
      case Inform -> body = SoapWriter.informResponse();
      case TransferComplete -> body = SoapWriter.transferCompleteResponse();
      case AutonomousTransferComplete -> body = SoapWriter.autonomousTransferCompleteResponse();
      case GetRPCMethods -> body = SoapWriter.getRpcMethodsResponse(SUPPORTED_METHODS);
      case GetParameterNames -> {
        String path = rpc.getObjectName() == null || rpc.getObjectName().isBlank()
            ? (s.getKeyRoot() == null ? "InternetGatewayDevice." : s.getKeyRoot()) : rpc.getObjectName();
        body = SoapWriter.getParameterNames(path, false);
      }
      case GetParameterValues -> {
        List<String> names = new ArrayList<>();
        if (rpc.getTaskId() != null && !rpc.getNames().isEmpty()) {
          names.addAll(rpc.getNames());
        } else {
          provisioning.addCpeParams(s);
          names.addAll(s.getFromDB().keySet());
        }
        List<ParameterValueStruct> requested = new ArrayList<>();
        for (String n : names) {
          requested.add(new ParameterValueStruct(n, "", "xsd:string"));
        }
        s.setRequestedCPE(requested);
        body = SoapWriter.getParameterValues(names);
      }
      case SetParameterValues -> {
        List<ParameterValueStruct> params = rpc.getParams().isEmpty() ? s.getToCPE() : rpc.getParams();
        body = SoapWriter.setParameterValues(params, parameterKey(params));
      }
      case AddObject -> body = SoapWriter.addObject(rpc.getObjectName(), parameterKey(rpc.getParams()));
      case DeleteObject -> body = SoapWriter.deleteObject(rpc.getObjectName(), parameterKey(rpc.getParams()));
      case Reboot -> body = SoapWriter.reboot(rpc.getCommandKey() == null ? "ACS-Reboot" : rpc.getCommandKey());
      case FactoryReset -> body = SoapWriter.factoryReset();
      case Download -> body = SoapWriter.download(
          rpc.getCommandKey(), rpc.getFileType(), rpc.getUrl(),
          properties.isFileAuthUsed() ? s.getUnitId() : "",
          properties.isFileAuthUsed() ? (s.getDevice() == null ? "" : s.getDevice().getSecret()) : "",
          rpc.getFileSize(), rpc.getTargetFileName(), 0);
      case Upload -> body = SoapWriter.upload(rpc.getCommandKey(), rpc.getFileType(), rpc.getUrl(),
          properties.isFileAuthUsed() ? s.getUnitId() : "",
          properties.isFileAuthUsed() ? (s.getDevice() == null ? "" : s.getDevice().getSecret()) : "",
          0);
      case GetParameterAttributes -> body = SoapWriter.getParameterAttributes(rpc.getNames());
      case SetParameterAttributes -> body = SoapWriter.setParameterAttributes(readAttributes(rpc));
      case ScheduleInform -> body = SoapWriter.scheduleInform(0, rpc.getCommandKey());
      default -> body = null;
    }
    if (body == null) {
      return null;
    }
    if (responseForm) {
      return SoapWriter.envelope(in.getHeaderId(), body, s.getCwmpVersion());
    }
    String txId = in.getHeaderId() != null ? in.getHeaderId() : "ACS-" + System.currentTimeMillis();
    return SoapWriter.envelope(txId, body, s.getCwmpVersion());
  }

  private String parameterKey(List<ParameterValueStruct> params) {
    StringBuilder sb = new StringBuilder();
    params.forEach(p -> sb.append(p.getName()).append('=').append(p.getValue()).append(';'));
    String hash = com.acsr.acs.adapt.AdaptService.sha256(sb.toString());
    return hash.length() > 32 ? hash.substring(0, 32) : hash;
  }

  // ============================ 收尾 ============================

  private void endSession(CpeSession s) {
    try {
      provisioning.flush(s);
      if (s.getUnitId() != null) {
        deviceService.touchOnline(s.getUnitId(), s.getRemoteIp());
      }
    } catch (Exception e) {
      log.warn("会话收尾异常: {}", e.getMessage());
    } finally {
      s.setSessionEnd(true);
      sessionStore.remove(s.getId());
      if (s.getUnitId() != null) {
        sessionStore.remove(s.getUnitId());
      }
    }
  }

  /** 等待 TransferComplete 的任务（Download/Upload 类）。 */
  private DispatchTask firstWaitingTask(String unitId) {
    return unitId == null ? null : dispatchService.findWaiting(unitId);
  }

  // ============================ 工具 ============================

  private JsonNode parsePayload(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      log.warn("任务参数解析失败: {}", e.getMessage());
      return null;
    }
  }

  private List<String> readNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    if (node == null) {
      return names;
    }
    if (node.isArray()) {
      node.forEach(n -> names.add(n.asText()));
    } else if (node.has("names") && node.get("names").isArray()) {
      node.get("names").forEach(n -> names.add(n.asText()));
    } else if (node.isTextual()) {
      names.add(node.asText());
    }
    return names;
  }

  private List<ParameterValueStruct> readParams(JsonNode node) {
    List<ParameterValueStruct> list = new ArrayList<>();
    if (node == null) {
      return list;
    }
    if (node.isArray()) {
      for (JsonNode n : node) {
        list.add(new ParameterValueStruct(
            n.path("name").asText(),
            n.path("value").asText(null),
            n.path("type").asText("xsd:string")));
      }
    } else if (node.isObject() && node.has("params")) {
      list.addAll(readParams(node.get("params")));
    } else if (node.isObject()) {
      node.fields().forEachRemaining(e -> list.add(
          new ParameterValueStruct(e.getKey(), e.getValue().asText(null), "xsd:string")));
    }
    return list;
  }

  private List<com.acsr.acs.cwmp.xml.ParameterAttributeStruct> readAttributes(CpeSession.PendingRpc rpc) {
    List<com.acsr.acs.cwmp.xml.ParameterAttributeStruct> list = new ArrayList<>();
    for (String name : rpc.getNames()) {
      var attr = new com.acsr.acs.cwmp.xml.ParameterAttributeStruct();
      attr.setName(name);
      attr.setNotification(0);
      list.add(attr);
    }
    return list;
  }

  private String toJson(Object obj) {
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      return null;
    }
  }

  /** 供管理端查看在线会话。 */
  public Map<String, Object> sessionSnapshot() {
    Map<String, Object> map = new LinkedHashMap<>();
    sessionStore.all().forEach(s -> map.put(s.getId(), s.getUnitId() + " / " + s.getPreviousMethod()));
    return map;
  }
}
