package com.tr069.acs.api.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 权限点清单，以及「请求 → 权限点」的映射。
 *
 * <p>权限点不落库：它是代码里真实存在的功能，用户删掉一个权限点、代码还在判它，
 * 只会让功能变成谁也点不动的死按钮。所以这里是一份常量清单，
 * 接口把清单吐给前端去勾选，勾选结果存在 {@code sys_role_permission} 里。</p>
 *
 * <p>判定规则：{@code GET} 是看，要 {@code 模块:view}；写（POST / PUT / DELETE）
 * 要 {@code 模块:manage}。模块由 URL 前缀定，所以不用给每个接口逐个加注解。</p>
 */
public final class PermissionCodes {

    /** 系统管理员：拥有全部权限，且不允许改它的权限。 */
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    /**
     * 这条路径没登记过模块。
     *
     * <p>拿到它不是放行，是要拒绝：新接口漏登记时，宁可它打不开、日志里报出来，
     * 也不能悄悄变成「所有人都能访问」。这是默认拒绝，不是默认放行。</p>
     */
    public static final String UNMAPPED = "unmapped";

    /** 运维管理员：默认建出来的第二个角色，权限可自定义。 */
    public static final String ROLE_OPS_ADMIN = "OPS_ADMIN";

    private static final String VIEW = ":view";

    private static final String MANAGE = ":manage";

    /** 模块名 → 展示名。顺序就是前端勾选页的展示顺序。 */
    private static final List<String[]> MODULES = List.of(
            new String[]{"dashboard", "首页概览"},
            new String[]{"device", "设备台账"},
            new String[]{"template", "配置模板"},
            new String[]{"biz", "业务模板"},
            new String[]{"model", "型号适配"},
            new String[]{"worklist", "工单管理"},
            new String[]{"olt", "OLT 管理"},
            new String[]{"file", "文件与固件"},
            new String[]{"tag", "设备标签"},
            new String[]{"system", "系统管理"});

    /** 免检：登进来就是为了用这几个，再问一次权限就成了自己挡自己。 */
    private static final List<String> FREE_PASS = List.of(
            "/api/user/login", "/api/user/info", "/api/user/logout");

    /** URL 前缀 → 模块。长前缀在前，先命中先算。 */
    private static final List<String[]> PREFIXES = List.of(
            new String[]{"/api/dashboard", "dashboard"},
            new String[]{"/api/device", "device"},
            // 单独下发任务的口子，与 /api/device/{sn}/rpc 是同一件事，都算设备的写
            new String[]{"/api/rpc", "device"},
            new String[]{"/api/config-templates", "template"},
            new String[]{"/api/biz-template", "biz"},
            new String[]{"/api/models", "model"},
            new String[]{"/api/worklist", "worklist"},
            new String[]{"/api/olt", "olt"},
            new String[]{"/api/files", "file"},
            new String[]{"/api/device-tags", "tag"},
            new String[]{"/api/system", "system"});

    private PermissionCodes() {
    }

    /** 全部权限点，按模块分组，给前端勾选页用。 */
    public static List<Map<String, Object>> groups() {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (String[] module : MODULES) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("module", module[0]);
            group.put("name", module[1]);
            group.put("view", module[0] + VIEW);
            group.put("manage", module[0] + MANAGE);
            groups.add(group);
        }
        return groups;
    }

    /** 全部权限点的扁平清单。 */
    public static List<String> all() {
        List<String> codes = new ArrayList<>();
        for (String[] module : MODULES) {
            codes.add(module[0] + VIEW);
            codes.add(module[0] + MANAGE);
        }
        return codes;
    }

    /** 这个权限点是不是清单里的，防止被人塞进一个不存在的权限。 */
    public static boolean known(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String lower = code.trim().toLowerCase(Locale.ROOT);
        return all().stream().anyMatch(item -> item.equals(lower));
    }

    /**
     * 请求需要哪个权限点。
     *
     * <p>认不出模块的请求分两种：免检名单里的返回 null（比如 {@code /api/user/info}，
     * 登进来就能看自己是谁）；其余返回 {@link #UNMAPPED}，那是漏登记的接口，
     * 调用方要拦掉，不能当成「不需要权限」。</p>
     */
    public static String required(String method, String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String path = uri;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String module = null;
        for (String[] prefix : PREFIXES) {
            if (path.equals(prefix[0]) || path.startsWith(prefix[0] + "/")) {
                module = prefix[1];
                break;
            }
        }
        if (module == null) {
            // 免检名单里的放行，其它一律报未登记，让调用方拦掉
            return FREE_PASS.contains(path) ? null : UNMAPPED;
        }
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
        return module + (read ? VIEW : MANAGE);
    }
}
