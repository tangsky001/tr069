package com.tr069.acs.common.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** 核心业务日志：设备上下线、上报、指令结果、业务下发、机型适配 */
@Component
public class AcsCoreLog {

    private static final Logger LOG = LoggerFactory.getLogger("ACS_CORE");

    /** 设备上线 */
    public void online(String sn, String reason) {
        LOG.info("上线 | sn={} | {}", sn, reason);
    }

    /** 设备离线 */
    public void offline(String sn, String reason) {
        LOG.info("离线 | sn={} | {}", sn, reason);
    }

    /** 设备上报 Inform */
    public void inform(String sn, List<String> eventCodes, String connId) {
        LOG.info("上报 | sn={} | events={} | conn={}", sn, eventCodes, connId);
    }

    /** 一条 CWMP 指令的最终结果 */
    public void rpcResult(String sn, String rpcName, long taskId, boolean success,
                          String faultCode, String faultString) {
        LOG.info("指令 | sn={} | rpc={} | taskId={} | 结果={} | code={} | {}",
                sn, rpcName, taskId, success ? "成功" : "失败", faultCode, faultString);
    }

    /** 一次业务下发（工单）的最终结果 */
    public void business(String sn, String bizKey, boolean success, String error) {
        LOG.info("业务下发 | sn={} | {} | 结果={} | {}",
                sn, bizKey, success ? "成功" : "失败", error);
    }

    /** 机型适配事件 */
    public void adaptation(String modelKey, String action, boolean success, String detail) {
        LOG.info("机型适配 | {} | {} | 结果={} | {}",
                modelKey, action, success ? "成功" : "失败", detail);
    }

    /** 下发后回读核对：值是否真的写进设备 */
    public void verify(String sn, boolean matched, String detail) {
        LOG.info("下发核对 | sn={} | 结果={} | {}", sn, matched ? "一致" : "不一致", detail);
    }
}
