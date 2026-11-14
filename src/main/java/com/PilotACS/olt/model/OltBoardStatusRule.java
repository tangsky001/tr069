/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.733
 *
 */

package com.PilotACS.olt.model;

import java.util.List;

/** 板卡状态的翻译规则：设备回的英文状态 → 页面的中文说法与告警等级 */
public record OltBoardStatusRule(List<String> keywords, String label, String level) {
}
