package com.acsr.acs.cwmp.xml;

import lombok.Data;

/** Inform 中的 DeviceId 结构。 */
@Data
public class DeviceIdStruct {
  private String manufacturer;
  private String oui;
  private String productClass;
  private String serialNumber;
}
