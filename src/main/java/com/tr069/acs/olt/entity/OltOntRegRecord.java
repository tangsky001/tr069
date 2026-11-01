package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ONT 注册记录：一台光猫一次注册的结果。
 *
 * <p>自动注册是后台跑的，页面上点一下才知道发生了什么，所以每次尝试都留一条：
 * 成了的能看出套了哪个模板，没成的把设备回的原因带上——
 * 「模板匹配不上」和「设备拒绝了」在页面上必须能分清。</p>
 */
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
