/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.971
 *
 */

package com.PilotACS.olt.model;

import java.util.List;

/** 「新增 DBA 模板」表单的元数据：有哪些类型、每类填什么、取值上下限 */
public record OltDbaForm(boolean supported, List<OltDbaType> types, String defaultType,
                         int profileIdMin, int profileIdMax, int nameMax, String namePattern,
                         int bandwidthMin, int bandwidthMax) {
}
