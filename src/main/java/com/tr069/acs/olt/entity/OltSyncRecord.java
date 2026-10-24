package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OLT 连接与同步记录。
 *
 * <p>命令行回显是解析的唯一依据，型号/板卡解析不对时靠这里的原文改正则，
 * 因此原文留档（超长截断）。</p>
 */
@Data
@TableName("olt_sync_record")
public class OltSyncRecord {

    /** 动作：诊断测试 */
    public static final String ACTION_TEST = "TEST";

    /** 动作：同步 */
    public static final String ACTION_SYNC = "SYNC";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** olt_device.id，未保存的设备为 null */
    private Long oltId;

    /** TEST / SYNC */
    private String action;

    private Boolean success;

    private String message;

    /** 设备回显原文 */
    private String rawOutput;

    private Integer costMs;

    private String operator;

    private LocalDateTime createdAt;
}
