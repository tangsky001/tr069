package com.tr069.acs.common.log;

/**
 * 日志级别。
 *
 * <p>与 SLF4J 语义一致，但在这里独立定义：
 * {@link LogRecord} 要以 JSON 形式进 Redis 队列，枚举比字符串更不容易写错，
 * 且级别只能取这四个，杜绝「有人随手 log.info 出一段带颜色转义的控制台文本」。</p>
 */
public enum LogLevel {

    /**
     * 排障用的逐条跟踪，生产环境默认被 {@code acs.log.level} 过滤掉。
     */
    DEBUG,

    /**
     * 正常业务事件：上线、离线、上报、指令结果。
     */
    INFO,

    /**
     * 可恢复的异常，不影响本次请求但需要人看一眼。
     */
    WARN,

    /**
     * 影响业务结果的失败。
     */
    ERROR
}
