package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文件：固件与配置，供设备 Download 取用。 */
@Data
@TableName("cpe_file")
public class CpeFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** FIRMWARE 固件 / CONFIG 配置 / VENDOR 厂商文件。 */
    private String fileType;

    private String version;

    private Long size;

    private String sha256;

    /** 相对存储目录的文件路径，不对外暴露。 */
    private String storePath;

    private String remark;

    private LocalDateTime createdAt;
}
