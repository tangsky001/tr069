package com.acsr.acs.adapt;

import com.acsr.acs.cwmp.xml.ParameterInfoStruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 光猫适配：按「型号 + 固件版本」匹配本地适配库（SQLite），
 * 未命中的设备在 discovery 模式下采集能力树后入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptService {

  private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final AdaptTemplateDao dao;
  private final Map<Long, CapabilityTree> capabilityCache = new ConcurrentHashMap<>();

  /** 按型号 + 固件匹配模板。 */
  public Optional<DeviceTemplate> match(String model, String firmware) {
    if (model == null || model.isBlank()) {
      return Optional.empty();
    }
    DeviceTemplate tpl = dao.find(model, firmware == null ? "" : firmware);
    return Optional.ofNullable(tpl);
  }

  /** 读取能力树（带缓存）。 */
  public CapabilityTree capability(Long templateId) {
    if (templateId == null) {
      return new CapabilityTree();
    }
    return capabilityCache.computeIfAbsent(templateId, id -> {
      DeviceTemplate tpl = dao.get(id);
      return tpl == null ? new CapabilityTree() : CapabilityTree.parse(tpl.getCapabilityXml());
    });
  }

  public void invalidate(Long templateId) {
    capabilityCache.remove(templateId);
  }

  /** 能力采集：把 GetParameterNames 的结果写入 SQLite 适配库。 */
  public Long collect(String brand, String model, String firmware,
                      List<ParameterInfoStruct> infos, String remark) {
    CapabilityTree tree = new CapabilityTree();
    tree.setModel(model);
    tree.setFirmware(firmware == null ? "" : firmware);
    if (infos != null) {
      for (ParameterInfoStruct info : infos) {
        if (info.getName() == null || info.getName().isBlank()) {
          continue;
        }
        tree.put(info.getName(), info.isWritable(), "xsd:string");
      }
    }
    String xml = tree.toXml();
    String now = LocalDateTime.now().format(FMT);
    String meta = "adapted: true\nadapt_time: " + now + "\nbrand: " + (brand == null ? "" : brand)
        + "\nmodel: " + model + "\nfirmware: " + firmware + "\nparam_count: " + tree.size();
    DeviceTemplate tpl = new DeviceTemplate();
    tpl.setDeviceBrand(brand == null ? "" : brand);
    tpl.setDeviceModel(model);
    tpl.setFirmwareVersion(firmware == null ? "" : firmware);
    tpl.setCapabilityXml(xml);
    tpl.setAdaptMetaYml(meta);
    tpl.setTemplateHash(sha256(xml + meta));
    tpl.setRemark(remark == null ? "自动采集入库" : remark);
    Long id = dao.save(tpl);
    if (id != null) {
      invalidate(id);
      log.info("新型号适配入库: model={}, firmware={}, 参数 {} 个, templateId={}", model, firmware, tree.size(), id);
    }
    return id;
  }

  /** 记录设备命中。 */
  public void recordHit(String serial, Long templateId) {
    if (serial == null || templateId == null) {
      return;
    }
    dao.recordMatch(serial, templateId);
  }

  public List<DeviceTemplate> listTemplates() {
    List<DeviceTemplate> list = dao.list();
    for (DeviceTemplate t : list) {
      t.setParamCount(countParams(t.getCapabilityXml()));
      // 列表不返回大字段，避免传输量过大
      t.setCapabilityXml(null);
      t.setAdaptMetaYml(null);
    }
    return list;
  }

  public DeviceTemplate getTemplate(Long id) {
    return dao.get(id);
  }

  public void deleteTemplate(Long id) {
    dao.delete(id);
    invalidate(id);
  }

  public List<AdaptParamMap> paramMaps(Long templateId) {
    return dao.paramMaps(templateId);
  }

  public void saveParamMap(AdaptParamMap m) {
    dao.saveParamMap(m);
  }

  public void deleteParamMap(Long id) {
    dao.deleteParamMap(id);
  }

  public List<DeviceAdapted> listAdapted() {
    return dao.listAdapted();
  }

  /** 设备最近一次命中的模板。 */
  public DeviceAdapted adapted(String serial) {
    return dao.findAdapted(serial);
  }

  private static int countParams(String xml) {
    if (xml == null || xml.isEmpty()) {
      return 0;
    }
    int count = 0;
    int idx = 0;
    while ((idx = xml.indexOf("<param ", idx)) >= 0) {
      count++;
      idx += 7;
    }
    return count;
  }

  public static String sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      return "";
    }
  }

  /** 能力树参数名清单（供前端浏览）。 */
  public List<String> capabilityNames(Long templateId) {
    return new ArrayList<>(capability(templateId).getParams().keySet());
  }
}
