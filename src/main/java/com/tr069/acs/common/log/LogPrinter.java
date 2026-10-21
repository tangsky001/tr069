package com.tr069.acs.common.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 日志排版与落盘。
 *
 * <p>全站唯一把 {@link LogRecord} 变成一行文本的地方，格式在这里定死：</p>
 * <pre>
 * 设备台账 | sn=HWTC1234 | key=- | 结果=成功 | 首次上报入库 | 线程=nio-8080-exec-1
 * </pre>
 *
 * <p>格式约定（改动前先想清楚，现有脚本和 grep 都依赖它）：</p>
 * <ul>
 *   <li>字段之间一律用 {@code " | "} 分隔，与 {@link AcsCoreLog} 历史格式保持一致；</li>
 *   <li>固定列顺序：分类 → sn → key → 结果 → [code] → 正文 → 线程；</li>
 *   <li>空值统一填 {@code -}，保证每行列数一致，切列工具可以直接用；</li>
 *   <li>正文里的换行与竖线一律转义，防止一条日志伪装成多条。</li>
 * </ul>
 *
 * <p>输出到 {@code ACS_CORE} logger，由 logback-spring.xml 负责落盘与级别通道分流。
 * 这里只做排版，不做级别过滤（过滤在生产端入队前已经做过一次）。</p>
 */
public final class LogPrinter {

    /**
     * 业务日志专用 logger，与 logback-spring.xml 中的 {@code ACS_CORE} 对应。
     */
    private static final Logger CORE = LoggerFactory.getLogger("ACS_CORE");

    /**
     * 空值占位符。
     */
    private static final String EMPTY = "-";

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private LogPrinter() {
    }

    /**
     * 按级别写出一行日志。<b>本方法不得抛异常。</b>
     *
     * @param record 日志记录
     */
    public static void print(LogRecord record) {
        if (record == null) {
            return;
        }
        try {
            String line = format(record);
            switch (record.getLevel() == null ? LogLevel.INFO : record.getLevel()) {
                case DEBUG -> CORE.debug(line);
                case WARN -> CORE.warn(line);
                case ERROR -> CORE.error(line);
                case INFO -> CORE.info(line);
                default -> CORE.info(line);
            }
        } catch (Exception e) {
            // 排版失败也不能影响调用方，更不能递归回业务日志通道
            LoggerFactory.getLogger("ACS_LOG_INFRA")
                    .error("日志排版失败，已降级为纯文本: {}", e.getMessage());
        }
    }

    /**
     * 把记录排版成一行。单独暴露是为了让测试可以断言格式而不经过 logback。
     *
     * @param record 日志记录
     * @return 单行日志文本
     */
    public static String format(LogRecord record) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(categoryOf(record)).append(" | ");
        sb.append("sn=").append(value(record.getSn())).append(" | ");
        sb.append("key=").append(value(record.getBizKey())).append(" | ");
        sb.append("结果=").append(value(record.getResult())).append(" | ");
        if (notBlank(record.getCode())) {
            sb.append("code=").append(sanitize(record.getCode())).append(" | ");
        }
        sb.append(value(record.getMessage())).append(" | ");
        sb.append("线程=").append(value(record.getThread()));
        if (record.getExtra() != null && !record.getExtra().isEmpty()) {
            for (Map.Entry<String, String> entry : record.getExtra().entrySet()) {
                sb.append(" | ").append(entry.getKey()).append('=').append(value(entry.getValue()));
            }
        }
        return sb.toString();
    }

    /**
     * 事件发生时间，排版给外部工具（管理端日志查询）复用。
     */
    public static String formatTimestamp(long epochMillis) {
        return TS_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String categoryOf(LogRecord record) {
        LogCategory category = record.getCategory();
        return category == null ? LogCategory.SYSTEM.getLabel() : category.getLabel();
    }

    private static String value(String raw) {
        return notBlank(raw) ? sanitize(raw) : EMPTY;
    }

    private static boolean notBlank(String raw) {
        return raw != null && !raw.isBlank();
    }

    /**
     * 转义会破坏「一行一条」的正文字符：换行、制表符，以及作为分隔符的竖线。
     */
    private static String sanitize(String raw) {
        return raw.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
                .replace("|", "\\|").trim();
    }
}
