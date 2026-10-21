package com.tr069.acs.common.log;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全站<b>唯一</b>的业务日志出口。
 *
 * <p>规范（改动本项目任何日志前先读完这段）：</p>
 * <ol>
 *   <li><b>业务代码禁止直接 {@code log.info / log.debug}。</b>所有业务事件必须走这里，
 *       这样日志才有统一的分类、列序与落盘策略。</li>
 *   <li><b>每条日志必须有分类。</b>要么用下面已封装好的业务语义方法，要么用
 *       {@link #entry(LogCategory)} 显式指定。</li>
 *   <li><b>调用方只负责描述事实，不负责排版。</b>正文写成一句人话，不要自己拼竖线分隔符——
 *       列由 {@link LogPrinter} 统一生成，拼了就会错位。</li>
 *   <li><b>{@code log.warn / log.error} 仍可保留在各自类里。</b>它们是异常诊断而非业务事件，
 *       走各类的默认 logger、直接进控制台，不占用核心日志队列的带宽。</li>
 * </ol>
 *
 * <p>本类只负责把记录丢进 {@link LogQueueService}，绝不阻塞调用线程、永不抛异常，
 * 真正的落盘由 {@link LogQueueConsumer} 完成。</p>
 */
@Component
@RequiredArgsConstructor
public class AcsCoreLog {

    private final LogQueueService logQueue;

    private final LogProperties properties;

    // ==================== 设备上下线 ====================

    /**
     * 设备上线。
     *
     * @param sn     设备序列号
     * @param reason 上线原因，如「首次上报入库」「离线后重新上报」
     */
    public void online(String sn, String reason) {
        entry(LogCategory.DEVICE).sn(sn).bizKey("上线").result(true).info(text(reason));
    }

    /**
     * 设备离线。
     *
     * @param sn     设备序列号
     * @param reason 离线原因，如「会话结束或超时未上报」
     */
    public void offline(String sn, String reason) {
        entry(LogCategory.DEVICE).sn(sn).bizKey("离线").result(false).info(text(reason));
    }

    /**
     * 设备台账变化：入库、型号登记、字段刷新等。
     *
     * @param sn      设备序列号
     * @param action  动作名，如「设备入库」
     * @param success 是否成功
     * @param detail  补充说明
     */
    public void device(String sn, String action, boolean success, String detail) {
        entry(LogCategory.DEVICE).sn(sn).bizKey(action).result(success).info(text(detail));
    }

    // ==================== 上报 ====================

    /**
     * 设备上报 Inform。
     *
     * @param sn         设备序列号
     * @param eventCodes 上报事件码，如 1 BOOT、2 PERIODIC、4 VALUE CHANGE
     * @param connId     本次上报所用连接标识
     */
    public void inform(String sn, List<String> eventCodes, String connId) {
        entry(LogCategory.INFORM).sn(sn).bizKey("Inform").with("events", eventCodes).with("conn", connId)
                .result(true).info("设备上报");
    }

    // ==================== 指令 ====================

    /**
     * 一条 CWMP 指令的最终结果。
     *
     * @param sn           设备序列号
     * @param rpcName      方法名
     * @param taskId       指令任务号
     * @param success      是否成功
     * @param faultCode    失败码，成功时传 "-" 或 null
     * @param faultString  失败描述，成功时传 "-" 或 null
     */
    public void rpcResult(String sn, String rpcName, long taskId, boolean success,
                          String faultCode, String faultString) {
        entry(LogCategory.RPC).sn(sn).bizKey(rpcName).code(blankToNull(faultCode))
                .result(success).info("指令结果 | taskId=" + taskId + " | " + text(faultString));
    }

    /**
     * 指令提交 / 等待 / 超时等中间态。
     *
     * @param sn      设备序列号
     * @param rpcName 方法名
     * @param stage   阶段，如「已提交」「等待回应超时」
     * @param detail  补充说明
     */
    public void rpcStage(String sn, String rpcName, String stage, String detail) {
        entry(LogCategory.RPC).sn(sn).bizKey(rpcName).code(stage).info(text(detail));
    }

    // ==================== 业务下发与工单 ====================

    /**
     * 一次业务下发（工单）的最终结果。
     *
     * @param sn      设备序列号
     * @param bizKey  业务标识，如「WiFi/任务号」
     * @param success 是否成功
     * @param error   失败原因，成功时传 null
     */
    public void business(String sn, String bizKey, boolean success, String error) {
        entry(LogCategory.BUSINESS).sn(sn).bizKey(bizKey).result(success).info(text(error));
    }

    /**
     * 工单 / 意图下发的阶段进展。
     *
     * @param sn      设备序列号
     * @param bizKey  工单或任务标识
     * @param stage   阶段名
     * @param detail  补充说明
     * @param success 该阶段是否成功
     */
    public void task(String sn, String bizKey, String stage, String detail, boolean success) {
        entry(LogCategory.TASK).sn(sn).bizKey(bizKey).code(stage).result(success).info(text(detail));
    }

    // ==================== 机型适配 ====================

    /**
     * 机型适配事件。
     *
     * @param modelKey 型号标识，如「CMCC/P22g-CIac_V1.0.00.488」
     * @param action   动作，如「适配入库」
     * @param success  是否成功
     * @param detail   补充说明，失败时写明原因
     */
    public void adaptation(String modelKey, String action, boolean success, String detail) {
        entry(LogCategory.ADAPTATION).bizKey(modelKey).code(action).result(success).info(text(detail));
    }

    // ==================== 通用入口 ====================

    /**
     * 通用入口：显式指定分类，链式拼装一条日志。
     *
     * <pre>
     * coreLog.entry(LogCategory.QUEUE).bizKey(key).warn("入队失败，转就地处理");
     * </pre>
     *
     * @param category 日志分类
     * @return 日志构造器
     */
    public LogEntry entry(LogCategory category) {
        return new LogEntry(logQueue, properties, category);
    }

    /**
     * 快捷方式：一条不带设备与业务键的分类日志。
     *
     * @param category 分类
     * @param level    级别
     * @param message  正文
     */
    public void log(LogCategory category, LogLevel level, String message) {
        LogEntry entry = entry(category);
        switch (level == null ? LogLevel.INFO : level) {
            case DEBUG -> entry.debug(message);
            case WARN -> entry.warn(message);
            case ERROR -> entry.error(message);
            default -> entry.info(message);
        }
    }

    /**
     * 快捷方式：带设备序列号的分类日志。
     *
     * @param category 分类
     * @param level    级别
     * @param sn       设备序列号，可为空
     * @param message  正文
     */
    public void log(LogCategory category, LogLevel level, String sn, String message) {
        LogEntry entry = entry(category).sn(sn);
        switch (level == null ? LogLevel.INFO : level) {
            case DEBUG -> entry.debug(message);
            case WARN -> entry.warn(message);
            case ERROR -> entry.error(message);
            default -> entry.info(message);
        }
    }

    private static String text(String raw) {
        return raw == null || raw.isBlank() ? "-" : raw;
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
