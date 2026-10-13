package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 型号语义字段到 CWMP 参数节点的映射。
 *
 * <p>业务模板只写「Internet_WAN.vlan_id」这类语义字段，下发时按型号查这张表换成
 * 该光猫真实的 CWMP 路径，业务模板因此不用为每种型号写死节点。</p>
 */
@Data
@TableName("model_param_mapping")
public class ModelParamMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** cpe_model.id */
    private Long modelId;

    /** 业务对象：Internet_WAN / VOIP_WAN / Bridge_WAN / WiFi_2G / WiFi_5G */
    private String bizObject;

    /** 业务字段：vlan_id / ssid / wpa_key ... */
    private String bizField;

    /** 真实参数路径，实例号用 {i} 占位 */
    private String cwmpPath;

    /** string / unsignedInt / boolean，用于推断下发报文的 xsi:type */
    private String valueType;

    /** 1 可写，0 只读 */
    private Integer writable;

    /** 1 表示缺失即阻断下发 */
    private Integer required;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
