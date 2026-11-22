/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.316
 *
 */

package com.PilotACS.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** OLT 连接与同步记录 */
@Data
@TableName("olt_sync_record")
public class OltSyncRecord {

    /** 动作：诊断测试 */
    public static final String ACTION_TEST = "TEST";

    /** 动作：同步 */
    public static final String ACTION_SYNC = "SYNC";

    /** 动作：配置下发 */
    public static final String ACTION_CONFIG = "CONFIG";

    /** 动作：只读查询 */
    public static final String ACTION_QUERY = "QUERY";

    /** 动作：删除 */
    public static final String ACTION_DELETE = "DELETE";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备 id */
    private Long oltId;

    /** 动作类型 */
    private String action;

    private Boolean success;

    private String message;

    private Integer costMs;

    private String operator;

    private LocalDateTime createdAt;
}
