package com.tr069.acs.olt.model;

import java.util.List;

/** 板卡状态的翻译规则：设备回的英文状态 → 页面的中文说法与告警等级 */
public record OltBoardStatusRule(List<String> keywords, String label, String level) {
}
