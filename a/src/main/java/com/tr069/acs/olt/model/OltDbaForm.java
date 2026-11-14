package com.tr069.acs.olt.model;

import java.util.List;

/** 「新增 DBA 模板」表单的元数据：有哪些类型、每类填什么、取值上下限 */
public record OltDbaForm(boolean supported, List<OltDbaType> types, String defaultType,
                         int profileIdMin, int profileIdMax, int nameMax, String namePattern,
                         int bandwidthMin, int bandwidthMax) {
}
