package com.tr069.acs.olt.model;

import java.util.List;

/**
 * 「新增 DBA 模板」表单的元数据：有哪些类型、每类填什么、取值上下限。
 *
 * <p>页面不再保存这些约束——模板 ID 范围、名称字符集、带宽范围、五类模板各要哪些字段，
 * 以前服务端校验一份、页面提示一份，改了这边忘那边就会「页面说能填、下发被拒」。</p>
 *
 * @param supported      这台设备支不支持 DBA 模板
 * @param types          type1~type5 及各自要填的字段
 * @param defaultType    默认选中的类型（现场基本都用「最大带宽」）
 * @param profileIdMin   模板 ID 下限
 * @param profileIdMax   模板 ID 上限
 * @param nameMax        模板名最大长度
 * @param namePattern    模板名的正则（页面直接拿来校验）
 * @param bandwidthMin   带宽下限(kbps)
 * @param bandwidthMax   带宽上限(kbps)
 */
public record OltDbaForm(boolean supported, List<OltDbaType> types, String defaultType,
                         int profileIdMin, int profileIdMax, int nameMax, String namePattern,
                         int bandwidthMin, int bandwidthMax) {
}
