package com.PilotACS.template.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置意图模板：只描述「要开通成什么样」的意图（IP 协议 / VLAN / 绑定端口等），
 * 不含任何型号参数路径；下发时由意图下发服务按设备数据模型翻译成真实 CWMP 参数。
 */
@Data
@TableName("config_template")
@Schema(description = "配置意图模板：意图字段以 JSON 存档，下发时按机型翻译")
public class ConfigTemplate {

    @TableId(type = IdType.AUTO)
    @Schema(description = "模板 id")
    private Long id;

    @Schema(description = "模板名称（同类型下唯一）")
    private String name;

    @Schema(description = "模板类型：WAN_DHCP / WAN_PPPOE / WAN_STATIC / WAN_BRIDGE / WIFI_24G / WIFI_58G / SIP / OTHER")
    private String category;

    @Schema(description = "模板分组，页面按分组归类展示")
    private String groupName;

    @Schema(description = "是否启用，停用的模板不允许下发")
    private Boolean enabled;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "意图字段 JSON：key 为字段定义里的 key（如 vlan_id / service_list）")
    private String fields;

    @Schema(description = "增量业务：true=下发只新增不清理存量连接；仅 WAN 类有意义")
    private Boolean incremental;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
