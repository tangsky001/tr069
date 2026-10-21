package com.tr069.acs.common.log;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 一条日志的统一载体。
 *
 * <p>字段刻意做成<b>固定且扁平</b>：日志先以本对象序列化成 JSON 进 Redis 队列，
 * 再由消费线程还原并按固定列宽排版写盘。所以它同时是「传输格式」和「排版格式」，
 * 两个用途共用一份定义，避免两边字段漂移。</p>
 *
 * <p>可变业务字段一律走 {@link #getExtra()} 或专用列，不要再加新列：
 * 新列会让每一行都长一截，却只有一个业务用得到，得不偿失。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件发生时间(毫秒)。
     *
     * <p>取的是<b>产生时刻</b>而不是消费时刻：队列积压时两者可能差出很多秒，
     * 排查「设备什么时候离线的」只能认前者。</p>
     */
    private long ts = System.currentTimeMillis();

    /**
     * 级别。
     */
    private LogLevel level = LogLevel.INFO;

    /**
     * 分类。
     */
    private LogCategory category = LogCategory.SYSTEM;

    /**
     * 产生日志的线程名，用于还原并发时序。
     */
    private String thread;

    /**
     * 设备序列号。全站约八成日志能落到这一列，缺失时统一填 {@code -}。
     */
    private String sn;

    /**
     * 业务键：任务号、指令名、模板标识等，取决于分类。
     */
    private String bizKey;

    /**
     * 结果：成功 / 失败 / 未知。
     */
    private String result;

    /**
     * 结果码：CWMP FaultCode、数据库错误码等，无则为空。
     */
    private String code;

    /**
     * 正文。已排版好的一行纯文本，不要再嵌换行。
     */
    private String message;

    /**
     * 补充字段，按 {@code k=v} 追加在正文之后。
     */
    private java.util.LinkedHashMap<String, String> extra;
}
