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

    /**
     * 业务模块，中文名即日志前缀；日志统一输出为「[系统]→[模块]→[子模块] : 内容」。
     * label 是一级模块，child 是可选二级子模块（只有 OLT 这类大模块需要细分）。
     */
    public enum Module {
        /** 登录认证 */
        AUTH("认证", null),
        /** 用户管理 */
        USER("用户", null),
        /** 角色管理 */
        ROLE("角色", null),
        /** 内网穿透隧道连接 */
        TUNNEL("隧道连接", null),
        /** 运维管理 */
        OPS("运维", null),
        /** 在线升级 */
        UPGRADE("升级", null),
        /** 系统杂项 */
        SYSTEM("系统", null),
        /** 启动自检 */
        CHECK("自检", null),
        /** 机器指纹 */
        FINGERPRINT("指纹", null),
        /** 全局异常 */
        EXCEPTION("异常", null),
        /** OLT 一级模块（会话池、控制器等未细分的） */
        OLT("OLT", null),
        /** OLT → 光猫注册：自动注册、注册后配置（TR069 模板 / native-vlan / 业务流）下发 */
        OLT_REG("OLT", "光猫注册"),
        /** OLT → 采集：Telnet 连接、命令回显查询、状态刷新上报 */
        OLT_COLLECT("OLT", "采集"),
        /** 资源分组管理 */
        GROUP("分组", null),
        /** 设备/分组级 ACL */
        ACL("设备权限", null),
        /** CPE 光猫一级模块（配置初始化、缓存、网络层等未细分的） */
        CPE("光猫", null),
        /** 光猫 → 上线：Inform 上报、会话建立、SOAP 报文入口 */
        CPE_ONLINE("光猫", "上线"),
        /** 光猫 → 离线：会话结束、会话超时收尾、会话过期、离线标记 */
        CPE_OFFLINE("光猫", "离线"),
        /** 光猫 → 下发：任务派发/执行、参数下发、反向连接、初始化开通、参数树探测 */
        CPE_PUSH("光猫", "下发"),
        /** 光猫 → 工单：工单台账初始化等 */
        CPE_WORKLIST("光猫", "工单");

        private final String label;
        private final String child;

        Module(String label, String child) {
            this.label = label;
            this.child = child;
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

    // 统一前缀：[系统]→[模块]（有子模块时）[系统]→[模块]→[子模块]，后接 ": " 分隔内容
    private static String prefix(Module module) {
        StringBuilder sb = new StringBuilder("[系统]→[").append(module.label).append(']');
        if (module.child != null) {
            sb.append("→[").append(module.child).append(']');
        }
        return sb.append(" : ").toString();
    }

    // kv 两两成对：key1, value1, key2, value2 ...；key 为 null 的对（调用方按条件拼参的占位）跳过不输出
    private static String format(Module module, String action, Object... kv) {
        StringBuilder sb = new StringBuilder(prefix(module)).append(action);
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (kv[i] == null) {
                    continue;
                }
                sb.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
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
