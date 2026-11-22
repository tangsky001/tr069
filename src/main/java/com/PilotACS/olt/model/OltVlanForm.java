/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.537
 *
 */

package com.PilotACS.olt.model;

import java.util.List;

/** 新建 VLAN 表单的元数据 */
public record OltVlanForm(boolean supported, int vlanIdMin, int vlanIdMax,
                          int batchMax, int batchMaxWithPorts,
                          List<String> portTypes, boolean exclusiveTagMode) {
}
