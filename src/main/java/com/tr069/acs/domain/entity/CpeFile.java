package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/** 文件：固件与配置，供设备 Download 取用 */
@Data
@TableName("cpe_file")
public class CpeFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** FIRMWARE 固件 / CONFIG 配置 / VENDOR 厂商文件 */
    private String fileType;

    private String version;

    private Long size;

    private String sha256;

    /** 相对存储目录的文件路径，不对外暴露 */
    @JsonIgnore
    private String storePath;

    private String remark;

    private LocalDateTime createdAt;

    /** 大小文本：「512 B」「3.4 KB」「12.0 MB」，页面不再做单位换算 */
    @TableField(exist = false)
    private String sizeText;

    /** 摘要前 16 位 + 省略号：台账上只需认出是哪个包，全长 64 位挤不进列 */
    @TableField(exist = false)
    private String shortSha256;

    /** 类型中文：固件 / 配置 / 厂商文件 */
    @TableField(exist = false)
    private String fileTypeText;

    /** 类型标签色：固件橙、其余灰 */
    @TableField(exist = false)
    private String fileTypeLevel;
}
