package com.acsr.acs.cwmp.xml;

import lombok.Data;

/** Inform 中的 EventStruct（如 "1 BOOT"、"2 PERIODIC"）。 */
@Data
public class EventStruct {
  private String eventCode;
  private String commandKey;
}
