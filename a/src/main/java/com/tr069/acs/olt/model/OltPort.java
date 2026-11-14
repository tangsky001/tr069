package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.List;

/** PON 端口状态，随板卡一起返回给页面，用来点亮「光模块在位」指示灯 */
@Data
public class OltPort {

    /** 机框号，display port state all 这种带 F/S/P 的回显才有 */
    private Integer frameNo;

    /** 槽位号 */
    private Integer slotNo;

    /** 端口号 */
    private Integer portNo;

    /** 端口名，页面显示用，如 P0 */
    private String portName;

    /** 设备原始光模块状态：normal / absence / abnormal / mismatch */
    private String opticStatus;

    /** 光模块是否在位：true 在位、false 不在位、null 未采到 */
    private Boolean opticalPresent;

    /** 收光功率，如 -8.12 dBm；不在位时为空 */
    private String rxPower;

    /** 发光功率，如 4.23 dBm（display port state 的 TX power） */
    private String txPower;

    /** 光模块温度，如 35 ℃（display port state 的 Temperature） */
    private String opticTemperature;

    /** 偏置电流，如 92 mA（TX Bias current） */
    private String biasCurrent;

    /** 供电电压，如 3.22 V（Supply Voltage） */
    private String supplyVoltage;

    /** 光模块状态，取自 display port state 的 Optical Module status：Online / Offline */
    private String moduleStatus;

    /** 激光器状态：Normal / Abnormal */
    private String laserState;

    /** 发送故障：Normal / Fault */
    private String txFault;

    /** 链路状态，来自 display board 0/<槽位> 的 Link 列：online / offline；未采到为 null */
    private String linkStatus;

    /** 该端口下的 ONT 总数，来自 config 视图的 display ont info 0 <槽位> all */
    private Integer onuTotal;

    /** 其中在线(online)的 ONT 数 */
    private Integer onuOnline;

    /** 该端口下每个 ONT 的状态（在线和不在线都在），页面展开后逐条显示 */
    private List<OltOnt> onts;

    /** 端口指示灯三态（alarm / online / offline），detail 组装时按采集值现算 */
    private String lampState;

    // ---------------- 以下字段 detail 组装时算好下发，页面只渲染 ----------------

    /** 接口标识，命令行口径：框/槽/口，如 {@code 0/1/0} */
    private String iface;

    /** 上行口放通的 VLAN（压缩成「1、41、45-46」），来自 display port vlan 快照 */
    private String vlanText;

    /** ONT 汇总文本：「23 ONT / 22 在线」；汇总行缺失时按明细数兜底，没采到为空 */
    private String onuText;

    /** 链路状态中文：在线 / 离线 / 空（未采到） */
    private String linkText;

    /** 光模块指示灯：on 在位 / off 不在位 / warn 异常 / unknown 未采集 */
    private String opticLamp;

    /** 光模块指示灯的提示文案（页面 tooltip 直接显示） */
    private String opticLampText;

    /** 面板上的口名：PON 口是「PON 2」，上行口按速率前缀是「10GE 1」 */
    private String labelText;

    /** 面板标注第三行：接口号 + 放通 VLAN，如「0/1/0 · VLAN 41、45-46」，都没有是「—」 */
    private String subText;

    /** 光模块数值一行：「发光 4.23 dBm · 收光 -8.12 dBm · 温度 35 ℃…」，没采到写「光模块未采集」 */
    private String opticText;

    /** 面板标注第二行：PON 口写「22 / 23 ONT 在线」，上行口写「链路 在线」 */
    private String panelText;

    /** 悬停提示：口名 · 接口号 · 灯态 · ONT 汇总 · VLAN · 光模块，页面直接放进 title */
    private String tooltipText;
}
