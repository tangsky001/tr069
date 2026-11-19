/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.488
 *
 */

package com.PilotACS.group.config;

import java.util.function.Supplier;

/** 分组表域上下文：OLT 与光猫（ONU）各自一套分组表，请求线程内用 ThreadLocal */
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

    /** 物理表名映射：只动分组三件套（树 / 成员归属 / ACL）， */
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
