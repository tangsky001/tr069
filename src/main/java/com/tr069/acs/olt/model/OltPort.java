package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.List;

/**
 * PON 端口状态，随板卡一起返回给页面，用来点亮「光模块在位」指示灯。
 *
 * <p>华为 display port state 回显里的 Optic Status 取值：
 * {@code normal} 在位、{@code absence} 不在位、{@code abnormal} 故障、{@code mismatch} 类型不匹配。
 * 解析不出时 {@code opticalPresent} 为 null，页面显示灰灯（未采集），而不是误报「不在位」。</p>
 */
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

    /** 链路状态，来自 display board 0/<槽位> 的 Link 列：online / offline；未采到为 null */
    private String linkStatus;

    /** 该端口下的 ONT 总数，来自 config 视图的 display ont info 0 <槽位> all */
    private Integer onuTotal;

    /** 其中在线(online)的 ONT 数 */
    private Integer onuOnline;

    /** 该端口下每个 ONT 的状态（在线和不在线都在），页面展开后逐条显示 */
    private List<OltOnt> onts;
}
