package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务对象到 CWMP 对象路径的映射，供 AddObject / DeleteObject 使用。
 *
 * <p>WAN 类业务需要先清理旧连接再创建新实例，父对象与对象名由这张表给出；
 * WiFi 射频一般不新建实例，用 EXISTING 模式复用设备上的实例号。</p>
 */
@Data
@TableName("model_object_mapping")
public class ModelObjectMapping {

    /** 新建实例：先清旧实例再 AddObject。 */
    public static final String MODE_ADD = "ADD";

    /** 复用现有实例：不创建，直接往已有实例号写参数。 */
    public static final String MODE_EXISTING = "EXISTING";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** cpe_model.id */
    private Long modelId;

    /** 业务对象：Internet_WAN / VOIP_WAN / Bridge_WAN / WiFi_2G / WiFi_5G */
    private String bizObject;

    /** 连接设备路径，如 InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1 */
    private String parentPath;

    /** AddObject 的对象名，如 ...WANConnectionDevice.1.WANIPConnection. */
    private String objectName;

    /** {@link #MODE_ADD} / {@link #MODE_EXISTING} */
    private String instanceMode;

    /** 1 表示创建前先删除该对象下的旧实例 */
    private Integer deleteBefore;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
