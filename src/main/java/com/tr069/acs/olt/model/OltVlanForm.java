package com.tr069.acs.olt.model;

import java.util.List;

/** 「新建 VLAN」表单的元数据：VLAN ID 范围、一次最多几个、哪些端口能做成员 */
public record OltVlanForm(boolean supported, int vlanIdMin, int vlanIdMax,
                          int batchMax, int batchMaxWithPorts,
                          List<String> portTypes, boolean exclusiveTagMode) {
}
