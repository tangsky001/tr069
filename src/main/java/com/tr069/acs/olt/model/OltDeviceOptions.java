package com.tr069.acs.olt.model;

import java.util.List;
import java.util.Map;

/**
 * 页面要用的码表与阈值：厂商、字符集、默认端口账号、板卡档案、各类中文对照。
 *
 * <p>这些原来硬编码在页面里，和后端枚举/常量是两份：加一个厂商、改一条状态文案
 * 都得动两处，而且改了一处忘另一处就会显示不一致。现在统一由后端给。</p>
 *
 * @param vendors            厂商选项
 * @param charsets           采集连接字符集
 * @param sshPort            SSH 默认端口
 * @param telnetPort         Telnet 默认端口
 * @param defaultUsername    默认登录账号
 * @param defaultPassword    默认登录密码
 * @param boardProfiles      板卡型号档案（页面要展示型号说明时直接用）
 * @param viewLabels         命令动作视图 → 中文名
 * @param onuActions         ONT/ONU 上能做的操作
 * @param vlanTypeLabels     VLAN 类型码 → 中文名
 * @param boardCategoryLabels 板卡类别码 → 中文名
 * @param defaultSlotCount   机框槽位数的兜底值（设备没回时用）
 * @param envTempAlarmC      环境温度告警阈值(摄氏度)
 */
public record OltDeviceOptions(List<OltOption> vendors, List<String> charsets,
                               int sshPort, int telnetPort, String defaultUsername, String defaultPassword,
                               List<OltBoardProfile> boardProfiles, Map<String, String> viewLabels,
                               List<OltOption> onuActions, Map<String, String> vlanTypeLabels,
                               Map<String, String> boardCategoryLabels, int defaultSlotCount,
                               int envTempAlarmC) {
}
