package com.tr069.acs.biz.model;

import com.tr069.acs.biz.entity.BizTemplate;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务模板详情：模板本体 + 成员列表。
 */
@Data
public class BizTemplateDetail {

    private BizTemplate template;

    private List<BizTemplateItemView> items = new ArrayList<>();

    public static BizTemplateDetail of(BizTemplate template) {
        BizTemplateDetail detail = new BizTemplateDetail();
        detail.setTemplate(template);
        return detail;
    }
}
