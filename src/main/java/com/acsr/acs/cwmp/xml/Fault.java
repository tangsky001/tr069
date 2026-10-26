package com.acsr.acs.cwmp.xml;

import lombok.Data;

/** CWMP Fault 结构。 */
@Data
public class Fault {
  private String faultCode;
  private String faultString;
  private String parameterName;
  private String setParameterValuesFaultCode;
}
