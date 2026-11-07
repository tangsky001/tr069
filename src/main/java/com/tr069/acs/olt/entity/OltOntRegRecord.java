package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** ONT 注册记录：一台光猫一次注册的结果 */
@Data
@TableName("olt_ont_reg_record")
public class OltOntRegRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long oltId;

    private Long templateId;

    /** 模板名称快照：模板后来改了名，记录里还是当时那条 */
    private String templateName;

    /** 框/槽/端口，如 0/2/0 */
    private String fsp;

    /** ONT SN / MAC / LOID */
    private String ontSn;

    private String model;

    private String authMode;

    private Integer success;

    /** 来源：AUTO 自动 / MANUAL 手动 */
    private String source;

    private String command;

    private String message;

    private String operator;

    private LocalDateTime createdAt;
}
