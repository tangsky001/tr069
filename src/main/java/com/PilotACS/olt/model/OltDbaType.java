/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.419
 *
 */

package com.PilotACS.olt.model;

import java.util.List;

/** DBA 模板的一种类型（华为 {@code dba-profile add} 的 type1~type5） */
public record OltDbaType(String code, String label, List<String> fields) {
}
