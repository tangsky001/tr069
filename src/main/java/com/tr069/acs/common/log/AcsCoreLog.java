package com.tr069.acs.common.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 核心业务日志：设备上下线、上报、指令结果、业务下发、机型适配。
 *
 * <p>这些是运维排障真正关心的事件，混在 CWMP 调试日志里会被刷掉，所以单独走
 * {@code ACS_CORE} 这个 logger，由 logback-spring.xml 额外写一份到
 * {@code log/ACS_LOG.LOG}（异步写盘，不拖慢 CWMP 处理；控制台同时保留）。</p>
 *
 * <p>一行一条事件，字段用竖线分隔，方便直接 grep 文件排查。</p>
 */
@Component
public class AcsCoreLog {

    private static final Logger LOG = LoggerFactory.getLogger("ACS_CORE");

    /**
     * 设备上线。
     *
     * @param sn     设备序列号
     * @param reason 上线原因，如「首次上报入库」「离线后重新上报」
     */
    public void online(String sn, String reason) {
        LOG.info("上线 | sn={} | {}", sn, reason);
    }

    /**
     * 设备离线。
     *
     * @param sn     设备序列号
     * @param reason 离线原因，如「会话结束或超时未上报」
     */
    public void offline(String sn, String reason) {
        LOG.info("离线 | sn={} | {}", sn, reason);
    }

    /**
     * 设备上报 Inform。
     *
     * @param sn         设备序列号
     * @param eventCodes 上报事件码，如 1 BOOT、2 PERIODIC、4 VALUE CHANGE
     * @param connId     本次上报所用连接标识
     */
    public void inform(String sn, List<String> eventCodes, String connId) {
        LOG.info("上报 | sn={} | events={} | conn={}", sn, eventCodes, connId);
    }

    /**
     * 一条 CWMP 指令的最终结果。
     *
     * @param sn           设备序列号
     * @param rpcName      方法名
     * @param taskId       指令任务号
     * @param success      是否成功
     * @param faultCode    失败码，成功时传 "-"
     * @param faultString  失败描述，成功时传 "-"
     */
    public void rpcResult(String sn, String rpcName, long taskId, boolean success,
                          String faultCode, String faultString) {
        LOG.info("指令 | sn={} | rpc={} | taskId={} | 结果={} | code={} | {}",
                sn, rpcName, taskId, success ? "成功" : "失败", faultCode, faultString);
    }

    /**
     * 一次业务下发（工单）的最终结果。
     *
     * @param sn      设备序列号
     * @param bizKey  业务标识，如「WiFi/任务号」
     * @param success 是否成功
     * @param error   失败原因，成功时传 null
     */
    public void business(String sn, String bizKey, boolean success, String error) {
        LOG.info("业务下发 | sn={} | {} | 结果={} | {}",
                sn, bizKey, success ? "成功" : "失败", error);
    }

    /**
     * 机型适配事件。
     *
     * @param modelKey 型号标识，如「CMCC/P22g-CIac_V1.0.00.488」
     * @param action   动作，如「适配入库」
     * @param success  是否成功
     * @param detail   补充说明，失败时写明原因
     */
    public void adaptation(String modelKey, String action, boolean success, String detail) {
        LOG.info("机型适配 | {} | {} | 结果={} | {}",
                modelKey, action, success ? "成功" : "失败", detail);
    }
}
