package com.acsr.acs.adapt;

import lombok.Data;

/** SQLite：本机已适配设备的调用记录。 */
@Data
public class DeviceAdapted {

  private Long id;
  private String serialNumber;
  private Long templateId;
  private String lastMatchAt;
  private Integer successCount;
}
