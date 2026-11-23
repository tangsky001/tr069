/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.662
 *
 */

package com.PilotACS.common.constant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 权限码分组定义 */
public final class PermissionGroups {

    private PermissionGroups() {
    }

    // 模块名 -> 中文名
    private static final Map<String, String> MODULES = new LinkedHashMap<>();

    // 仅查看的模块
    private static final List<String> VIEW_ONLY = List.of("dashboard");

    static {
        MODULES.put("dashboard", "仪表盘");
        MODULES.put("template", "配置模板");
        MODULES.put("biz", "业务模板");
        MODULES.put("device", "设备管理");
        MODULES.put("file", "文件与固件");
        MODULES.put("worklist", "工单管理");
        MODULES.put("olt", "OLT管理");
        MODULES.put("sip", "电话交换");
        MODULES.put("system", "系统管理");
        MODULES.put("ops", "运维管理");
    }

    // 全部权限码
    public static List<String> allCodes() {
        List<String> codes = new java.util.ArrayList<>();
        MODULES.forEach((m, n) -> {
            codes.add(m + ":view");
            if (!VIEW_ONLY.contains(m)) {
                codes.add(m + ":update");
                codes.add(m + ":delete");
            }
        });
        return codes;
    }

    /** 权限组：{name, view, update, delete} */
    public static List<Map<String, Object>> groups() {
        List<Map<String, Object>> groups = new java.util.ArrayList<>();
        MODULES.forEach((m, n) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", n);
            g.put("view", m + ":view");
            g.put("update", VIEW_ONLY.contains(m) ? null : m + ":update");
            g.put("delete", VIEW_ONLY.contains(m) ? null : m + ":delete");
            groups.add(g);
        });
        return groups;
    }

    public static boolean exists(String code) {
        return allCodes().contains(code);
    }
}
