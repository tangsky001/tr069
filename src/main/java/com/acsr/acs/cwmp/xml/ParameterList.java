package com.acsr.acs.cwmp.xml;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 请求/响应中的参数列表容器。 */
@Data
public class ParameterList {

  private List<ParameterValueStruct> parameterValueList = new ArrayList<>();
  private List<ParameterInfoStruct> parameterInfoList = new ArrayList<>();
  private List<ParameterAttributeStruct> parameterAttributeList = new ArrayList<>();

  public void addParameterValueStruct(ParameterValueStruct pvs) {
    parameterValueList.add(pvs);
  }

  public void addParameterInfoStruct(ParameterInfoStruct pis) {
    parameterInfoList.add(pis);
  }

  public void addParameterAttributeStruct(ParameterAttributeStruct pas) {
    parameterAttributeList.add(pas);
  }

  public String getValueByName(String name) {
    return parameterValueList.stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .map(ParameterValueStruct::getValue)
        .orElse(null);
  }

  public void addOrChange(String key, String value, String type) {
    for (ParameterValueStruct pvs : parameterValueList) {
      if (key.equals(pvs.getName())) {
        pvs.setValue(value);
        if (type != null) {
          pvs.setType(type);
        }
        return;
      }
    }
    parameterValueList.add(new ParameterValueStruct(key, value, type == null ? "xsd:string" : type));
  }
}
