package com.tr069.acs.biz.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 业务模板成员的保存结构 */
@Data
public class BizTemplateItemInput {

    private Long id;

    /** 配置模板 key */
    private String templateKey;

    private Integer itemOrder;

    /** 入参覆盖 */
    private Map<String, String> params = new LinkedHashMap<>();

    private String remark;
}
