package com.acsr.acs.cwmp.xml;

import lombok.Data;

/** GetParameterNamesResponse 中的 ParameterInfoStruct。 */
@Data
public class ParameterInfoStruct {
  private String name;
  private boolean writable;
}
