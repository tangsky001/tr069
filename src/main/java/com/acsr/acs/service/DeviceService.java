package com.acsr.acs.service;

import com.acsr.acs.cwmp.xml.DeviceIdStruct;
import com.acsr.acs.cwmp.xml.ParameterValueStruct;
import com.acsr.acs.mapper.CpeDeviceMapper;
import com.acsr.acs.mapper.ProfileMapper;
import com.acsr.acs.mapper.ProfileParamMapper;
import com.acsr.acs.mapper.UnitParamMapper;
import com.acsr.acs.mapper.UnitTypeMapper;
import com.acsr.acs.mapper.UnitTypeParamMapper;
import com.acsr.acs.model.entity.CpeDevice;
import com.acsr.acs.model.entity.Profile;
import com.acsr.acs.model.entity.ProfileParam;
import com.acsr.acs.model.entity.UnitParam;
import com.acsr.acs.model.entity.UnitType;
import com.acsr.acs.model.entity.UnitTypeParam;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 设备台账 + 设备参数（MySQL）。 */
@Slf4j
@Service
public class DeviceService {

  private final CpeDeviceMapper deviceMapper;
  private final UnitParamMapper unitParamMapper;
  private final ProfileParamMapper profileParamMapper;
  private final ProfileMapper profileMapper;
  private final UnitTypeMapper unitTypeMapper;
  private final UnitTypeParamMapper unitTypeParamMapper;

  private final JdbcTemplate jdbc;

  public DeviceService(CpeDeviceMapper deviceMapper,
                       UnitParamMapper unitParamMapper,
                       ProfileParamMapper profileParamMapper,
                       ProfileMapper profileMapper,
                       UnitTypeMapper unitTypeMapper,
                       UnitTypeParamMapper unitTypeParamMapper,
                       @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
    this.deviceMapper = deviceMapper;
    this.unitParamMapper = unitParamMapper;
    this.profileParamMapper = profileParamMapper;
    this.profileMapper = profileMapper;
    this.unitTypeMapper = unitTypeMapper;
    this.unitTypeParamMapper = unitTypeParamMapper;
    this.jdbc = jdbc;
  }

  // ---------------- 台账 ----------------

  public CpeDevice findByUnitId(String unitId) {
    return deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
        .eq(CpeDevice::getUnitId, unitId));
  }

  public CpeDevice findBySerial(String serial) {
    if (serial == null) {
      return null;
    }
    return deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
        .eq(CpeDevice::getSerialNumber, serial));
  }

  public CpeDevice findByAccount(String account) {
    if (account == null) {
      return null;
    }
    return deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
        .eq(CpeDevice::getAccount, account));
  }

  public CpeDevice findByIp(String ip) {
    if (ip == null) {
      return null;
    }
    List<CpeDevice> list = deviceMapper.selectList(new LambdaQueryWrapper<CpeDevice>()
        .eq(CpeDevice::getIpAddress, ip).last("LIMIT 1"));
    return list.isEmpty() ? null : list.get(0);
  }

  /** 取设备某个参数值（unit_param 优先，其次 profile_param）。 */
  public String paramValue(CpeDevice device, String name) {
    UnitParam up = unitParamMapper.selectOne(new LambdaQueryWrapper<UnitParam>()
        .eq(UnitParam::getUnitId, device.getUnitId()).eq(UnitParam::getName, name));
    if (up != null) {
      return up.getValue();
    }
    if (device.getProfileId() != null) {
      ProfileParam pp = profileParamMapper.selectOne(new LambdaQueryWrapper<ProfileParam>()
          .eq(ProfileParam::getProfileId, device.getProfileId()).eq(ProfileParam::getName, name));
      if (pp != null) {
        return pp.getValue();
      }
    }
    return null;
  }

  public static String buildUnitId(DeviceIdStruct id) {
    String oui = id.getOui() == null ? "" : id.getOui();
    String pc = id.getProductClass() == null ? "" : id.getProductClass().trim();
    String sn = id.getSerialNumber() == null ? "" : id.getSerialNumber();
    return pc.isEmpty() ? oui + "-" + sn : oui + "-" + pc + "-" + sn;
  }

  /** 设备首次上线：自动登记台账（等价 FreeACS 的 discovery）。 */
  public CpeDevice register(DeviceIdStruct id, String unitId, String ip) {
    CpeDevice device = findByUnitId(unitId);
    boolean isNew = device == null;
    if (isNew) {
      device = new CpeDevice();
      device.setUnitId(unitId);
      device.setCreateTime(LocalDateTime.now());
      device.setFirstOnline(LocalDateTime.now());
      device.setProvisioningMode("REGULAR");
      device.setOnline(1);
    }
    device.setSerialNumber(id.getSerialNumber());
    device.setOui(id.getOui());
    device.setManufacturer(id.getManufacturer());
    device.setProductClass(id.getProductClass());
    device.setModel(id.getProductClass());
    device.setLastOnline(LocalDateTime.now());
    device.setIpAddress(ip);
    device.setUpdateTime(LocalDateTime.now());

    Long unitTypeId = ensureUnitType(device.getModel(), id.getManufacturer());
    device.setUnitTypeId(unitTypeId);
    if (device.getProfileId() == null) {
      Profile profile = ensureDefaultProfile(unitTypeId);
      device.setProfileId(profile == null ? null : profile.getId());
    }
    if (isNew) {
      deviceMapper.insert(device);
      log.info("新设备登记台账: unitId={}, model={}", unitId, device.getModel());
    } else {
      deviceMapper.updateById(device);
    }
    return device;
  }

  private Long ensureUnitType(String model, String vendor) {
    if (!StringUtils.hasText(model)) {
      return null;
    }
    UnitType exist = unitTypeMapper.selectOne(new LambdaQueryWrapper<UnitType>()
        .eq(UnitType::getName, model));
    if (exist != null) {
      return exist.getId();
    }
    UnitType ut = new UnitType();
    ut.setName(model);
    ut.setVendor(vendor);
    ut.setProtocol("TR069");
    unitTypeMapper.insert(ut);
    return ut.getId();
  }

  private Profile ensureDefaultProfile(Long unitTypeId) {
    if (unitTypeId == null) {
      return null;
    }
    Profile profile = profileMapper.selectOne(new LambdaQueryWrapper<Profile>()
        .eq(Profile::getUnitTypeId, unitTypeId).eq(Profile::getName, "Default"));
    if (profile != null) {
      return profile;
    }
    profile = new Profile();
    profile.setUnitTypeId(unitTypeId);
    profile.setName("Default");
    profile.setDescription("自动创建的默认配置模板");
    profileMapper.insert(profile);
    return profile;
  }

  /** 上线/心跳更新。 */
  public void touchOnline(String unitId, String ip) {
    jdbc.update(
        "UPDATE cpe_device SET online=1, last_online=NOW(), ip_address=?, update_time=NOW() WHERE unit_id=?",
        ip, unitId);
  }

  /** 离线判定：超过指定秒数没有通信的设备标记离线。 */
  public int markOffline(int seconds) {
    return jdbc.update(
        "UPDATE cpe_device SET online=0 WHERE online=1 AND last_online IS NOT NULL "
            + "AND last_online < DATE_SUB(NOW(), INTERVAL ? SECOND)", seconds);
  }

  public IPage<CpeDevice> page(long current, long size, String keyword, Integer online) {
    LambdaQueryWrapper<CpeDevice> q = new LambdaQueryWrapper<>();
    if (StringUtils.hasText(keyword)) {
      q.and(w -> w.like(CpeDevice::getUnitId, keyword)
          .or().like(CpeDevice::getSerialNumber, keyword)
          .or().like(CpeDevice::getModel, keyword)
          .or().like(CpeDevice::getIpAddress, keyword));
    }
    if (online != null) {
      q.eq(CpeDevice::getOnline, online);
    }
    q.orderByDesc(CpeDevice::getLastOnline);
    return deviceMapper.selectPage(new Page<>(current, size), q);
  }

  public void updateDevice(CpeDevice device) {
    device.setUpdateTime(LocalDateTime.now());
    deviceMapper.updateById(device);
  }

  public void deleteDevice(Long id) {
    CpeDevice d = deviceMapper.selectById(id);
    if (d != null) {
      unitParamMapper.delete(new LambdaQueryWrapper<UnitParam>().eq(UnitParam::getUnitId, d.getUnitId()));
      deviceMapper.deleteById(id);
    }
  }

  // ---------------- 参数 ----------------

  /** ACS 侧目标参数：profile 默认值 + 设备覆盖值。 */
  public Map<String, ParameterValueStruct> desiredParams(CpeDevice device) {
    Map<String, ParameterValueStruct> map = new HashMap<>();
    if (device.getProfileId() != null) {
      List<ProfileParam> pps = profileParamMapper.selectList(
          new LambdaQueryWrapper<ProfileParam>().eq(ProfileParam::getProfileId, device.getProfileId()));
      for (ProfileParam pp : pps) {
        map.put(pp.getName(), new ParameterValueStruct(pp.getName(), pp.getValue(), "xsd:string"));
      }
    }
    List<UnitParam> ups = unitParamMapper.selectList(
        new LambdaQueryWrapper<UnitParam>().eq(UnitParam::getUnitId, device.getUnitId()));
    for (UnitParam up : ups) {
      map.put(up.getName(), new ParameterValueStruct(up.getName(), up.getValue(), "xsd:string"));
    }
    return map;
  }

  /** 回写设备参数（MySQL unit_param，UPSERT）。 */
  public void writeParams(String unitId, List<ParameterValueStruct> params, String flags) {
    if (params == null || params.isEmpty()) {
      return;
    }
    for (ParameterValueStruct p : params) {
      if (p.getName() == null || p.getName().isBlank()) {
        continue;
      }
      jdbc.update(
          "INSERT INTO unit_param (unit_id, name, value, flags) VALUES (?,?,?,?) "
              + "ON DUPLICATE KEY UPDATE value=VALUES(value), flags=VALUES(flags)",
          unitId, p.getName(), truncate(p.getValue(), 500), flags == null ? "R" : flags);
    }
  }

  /** 回写会话级参数（READALL 采集模式）。 */
  public void writeSessionParams(String unitId, List<ParameterValueStruct> params) {
    if (params == null || params.isEmpty()) {
      return;
    }
    for (ParameterValueStruct p : params) {
      jdbc.update(
          "INSERT INTO unit_param_session (unit_id, name, value) VALUES (?,?,?) "
              + "ON DUPLICATE KEY UPDATE value=VALUES(value)",
          unitId, p.getName(), truncate(p.getValue(), 500));
    }
  }

  public List<UnitParam> params(String unitId) {
    return unitParamMapper.selectList(
        new LambdaQueryWrapper<UnitParam>().eq(UnitParam::getUnitId, unitId).orderByAsc(UnitParam::getName));
  }

  /** 设置单个设备参数目标值（管理端下发）。 */
  public void setParam(String unitId, String name, String value, String flags) {
    jdbc.update(
        "INSERT INTO unit_param (unit_id, name, value, flags) VALUES (?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE value=VALUES(value), flags=VALUES(flags)",
        unitId, name, truncate(value, 500), flags == null ? "RW" : flags);
  }

  public void deleteParam(String unitId, String name) {
    unitParamMapper.delete(new LambdaQueryWrapper<UnitParam>()
        .eq(UnitParam::getUnitId, unitId).eq(UnitParam::getName, name));
  }

  // ---------------- 型号参数元数据 ----------------

  public List<UnitTypeParam> unitTypeParams(Long unitTypeId) {
    if (unitTypeId == null) {
      return new ArrayList<>();
    }
    return unitTypeParamMapper.selectList(
        new LambdaQueryWrapper<UnitTypeParam>().eq(UnitTypeParam::getUnitTypeId, unitTypeId));
  }

  /** 把采集到的能力树同步一份到 MySQL（便于管理端检索）。 */
  public void syncUnitTypeParams(Long unitTypeId, Map<String, String> capability) {
    if (unitTypeId == null || capability == null) {
      return;
    }
    for (Map.Entry<String, String> e : capability.entrySet()) {
      jdbc.update(
          "INSERT INTO unit_type_param (unit_type_id, name, flags) VALUES (?,?,?) "
              + "ON DUPLICATE KEY UPDATE flags=VALUES(flags)",
          unitTypeId, e.getKey(), e.getValue());
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() <= max ? s : s.substring(0, max);
  }
}
