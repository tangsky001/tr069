/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.863
 *
 */

package com.PilotACS.common.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 打印专用模块：全项目日志统一从这里输出，业务类 */
public final class Logs {

    /** 业务模块，中文名即日志前缀 */
    public enum Module {
        /** 登录认证 */
        AUTH("认证"),
        /** 用户管理 */
        USER("用户"),
        /** 角色管理 */
        ROLE("角色"),
        /** 内网穿透隧道 */
        TUNNEL("隧道"),
        /** 运维管理 */
        OPS("运维"),
        /** 在线升级 */
        UPGRADE("升级"),
        /** 系统杂项 */
        SYSTEM("系统"),
        /** 启动自检 */
        CHECK("自检"),
        /** 机器指纹 */
        FINGERPRINT("指纹"),
        /** 全局异常 */
        EXCEPTION("异常"),
        /** OLT（Telnet 采集） */
        OLT("OLT"),
        /** 资源分组管理 */
        GROUP("分组"),
        /** 设备/分组级 ACL */
        ACL("设备权限"),
        /** CPE 光猫 CWMP（TR-069 会话/参数树/机型模板） */
        CPE("光猫");

        private final String label;

        Module(String label) {
            this.label = label;
        }
    }

    private static final Map<Class<?>, Logger> LOGGERS = new ConcurrentHashMap<>();
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private Logs() {
    }

    public static void info(Module module, String action, Object... args) {
        // 消息里带 {} 占位符时按 SLF4J 占位符风格输出
        if (isPlaceholderStyle(action, args)) {
            logger().info(prefix(module) + action, args);
        } else {
            logger().info(format(module, action, args));
        }
    }

    public static void warn(Module module, String action, Object... args) {
        if (isPlaceholderStyle(action, args)) {
            logger().warn(prefix(module) + action, args);
        } else {
            logger().warn(format(module, action, args));
        }
    }

    public static void warn(Module module, String action, Throwable t, Object... kv) {
        logger().warn(format(module, action, kv), t);
    }

    public static void error(Module module, String action, Object... args) {
        if (isPlaceholderStyle(action, args)) {
            logger().error(prefix(module) + action, args);
        } else {
            logger().error(format(module, action, args));
        }
    }

    public static void error(Module module, String action, Throwable t, Object... kv) {
        if (isPlaceholderStyle(action, kv)) {
            Object[] all = new Object[kv.length + 1];
            System.arraycopy(kv, 0, all, 0, kv.length);
            all[kv.length] = t;
            logger().error(prefix(module) + action, all);
        } else {
            logger().error(format(module, action, kv), t);
        }
    }

    public static void debug(Module module, String action, Object... args) {
        if (isPlaceholderStyle(action, args)) {
            logger().debug(prefix(module) + action, args);
        } else {
            logger().debug(format(module, action, args));
        }
    }

    // 消息含 {} 且有参数 → SLF4J 占位符风格
    private static boolean isPlaceholderStyle(String action, Object... args) {
        return action != null && action.contains("{}") && args != null && args.length > 0;
    }

    private static String prefix(Module module) {
        return '[' + module.label + "] ";
    }

    // kv 两两成对：key1, value1, key2, value2 ...；key 为 null 的对（调用方按条件拼参的占位）跳过不输出
    private static String format(Module module, String action, Object... kv) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(module.label).append("] ").append(action);
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (kv[i] == null) {
                    continue;
                }
                sb.append('，').append(kv[i]).append('=').append(kv[i + 1]);
            }
        }
        return sb.toString();
    }

    // logger 名取真实调用类，日志里能看到来源类
    private static Logger logger() {
        Class<?> caller = WALKER.getCallerClass();
        return LOGGERS.computeIfAbsent(caller, LoggerFactory::getLogger);
    }
}
