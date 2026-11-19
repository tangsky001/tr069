/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.common.ws;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 实时日志采集器：挂在 Logback 根 Logger 上，把控制台输出的每条日志格式化后 */
@Component
public class RealtimeLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** 重入闸：本类自身触发的日志不再采集，避免 推送→日志→再推送 死循环 */
    private static final ThreadLocal<Boolean> EMITTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final RealtimeLogWsHandler handler;

    public RealtimeLogAppender(RealtimeLogWsHandler handler) {
        this.handler = handler;
    }

    /** Bean 装配完成后挂到根 Logger，与控制台输出并行生效 */
    @PostConstruct
    public void attach() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        setContext(root.getLoggerContext());
        setName("RealtimeLogWsAppender");
        root.addAppender(this);
        start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (EMITTING.get()) {
            return;
        }
        EMITTING.set(Boolean.TRUE);
        try {
            handler.publish(event.getLevel().toString(), format(event));
        } finally {
            EMITTING.remove();
        }
    }

    private String format(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(TIME.format(Instant.ofEpochMilli(event.getTimeStamp())))
                .append(' ')
                .append(pad(event.getLevel()))
                .append(' ')
                .append('[').append(shortName(event.getLoggerName())).append("] ")
                .append(event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            sb.append(System.lineSeparator()).append(ThrowableProxyUtil.asString(throwable));
        }
        return sb.toString();
    }

    /** 级别定宽对齐，控制台观感 */
    private String pad(Level level) {
        String name = level.toString();
        return switch (name) {
            case "WARN" -> "WARN ";
            case "INFO" -> "INFO ";
            default -> name;
        };
    }

    /** logger 全限定名只留最后一段类名，避免前端一行太长 */
    private String shortName(String loggerName) {
        if (loggerName == null) {
            return "";
        }
        int dot = loggerName.lastIndexOf('.');
        return dot >= 0 ? loggerName.substring(dot + 1) : loggerName;
    }
}
