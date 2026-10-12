package com.tr069.acs.biz.model;

import com.tr069.acs.biz.entity.BizTemplate;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务模板保存请求：模板本体 + 全量成员（保存时按模板 id 整体替换）。
 */
@Data
public class BizTemplateSaveRequest {

    private BizTemplate template;

    private List<BizTemplateItemInput> items = new ArrayList<>();
}
