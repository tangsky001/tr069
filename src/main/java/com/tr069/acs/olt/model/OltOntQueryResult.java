package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 单条 ONT 按需查询的结果（光信息 / 版本 / 注册历史）。
 *
 * <p>回显是块状 key-value（一条 ONT 一块，注册历史可能有多块），解析成 blocks 给页面
 * 直接铺表格；原始回显也一并返回，解析漏了字段时页面还能看回显。</p>
 */
@Data
public class OltOntQueryResult {

    /** 实际下发的命令，如 display ont optical-info 0 1 */
    private String command;

    /** 命令原始回显 */
    private String raw;

    /** 解析出来的块，每块是一段「键 -> 值」（注册历史一块就是一次上/下线） */
    private List<LinkedHashMap<String, String>> blocks = new ArrayList<>();

    /** display ont info 的关键字段（IP / 线路模板 / 业务模板 / 掉线原因），只有查 info 时带 */
    private OltOntInfo ontInfo;

    /** 这条 ONT 的业务流（service-port 行），只有查 config（display current-configuration ont）时带 */
    private List<OltServicePort> servicePorts = new ArrayList<>();
}
