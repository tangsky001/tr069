/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.252
 *
 */

package com.PilotACS.common.security;

/** 当前登录用户上下文 */
public final class UserContext {
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
