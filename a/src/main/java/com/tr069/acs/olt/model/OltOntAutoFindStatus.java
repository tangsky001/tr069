package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 各 PON 口「自动发现」（华为 {@code ont-auto-find}）的开关状态 */
@Data
public class OltOntAutoFindStatus {

    /** 这台设备能不能查；false 时 {@link #message} 里写原因 */
    private boolean supported;

    /** 已装 GPON 业务板上的 PON 口总数 */
    private int totalPorts;

    /** 其中已经开启的个数 */
    private int enabledPorts;

    /** 是不是全都开了（没装板卡时为 false） */
    private boolean allEnabled;

    /** 没开的口，形如 0/1/3；全部开着时为空 */
    private List<String> disabledPorts = new ArrayList<>();

    /** 查不出来的原因：设备不支持、没采到板卡、或者命令执行失败 */
    private String message;
}
