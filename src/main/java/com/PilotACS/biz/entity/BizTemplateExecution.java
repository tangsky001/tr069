package com.PilotACS.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次业务模板整包下发记录：页面对它轮询进度 */
@Data
@TableName("biz_template_execution")
@Schema(description = "业务模板整包下发记录")
public class BizTemplateExecution {

    @TableId(type = IdType.AUTO)
    @Schema(description = "执行记录 id")
    private Long id;

    @Schema(description = "业务模板 id")
    private Long templateId;

    @Schema(description = "冗余保存业务模板名，模板删除后仍可追溯")
    private String templateName;

    @Schema(description = "设备序列号（归一后）")
    private String sn;

    @Schema(description = "状态：running / success / partial / fail")
    private String status;

    @Schema(description = "操作人")
    private String username;

    @Schema(description = "成员总数")
    private Integer totalCount;

    @Schema(description = "已结束成员数")
    private Integer finishedCount;

    @Schema(description = "成功成员数")
    private Integer successCount;

    @Schema(description = "失败成员数")
    private Integer failCount;

    @Schema(description = "正在执行的成员配置模板 key")
    private String currentKey;

    @Schema(description = "每个成员的执行结果，JSON 数组")
    private String result;

    @Schema(description = "中断原因")
    private String errorMessage;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "结束时间")
    private LocalDateTime finishedAt;
}
