package com.tr069.acs.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 业务模板成员：引用一张配置模板，并可覆盖它的入参默认值。
 */
@Data
@TableName("biz_template_item")
public class BizTemplateItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属业务模板。 */
    private Long templateId;

    /** 配置模板 key，如 CT/v4.0/business/ADSL_2LAN/WAN_DHCP。 */
    private String templateKey;

    /** 执行顺序，小的先执行。 */
    private Integer itemOrder;

    /**
     * 入参覆盖，JSON 对象，如 {"VLAN_ID":"41"}。
     * 下发时与页面传入参数合并，页面传入的优先。
     */
    private String params;

    /** 成员备注。 */
    private String remark;
}
