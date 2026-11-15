/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.488
 *
 */

package com.PilotACS.group.config;

import java.util.function.Supplier;

/**
 * 分组表域上下文：OLT 与光猫（ONU）各自一套分组表，请求线程内用 ThreadLocal
 * 标记当前操作落在哪个域，由 MybatisPlusConfig 的动态表名拦截器把
 * {@code res_group / res_group_member / res_acl} 映射到
 * {@code olt_* / onu_*} 物理表。
 *
 * <p>规则：未设置（内部调用、老接口）默认 OLT，与历史行为一致；
 * ONU 语义的方法必须显式 {@link #with} 包裹。</p>
 */
public final class GroupScopeContext {

    public static final String OLT = "OLT";
    public static final String ONU = "ONU";

    private static final ThreadLocal<String> SCOPE = new ThreadLocal<>();

    private GroupScopeContext() {
    }

    /** 归一：空/未知 → OLT */
    public static String normalize(String scope) {
        return ONU.equalsIgnoreCase(scope) ? ONU : OLT;
    }

    /** 当前域（未设置 = OLT） */
    public static String get() {
        return normalize(SCOPE.get());
    }

    /** 设置当前域；用完必须恢复（with 已处理） */
    public static void set(String scope) {
        SCOPE.set(normalize(scope));
    }

    /** 清除（线程池复用防串域） */
    public static void clear() {
        SCOPE.remove();
    }

    /** 在指定域内执行，结束后恢复外层域（支持重入） */
    public static <T> T with(String scope, Supplier<T> action) {
        String prev = SCOPE.get();
        set(scope);
        try {
            return action.get();
        } finally {
            if (prev == null) {
                clear();
            } else {
                SCOPE.set(prev);
            }
        }
    }

    /** 在指定域内执行（无返回值） */
    public static void with(String scope, Runnable action) {
        with(scope, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 物理表名映射：只动分组三件套（树 / 成员归属 / ACL），
     * 其余表原样返回。未设置域时默认 OLT（老接口行为不变）。
     */
    public static String mapTable(String tableName) {
        String prefix = ONU.equals(get()) ? "onu_" : "olt_";
        return switch (tableName) {
            case "res_group" -> prefix + "res_group";
            case "res_group_member" -> prefix + "res_group_member";
            case "res_acl" -> prefix + "res_acl";
            default -> tableName;
        };
    }
}
