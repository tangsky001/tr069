package com.PilotACS.worklist.service;

import com.PilotACS.template.model.TemplateFieldDefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 工单变量定义 */
public final class WorklistVariables {

    /** 房间号没登记时的兜底值 */
    public static final String DEFAULT_ROOM = "0000";

    private WorklistVariables() {
    }

    /** 变量清单 */
    public static List<Map<String, Object>> forCategory(String category) {
        String cat = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> def : TemplateFieldDefs.FIELD_DEFS) {
            if (!cat.equalsIgnoreCase(String.valueOf(def.get("category")))) {
                continue;
            }
            boolean secret = "password".equals(String.valueOf(def.get("inputType")));
            Object dv = def.get("defaultValue");
            Map<String, Object> var = new LinkedHashMap<>();
            var.put("name", String.valueOf(def.get("key")));
            var.put("label", String.valueOf(def.get("label")));
            var.put("secret", secret);
            var.put("example", dv == null ? "" : String.valueOf(dv));
            out.add(var);
        }
        return out;
    }

    /** 未填变量补模板默认值，空值键移除 */
    public static Map<String, String> applyDefaults(String category, Map<String, String> values) {
        Map<String, String> filled = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        String cat = category == null ? "" : category.trim();
        for (Map<String, Object> def : TemplateFieldDefs.FIELD_DEFS) {
            if (!String.valueOf(def.get("category")).equalsIgnoreCase(cat)) {
                continue;
            }
            String key = String.valueOf(def.get("key"));
            String cur = filled.get(key);
            if (cur != null && cur.isBlank()) {
                filled.remove(key);
            }
        }
        return filled;
    }

    /** 占位替换：%room% → 房间号，%desc% → 光猫备注 */
    public static String resolve(String template, String room, String desc) {
        if (template == null || template.indexOf('%') < 0) {
            return template;
        }
        return template
                .replace("%room%", room == null || room.isBlank() ? DEFAULT_ROOM : room)
                .replace("%desc%", desc == null ? "" : desc);
    }
}
