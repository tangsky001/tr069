package com.tr069.acs.common.log;

/**
 * 日志分类。
 *
 * <p>全站日志只允许走 {@link AcsCoreLog}，每一条都必须挂一个分类，
 * 这样翻 {@code log/ACS_LOG.LOG} 时既能按行首 grep，也能按分类统计。
 * 分类宁稳勿变：新增分类需要审阅，因为它决定落库与告警口径，
 * 而不是随手找个「其它」往里塞。</p>
 *
 * <p>「其它」本身不是分类。确实无法归类时才用 {@link #SYSTEM}，
 * 并且必须写清楚原因。</p>
 */
public enum LogCategory {

    /**
     * 设备台账：上线、离线、入库、登记。
     */
    DEVICE("设备台账"),

    /**
     * CWMP 会话：创建、关闭、换连接、双会话检测。
     */
    SESSION("会话"),

    /**
     * 设备上报 Inform。
     */
    INFORM("上报"),

    /**
     * CWMP 指令的提交与执行结果。
     */
    RPC("指令"),

    /**
     * 工单与意图下发流程。
     */
    TASK("工单"),

    /**
     * 业务下发（WiFi 重启、拨号参数等）。
     */
    BUSINESS("业务下发"),

    /**
     * 机型适配。
     */
    ADAPTATION("机型适配"),

    /**
     * 型号档案与能力树。
     */
    MODEL("型号能力"),

    /**
     * 配置模板构建与套用。
     */
    TEMPLATE("配置模板"),

    /**
     * Redis 队列的入队、消费、积压与降级。
     */
    QUEUE("队列"),

    /**
     * 北向 API 与 Webhook。
     */
    OPENAPI("北向接口"),

    /**
     * 进程启动期的建表、端口装配、消费者拉起等一次性动作。
     */
    STARTUP("启动"),

    /**
     * 数据表与持久化。
     */
    DATABASE("数据表"),

    /**
     * 管理端与北向的认证鉴权。
     */
    AUTH("认证"),

    /**
     * 其它无法归类的系统事件。
     */
    SYSTEM("系统");

    /**
     * 中文名，落在日志行的分类列上，便于人工阅读。
     */
    private final String label;

    LogCategory(String label) {
        this.label = label;
    }

    /**
     * 中文名。
     */
    public String getLabel() {
        return label;
    }
}
