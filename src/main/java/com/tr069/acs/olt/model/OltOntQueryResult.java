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
}
