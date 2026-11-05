package com.tr069.acs.olt.model;

import lombok.Data;

/** 新增 DBA 模板的表单（页面上「新增DBA模板」弹窗） */
@Data
public class OltDbaProfileRequest {

    /** 模板 ID，10-512，OLT 上不能重复 */
    private Integer profileId;

    /** 模板名，数字、字母及下划线 */
    private String name;

    /** type1~type5 */
    private String type;

    /** 固定带宽（kbps），type1 / type5 */
    private Integer fixKbps;

    /** 保证带宽（kbps），type2 / type3 / type5 */
    private Integer assureKbps;

    /** 最大带宽（kbps），type3 / type4 / type5 */
    private Integer maxKbps;
}
