package com.tr069.acs.common.log;

import java.util.LinkedHashMap;

/**
 * 日志条目构造器：链式拼装一条日志，最后由 {@code debug/info/warn/error} 收口入队。
 *
 * <pre>
 * coreLog.entry(LogCategory.DEVICE).sn(sn).bizKey(rpc).result(ok).info("指令下发完成");
 * </pre>
 *
 * <p>只提供收口方法而不再暴露 {@code setXxx}，是为了强制「每条日志都必须有级别」：
 * 忘了定级别的日志就没法写出来，也就不可能悄悄以 INFO 混进核心日志文件。</p>
 */
public class LogEntry {

    private final LogQueueService logQueue;

    private final LogProperties properties;

    private final LogRecord record = new LogRecord();

    LogEntry(LogQueueService logQueue, LogProperties properties, LogCategory category) {
        this.logQueue = logQueue;
        this.properties = properties;
        this.record.setCategory(category == null ? LogCategory.SYSTEM : category);
        this.record.setThread(Thread.currentThread().getName());
    }

    /**
     * 设备序列号。
     */
    public LogEntry sn(String sn) {
        record.setSn(sn);
        return this;
    }

    /**
     * 业务键：任务号、指令名、模板标识等，由分类决定含义。
     */
    public LogEntry bizKey(String bizKey) {
        record.setBizKey(bizKey);
        return this;
    }

    /**
     * 结果，统一排版成「成功 / 失败」。
     */
    public LogEntry result(boolean success) {
        record.setResult(success ? "成功" : "失败");
        return this;
    }

    /**
     * 结果码：CWMP FaultCode、数据库错误码等。
     */
    public LogEntry code(String code) {
        record.setCode(code);
        return this;
    }

    /**
     * 补充字段，最终以 {@code k=v} 追加在行尾。仅用于确实没有合适专用列的信息。
     */
    public LogEntry with(String key, Object value) {
        if (record.getExtra() == null) {
            record.setExtra(new LinkedHashMap<>());
        }
        record.getExtra().put(key, value == null ? null : String.valueOf(value));
        return this;
    }

    /**
     * 以 DEBUG 级别写出。
     */
    public void debug(String message) {
        submit(LogLevel.DEBUG, message);
    }

    /**
     * 以 INFO 级别写出。
     */
    public void info(String message) {
        submit(LogLevel.INFO, message);
    }

    /**
     * 以 WARN 级别写出。
     */
    public void warn(String message) {
        submit(LogLevel.WARN, message);
    }

    /**
     * 以 ERROR 级别写出。
     */
    public void error(String message) {
        submit(LogLevel.ERROR, message);
    }

    /**
     * 级别过滤与入队。低于 {@code acs.log.level} 的直接丢弃，不做序列化也不进队列。
     */
    private void submit(LogLevel level, String message) {
        if (level.ordinal() < properties.getLevel().ordinal()) {
            return;
        }
        record.setLevel(level);
        record.setMessage(message);
        logQueue.offer(record);
    }

    /**
     * 仅供同包代码与测试断言使用，返回将要入队的记录快照。
     */
    LogRecord peek() {
        return record;
    }
}
