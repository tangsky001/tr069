package com.acsr.acs.cwmp.xml;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** ParameterValueStruct：参数名 + 值 + xsi 类型。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParameterValueStruct {

  private String name;
  private String value;
  private String type = "xsd:string";

  public ParameterValueStruct(String name, String value) {
    this.name = name;
    this.value = value;
  }
}
