package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备标签：按型号 / 运营商之外的维度分群。 */
@Data
@TableName("cpe_device_tag")
public class CpeDeviceTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sn;

    private String tag;

    private LocalDateTime createdAt;
}
