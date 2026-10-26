package com.acsr.acs.cwmp.xml;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** GetParameterAttributesResponse 中的 ParameterAttributeStruct。 */
@Data
public class ParameterAttributeStruct {
  private String name;
  private int notification;
  private List<String> accessList = new ArrayList<>();
}
