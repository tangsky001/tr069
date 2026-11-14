package com.tr069.acs.olt.model;

import java.util.List;

/** DBA 模板的一种类型（华为 {@code dba-profile add} 的 type1~type5） */
public record OltDbaType(String code, String label, List<String> fields) {
}
