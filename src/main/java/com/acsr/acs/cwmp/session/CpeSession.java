package com.acsr.acs.cwmp.session;

import com.acsr.acs.cwmp.xml.CwmpMethod;
import com.acsr.acs.cwmp.xml.ParameterValueStruct;
import com.acsr.acs.model.entity.CpeDevice;
import lombok.Data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 一次 TR-069 会话的临时态（进程内保存，等价于 FreeACS 的 SessionData）。 */
@Data
public class CpeSession {

  /** 会话标识（JSESSIONID 或 unitId）。 */
  private String id;
  private long createdAt = System.currentTimeMillis();
  private long lastAccessAt = System.currentTimeMillis();

  // ---------- 设备身份 ----------
  private String unitId;
  private String serialNumber;
  private String oui;
  private String productClass;
  private String manufacturer;
  private String model;
  private String softwareVersion;
  private String firmwareVersion;
  private String hardwareVersion;
  private String keyRoot;
  private String cwmpVersion = "1-0";
  private String remoteIp;

  private CpeDevice device;
  /** SQLite 适配模板 id */
  private Long templateId;
  /** 是否已适配（命中 SQLite 模板） */
  private boolean adapted;
  /** 本会话是否需要先做能力采集（GPN） */
  private boolean needDiscovery;

  // ---------- 事件 ----------
  private boolean booted;
  private boolean periodic;
  private boolean valueChange;
  private boolean kicked;
  private boolean transferComplete;
  private boolean autonomousTransferComplete;
  private boolean diagnosticsComplete;
  private boolean factoryReset;
  private String eventCodes;
  private String eventCommandKey;

  // ---------- 参数集合 ----------
  /** ACS 侧（profile + unit）目标参数 */
  private Map<String, ParameterValueStruct> fromDB = new HashMap<>();
  /** CPE 返回的参数 */
  private List<ParameterValueStruct> valuesFromCPE = new ArrayList<>();
  /** 待写入 CPE */
  private List<ParameterValueStruct> toCPE = new ArrayList<>();
  /** 待写回 MySQL */
  private List<ParameterValueStruct> toDB = new ArrayList<>();
  /** 本轮向 CPE 请求的参数 */
  private List<ParameterValueStruct> requestedCPE = new ArrayList<>();
  /** ACS 关注的内置参数（PII / ConnectionRequestURL / SoftwareVersion ...） */
  private Map<String, String> cpeParams = new HashMap<>();

  /** 参数读写标记：name -> flags（R / RW），来自 SQLite 能力树 */
  private Map<String, String> capability = new HashMap<>();

  // ---------- 会话流程 ----------
  /** 上一轮 ACS 发给 CPE 的方法 */
  private CwmpMethod previousMethod = CwmpMethod.Empty;
  /** 上上轮 */
  private CwmpMethod methodBeforePrevious = CwmpMethod.Empty;
  /** 本轮已下发的 RPC（等待 CPE 响应） */
  private PendingRpc currentRpc;
  private String parameterKeyFromCpe;
  private String parameterKeyServer;
  private String commandKeyFromCpe;
  private String commandKeyServer;

  /** ACS 主动下发的 RPC 队列（同一会话内按序执行） */
  private Deque<PendingRpc> pending = new ArrayDeque<>();
  /** 当前正在执行的下发任务 id */
  private Long currentTaskId;

  private boolean provisioningAllowed = true;
  private boolean sessionEnd;

  public void touch() {
    this.lastAccessAt = System.currentTimeMillis();
  }

  public boolean isExpired(long timeoutSeconds) {
    return System.currentTimeMillis() - lastAccessAt > timeoutSeconds * 1000L;
  }

  public void pushRpc(PendingRpc rpc) {
    pending.addLast(rpc);
  }

  public PendingRpc pollRpc() {
    return pending.pollFirst();
  }

  public boolean hasRpc() {
    return !pending.isEmpty();
  }

  /** 会话内待下发的 RPC。 */
  @Data
  public static class PendingRpc {
    private CwmpMethod method;
    /** SetParameterValues 用 */
    private List<ParameterValueStruct> params = new ArrayList<>();
    /** GetParameterValues / GetParameterNames / GetParameterAttributes 用 */
    private List<String> names = new ArrayList<>();
    /** AddObject / DeleteObject 用 */
    private String objectName;
    /** Download / Upload 用 */
    private String url;
    private String fileType;
    private String commandKey;
    private long fileSize;
    private String targetFileName;
    /** 关联的下发任务 id */
    private Long taskId;

    public PendingRpc(CwmpMethod method) {
      this.method = method;
    }
  }
}
