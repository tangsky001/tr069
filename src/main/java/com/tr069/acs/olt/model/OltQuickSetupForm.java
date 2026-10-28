package com.tr069.acs.olt.model;

import java.util.List;

/**
 * 一键开局表单的取值约束和默认值。
 *
 * <p>模式有哪些、VLAN 范围是多少、TR-069 各项默认填什么，都是设备侧约定，
 * 由后端一处给出，页面照着渲染即可——前端不再硬编码这些规则。</p>
 *
 * @param supported        这台设备有没有接入一键开局
 * @param modes            可选的端口 VLAN 模式
 * @param defaultMode      默认模式
 * @param portTypes        可选的端口类型
 * @param defaultPortTypes 默认勾选哪些类型的端口（PON + GE，不动 10GE）
 * @param maxPorts         一次最多能改多少个端口
 * @param vlanMin          VLAN ID 下限
 * @param vlanMax          VLAN ID 上限
 * @param tr069Defaults    TR-069 段的默认值
 */
public record OltQuickSetupForm(boolean supported, List<String> modes, String defaultMode,
                                List<String> portTypes, List<String> defaultPortTypes, int maxPorts,
                                int vlanMin, int vlanMax, OltTr069ProfileRequest tr069Defaults) {
}
