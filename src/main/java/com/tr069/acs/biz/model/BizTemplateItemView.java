package com.tr069.acs.biz.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务模板成员的展示结构：数据库里的成员 + 配置模板的实时信息。
 *
 * <p>配置模板是文件，随时可能被删改，因此模板名、类型、能否下发这些属性
 * 在查询时从注册表补全，不冗余进数据库，避免改名后展示的是旧值。</p>
 */
@Data
public class BizTemplateItemView {

    private Long id;

    private Long templateId;

    /** 配置模板 key。 */
    private String templateKey;

    private Integer itemOrder;

    /** 入参覆盖。 */
    private Map<String, String> params = new LinkedHashMap<>();

    private String remark;

    /** 配置模板名（key 的最后一段）。 */
    private String templateName;

    /** 模板类型码，如 WAN_DHCP。 */
    private String category;

    /** 模板类型中文名。 */
    private String categoryLabel;

    /** 模板说明摘要。 */
    private String doc;

    /** 配置模板是否还存在且解析正常。 */
    private boolean available;

    /** 执行步骤是否已迁移，未迁移的模板下发会被引擎拒绝。 */
    private boolean executable;

    /** 不可用/不可执行的原因。 */
    private String issue;
}
