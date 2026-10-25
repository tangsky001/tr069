package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 单条 ONT 按需查询的请求：在 GPON 端口视图下查一条 ONT 的光信息 / 版本 / 注册历史。
 */
@Data
public class OltOntQueryRequest {

    /** 动作：optical（光信息）/ version（版本）/ register（注册历史） */
    private String action;

    /** 框号，一般是 0 */
    private Integer frameNo;

    /** 槽位（业务板所在槽） */
    private Integer slotNo;

    /** PON 端口号 */
    private Integer portNo;

    /** ONT ID（端口内唯一） */
    private Integer ontId;
}
