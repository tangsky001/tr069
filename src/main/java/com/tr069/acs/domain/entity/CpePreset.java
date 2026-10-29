package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备预设：上报后按条件自动收敛设备配置。 */
@Data
@TableName("cpe_preset")
public class CpePreset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 小的先执行。 */
    private Integer priority;

    private Integer enabled;

    /** 匹配条件 JSON，为空表示命中所有设备。 */
    private String conditionsJson;

    /** 动作 JSON。 */
    private String actionsJson;

    /** 距上次执行超过该小时数才重跑，0 表示每次上报都跑。 */
    private Integer reprovisionIntervalHours;

    /** BOOTSTRAP 时是否跳过节流强制重跑。 */
    private Integer forceOnBootstrap;

    /** 内置预设不允许删除。 */
    private Integer builtIn;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
