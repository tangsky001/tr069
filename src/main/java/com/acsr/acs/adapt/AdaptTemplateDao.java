package com.acsr.acs.adapt;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** SQLite 适配库访问（acs 模块是唯一写方）。 */
@Repository
public class AdaptTemplateDao {

  private final JdbcTemplate jdbc;

  public AdaptTemplateDao(@Qualifier("sqliteJdbcTemplate") JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final BeanPropertyRowMapper<DeviceTemplate> TEMPLATE_MAPPER =
      new BeanPropertyRowMapper<>(DeviceTemplate.class);
  private static final BeanPropertyRowMapper<AdaptParamMap> PARAM_MAPPER =
      new BeanPropertyRowMapper<>(AdaptParamMap.class);
  private static final BeanPropertyRowMapper<DeviceAdapted> ADAPTED_MAPPER =
      new BeanPropertyRowMapper<>(DeviceAdapted.class);

  public DeviceTemplate find(String model, String firmware) {
    List<DeviceTemplate> list = jdbc.query(
        "SELECT * FROM device_template WHERE device_model = ? AND firmware_version = ?",
        TEMPLATE_MAPPER, model, firmware == null ? "" : firmware);
    return list.isEmpty() ? null : list.get(0);
  }

  public DeviceTemplate get(Long id) {
    List<DeviceTemplate> list = jdbc.query(
        "SELECT * FROM device_template WHERE template_id = ?", TEMPLATE_MAPPER, id);
    return list.isEmpty() ? null : list.get(0);
  }

  public List<DeviceTemplate> list() {
    return jdbc.query(
        "SELECT * FROM device_template ORDER BY device_model, firmware_version", TEMPLATE_MAPPER);
  }

  public int delete(Long id) {
    jdbc.update("DELETE FROM adapt_param_map WHERE template_id = ?", id);
    jdbc.update("DELETE FROM device_adapted WHERE template_id = ?", id);
    return jdbc.update("DELETE FROM device_template WHERE template_id = ?", id);
  }

  /** 按 model + firmware 保存（存在则更新能力树）。 */
  public Long save(DeviceTemplate t) {
    String model = t.getDeviceModel();
    String firmware = t.getFirmwareVersion() == null ? "" : t.getFirmwareVersion();
    String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    int updated = jdbc.update(
        "UPDATE device_template SET device_brand=?, capability_xml=?, adapt_meta_yml=?, template_hash=?, update_time=?, remark=? "
            + "WHERE device_model=? AND firmware_version=?",
        t.getDeviceBrand(), t.getCapabilityXml(), t.getAdaptMetaYml(), t.getTemplateHash(), now,
        t.getRemark(), model, firmware);
    if (updated == 0) {
      jdbc.update(
          "INSERT INTO device_template (device_brand, device_model, firmware_version, capability_xml, adapt_meta_yml, template_hash, create_time, update_time, remark) "
              + "VALUES (?,?,?,?,?,?,?,?,?)",
          t.getDeviceBrand(), model, firmware, t.getCapabilityXml(), t.getAdaptMetaYml(),
          t.getTemplateHash(), now, now, t.getRemark());
    }
    DeviceTemplate saved = find(model, firmware);
    return saved == null ? null : saved.getTemplateId();
  }

  public List<AdaptParamMap> paramMaps(Long templateId) {
    return jdbc.query(
        "SELECT * FROM adapt_param_map WHERE template_id = ? ORDER BY id", PARAM_MAPPER, templateId);
  }

  public void saveParamMap(AdaptParamMap m) {
    Long id = m.getId();
    if (id == null) {
      jdbc.update(
          "INSERT OR REPLACE INTO adapt_param_map (template_id, inform_path, get_path, set_path, value_type, transform) "
              + "VALUES (?,?,?,?,?,?)",
          m.getTemplateId(), m.getInformPath(), m.getGetPath(), m.getSetPath(), m.getValueType(), m.getTransform());
    } else {
      jdbc.update(
          "UPDATE adapt_param_map SET template_id=?, inform_path=?, get_path=?, set_path=?, value_type=?, transform=? WHERE id=?",
          m.getTemplateId(), m.getInformPath(), m.getGetPath(), m.getSetPath(), m.getValueType(), m.getTransform(), id);
    }
  }

  public void deleteParamMap(Long id) {
    jdbc.update("DELETE FROM adapt_param_map WHERE id = ?", id);
  }

  /** 记录设备命中模板。 */
  public void recordMatch(String serial, Long templateId) {
    String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    int updated = jdbc.update(
        "UPDATE device_adapted SET template_id=?, last_match_at=?, success_count=COALESCE(success_count,0)+1 WHERE serial_number=?",
        templateId, now, serial);
    if (updated == 0) {
      jdbc.update(
          "INSERT OR IGNORE INTO device_adapted (serial_number, template_id, last_match_at, success_count) VALUES (?,?,?,1)",
          serial, templateId, now);
    }
  }

  public DeviceAdapted findAdapted(String serial) {
    List<DeviceAdapted> list = jdbc.query(
        "SELECT * FROM device_adapted WHERE serial_number = ?", ADAPTED_MAPPER, serial);
    return list.isEmpty() ? null : list.get(0);
  }

  public List<DeviceAdapted> listAdapted() {
    return jdbc.query(
        "SELECT * FROM device_adapted ORDER BY last_match_at DESC LIMIT 1000", ADAPTED_MAPPER);
  }
}
