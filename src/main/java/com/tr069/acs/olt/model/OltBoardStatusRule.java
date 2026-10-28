package com.tr069.acs.olt.model;

import java.util.List;

/**
 * 板卡状态的翻译规则：设备回的英文状态 → 页面的中文说法与告警等级。
 *
 * <p>顺序有意义：从上往下第一条命中的生效（例如 {@code Active_normal} 先命中
 * 「激活」而不是「正常」）。以前这套正则写在页面里，改文案要动前端；
 * 现在服务端直接把 {@code statusText} / {@code statusLevel} 算好给页面。</p>
 *
 * @param keywords 状态文本（已转小写）里包含任一关键字即命中
 * @param label    中文说法
 * @param level    告警等级：success / warning / danger
 */
public record OltBoardStatusRule(List<String> keywords, String label, String level) {
}
