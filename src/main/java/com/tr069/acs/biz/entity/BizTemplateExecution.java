package com.tr069.acs.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次业务模板下发记录。
 *
 * <p>整包下发是异步的：接口立即返回本记录 id，页面靠它轮询进度。
 * 每个成员会各自生成一条下发任务（{@code device_task} 表），明细写在 result 里。</p>
 */
@Data
@TableName("biz_template_execution")
public class BizTemplateExecution {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    /** 冗余保存业务模板名，模板删除后仍可追溯。 */
    private String templateName;

    private String sn;

    /** running / success / partial / fail。 */
    private String status;

    private String username;

    private Integer totalCount;

    private Integer finishedCount;

    private Integer successCount;

    private Integer failCount;

    /** 正在执行的成员配置模板 key。 */
    private String currentKey;

    /** 每个成员的执行结果，JSON 数组。 */
    private String result;

    /** 中断原因。 */
    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
