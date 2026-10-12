package com.tr069.acs.biz.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务模板成员的保存结构。
 *
 * <p>与实体 {@link com.tr069.acs.biz.entity.BizTemplateItem} 的区别：params 这里是对象，
 * 落库时序列化为 JSON 字符串，避免前端要把对象手动转成字符串。</p>
 */
@Data
public class BizTemplateItemInput {

    private Long id;

    /** 配置模板 key。 */
    private String templateKey;

    private Integer itemOrder;

    /** 入参覆盖。 */
    private Map<String, String> params = new LinkedHashMap<>();

    private String remark;
}
