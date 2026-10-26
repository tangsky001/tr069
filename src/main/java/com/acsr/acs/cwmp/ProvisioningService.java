package com.acsr.acs.cwmp;

import com.acsr.acs.adapt.AdaptService;
import com.acsr.acs.cwmp.session.CpeSession;
import com.acsr.acs.cwmp.xml.ParameterValueStruct;
import com.acsr.acs.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 业务开通（provisioning）：比较「ACS 目标值」与「CPE 实际值」，
 * 产出待写入 CPE（toCPE）与待回写 MySQL（toDB）的参数集合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisioningService {

  /** 设备参数前缀：InternetGatewayDevice. 或 Device. */
  public static final String[] KEY_ROOTS = {"InternetGatewayDevice.", "Device."};

  private final DeviceService deviceService;
  private final AdaptService adaptService;
  private final com.acsr.acs.config.DeviceProperties deviceProperties;
  private final com.acsr.acs.config.AcsProperties acsProperties;

  /** ACS 关注的内置参数路径（相对 keyRoot）。 */
  public static final String SUFFIX_SOFTWARE_VERSION = "DeviceInfo.SoftwareVersion";
  public static final String SUFFIX_PII = "ManagementServer.PeriodicInformInterval";
  public static final String SUFFIX_CONN_URL = "ManagementServer.ConnectionRequestURL";
  public static final String SUFFIX_CONN_USER = "ManagementServer.ConnectionRequestUsername";
  public static final String SUFFIX_CONN_PASS = "ManagementServer.ConnectionRequestPassword";
  public static final String SUFFIX_ACS_URL = "ManagementServer.URL";
  public static final String SUFFIX_VENDOR_CONFIG_FILE = "DeviceInfo.VendorConfigFile.";

  /** 探测 keyRoot（根据第一个参数名）。 */
  public static String detectKeyRoot(List<ParameterValueStruct> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    for (ParameterValueStruct pvs : values) {
      String name = pvs.getName();
      if (name == null) {
        continue;
      }
      for (String root : KEY_ROOTS) {
        if (name.startsWith(root)) {
          return root;
        }
      }
    }
    return null;
  }

  /** 加载 ACS 侧目标参数（profile + unit，并叠加设备纳管项）。 */
  public Map<String, ParameterValueStruct> loadDesiredParams(CpeSession session) {
    Map<String, ParameterValueStruct> desired = new LinkedHashMap<>();
    if (session.getDevice() != null) {
      desired.putAll(deviceService.desiredParams(session.getDevice()));
    }
    // 设备纳管：ACS 地址 + 周期上报间隔 + 反向连接账号
    if (deviceProperties.getProvision().isEnabled() && session.getKeyRoot() != null) {
      String root = session.getKeyRoot();
      int interval = deviceProperties.getProvision().getInformIntervalSeconds();
      if (interval > 0) {
        desired.put(root + SUFFIX_PII,
            new ParameterValueStruct(root + SUFFIX_PII, String.valueOf(interval), "xsd:unsignedInt"));
      }
      String acsUrl = deviceProperties.getProvision().getAcsUrl();
      if (acsUrl == null || acsUrl.isBlank()) {
        acsUrl = acsProperties.getPublicUrl() + "/ACS-server/ACS";
      }
      desired.put(root + SUFFIX_ACS_URL,
          new ParameterValueStruct(root + SUFFIX_ACS_URL, acsUrl, "xsd:string"));
      String crUser = deviceProperties.getProvision().getConnectionRequestUsername();
      String crPass = deviceProperties.getProvision().getConnectionRequestPassword();
      if (crUser != null && !crUser.isBlank()) {
        desired.put(root + SUFFIX_CONN_USER,
            new ParameterValueStruct(root + SUFFIX_CONN_USER, crUser, "xsd:string"));
      }
      if (crPass != null && !crPass.isBlank()) {
        desired.put(root + SUFFIX_CONN_PASS,
            new ParameterValueStruct(root + SUFFIX_CONN_PASS, crPass, "xsd:string"));
      }
    }
    return desired;
  }

  /** 把 ACS 必须知道的内置参数补进 GPV 请求。 */
  public void addCpeParams(CpeSession session) {
    String root = session.getKeyRoot();
    if (root == null) {
      return;
    }
    List<String> required = List.of(
        root + SUFFIX_SOFTWARE_VERSION,
        root + SUFFIX_PII,
        root + SUFFIX_CONN_URL,
        root + "DeviceInfo.VendorConfigFile.");
    for (String name : required) {
      session.getFromDB().putIfAbsent(name,
          new ParameterValueStruct(name, "ExtraCPEParam", "xsd:string"));
    }
  }

  /** 计算差异：toCPE（ACS->CPE）与 toDB（CPE->ACS）。 */
  public void diff(CpeSession session) {
    List<ParameterValueStruct> toCPE = new ArrayList<>();
    List<ParameterValueStruct> toDB = new ArrayList<>();
    for (ParameterValueStruct cpe : session.getValuesFromCPE()) {
      if (cpe.getName() == null) {
        continue;
      }
      ParameterValueStruct acs = session.getFromDB().get(cpe.getName());
      if (acs == null) {
        // CPE 上报了 ACS 没有登记的参数：只在学习模式下回写
        continue;
      }
      if ("ExtraCPEParam".equals(acs.getValue())) {
        continue;
      }
      if (!Objects.equals(acs.getValue(), cpe.getValue())) {
        if (isWritable(session, cpe.getName())) {
          ParameterValueStruct write = new ParameterValueStruct(
              acs.getName(), acs.getValue(), cpe.getType() == null ? "xsd:string" : cpe.getType());
          toCPE.add(write);
          log.debug("ACS->CPE {} CPE[{}] ACS[{}]", acs.getName(), cpe.getValue(), acs.getValue());
        } else {
          toDB.add(cpe);
          log.debug("CPE->ACS {} CPE[{}] ACS[{}]（只读参数）", cpe.getName(), cpe.getValue(), acs.getValue());
        }
      }
    }
    // ACS 登记但 CPE 没有值的参数：以 CPE 为准回写
    for (ParameterValueStruct cpe : session.getValuesFromCPE()) {
      ParameterValueStruct acs = session.getFromDB().get(cpe.getName());
      if (acs != null && acs.getValue() == null && cpe.getValue() != null && !cpe.getValue().isEmpty()) {
        toDB.add(cpe);
      }
    }
    session.setToCPE(toCPE);
    session.setToDB(toDB);
  }

  /** 判断参数是否可写：优先取 SQLite 能力树，其次型号参数元数据，最后默认可写。 */
  public boolean isWritable(CpeSession session, String name) {
    String flags = session.getCapability().get(name);
    if (flags != null) {
      return flags.contains("W");
    }
    if (session.getDevice() != null && session.getDevice().getUnitTypeId() != null) {
      var params = deviceService.unitTypeParams(session.getDevice().getUnitTypeId());
      for (var p : params) {
        if (name.equals(p.getName()) && p.getFlags() != null) {
          return p.getFlags().contains("W");
        }
      }
    }
    return true;
  }

  /** 回写 MySQL。 */
  public void flush(CpeSession session) {
    if (session.getToDB() == null || session.getToDB().isEmpty()) {
      return;
    }
    deviceService.writeParams(session.getUnitId(), session.getToDB(), "R");
  }

  /** 能力树标记：name -> R/RW。 */
  public Map<String, String> capabilityFlags(Long templateId) {
    Map<String, String> flags = new LinkedHashMap<>();
    adaptService.capability(templateId).getParams()
        .forEach((name, meta) -> flags.put(name, meta.flags()));
    return flags;
  }
}
