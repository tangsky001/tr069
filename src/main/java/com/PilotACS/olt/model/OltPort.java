/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.721
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

@Data
public class OltPort {

    @Schema(description = "机框号，display port state all 这种带 F/S/P 的回显才有")
    private Integer frameNo;

    @Schema(description = "槽位号")
    private Integer slotNo;

    @Schema(description = "端口号")
    private Integer portNo;

    @Schema(description = "端口名，页面显示用，如 P0")
    private String portName;

    @Schema(description = "设备原始光模块状态：normal / absence / abnormal / mismatch")
    private String opticStatus;

    @Schema(description = "光模块是否在位：true 在位、false 不在位、null 未采到")
    private Boolean opticalPresent;

    @Schema(description = "收光功率，如 -8.12 dBm；不在位时为空")
    private String rxPower;

    @Schema(description = "发光功率，如 4.23 dBm（display port state 的 TX power）")
    private String txPower;

    @Schema(description = "光模块温度，如 35 ℃（display port state 的 Temperature）")
    private String opticTemperature;

    @Schema(description = "偏置电流，如 92 mA（TX Bias current）")
    private String biasCurrent;

    @Schema(description = "供电电压，如 3.22 V（Supply Voltage）")
    private String supplyVoltage;

    @Schema(description = "光模块状态，取自 display port state 的 Optical Module status：Online / Offline")
    private String moduleStatus;

    @Schema(description = "激光器状态：Normal / Abnormal")
    private String laserState;

    @Schema(description = "发送故障：Normal / Fault")
    private String txFault;

    @Schema(description = "链路状态，来自 display board 0/ 的 Link 列：online / offline；未采到为 null")
    private String linkStatus;

    @Schema(description = "该端口下的 ONT 总数，来自 config 视图的 display ont info 0 all")
    private Integer onuTotal;

    @Schema(description = "其中在线(online)的 ONT 数")
    private Integer onuOnline;

    @Schema(description = "该端口下每个 ONT 的状态（在线和不在线都在），页面展开后逐条显示")
    private List<OltOnt> onts;

    @Schema(description = "端口指示灯三态（alarm / online / offline），detail 组装时按采集值现算")
    private String lampState;

    // ---------------- 以下字段 detail 组装时算好下发，页面只渲染 ----------------

    @Schema(description = "接口标识，命令行口径：框/槽/口，如 0/1/0")
    private String iface;

    @Schema(description = "上行口放通的 VLAN（压缩成「1、41、45-46」），来自 display port vlan 快照")
    private String vlanText;

    @Schema(description = "ONT 汇总文本：「23 ONT / 22 在线」；汇总行缺失时按明细数兜底，没采到为空")
    private String onuText;

    @Schema(description = "链路状态中文：在线 / 离线 / 空（未采到）")
    private String linkText;

    @Schema(description = "光模块指示灯：on 在位 / off 不在位 / warn 异常 / unknown 未采集")
    private String opticLamp;

    @Schema(description = "光模块指示灯的提示文案（页面 tooltip 直接显示）")
    private String opticLampText;

    @Schema(description = "面板上的口名：PON 口是「PON 2」，上行口按速率前缀是「10GE 1」")
    private String labelText;

    @Schema(description = "面板标注第三行：接口号 + 放通 VLAN，如「0/1/0 · VLAN 41、45-46」，都没有是「—」")
    private String subText;

    @Schema(description = "光模块数值一行：「发光 4.23 dBm · 收光 -8.12 dBm · 温度 35 ℃…」，没采到写「光模块未采集」")
    private String opticText;

    @Schema(description = "面板标注第二行：PON 口写「22 / 23 ONT 在线」，上行口写「链路 在线」")
    private String panelText;

    @Schema(description = "悬停提示：口名 · 接口号 · 灯态 · ONT 汇总 · VLAN · 光模块，页面直接放进 title")
    private String tooltipText;
}
