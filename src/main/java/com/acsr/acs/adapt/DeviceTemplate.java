package com.acsr.acs.adapt;

import lombok.Data;

/** SQLite：型号 + 固件适配模板。 */
@Data
public class DeviceTemplate {

  private Long templateId;
  private String deviceBrand;
  private String deviceModel;
  private String firmwareVersion;
  /** 采集到的完整能力树 XML */
  private String capabilityXml;
  /** 适配标记元数据（yaml 文本） */
  private String adaptMetaYml;
  private String templateHash;
  private String createTime;
  private String updateTime;
  private String remark;

  /** 能力树中的参数个数（展示用）。 */
  private int paramCount;
}
