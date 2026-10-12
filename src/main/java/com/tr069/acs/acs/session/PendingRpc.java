package com.tr069.acs.acs.session;

import com.tr069.acs.cwmp.model.SoapMessage;
import lombok.Data;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 待下发给 CPE 的一条 CWMP RPC。
 *
 * <p>ACS 采用反向轮询模型：一个 HTTP 事务只携带一条 SOAP 消息，
 * 且只能在 CPE 主动发起的 POST 的响应体中捎带下发。</p>
 */
@Data
public class PendingRpc {

    /**
     * CWMP 方法名
     */
    private final String rpcName;

    /**
     * 方法参数
     */
    private final Map<String, Object> args;

    /**
     * 关联的工单，便于审计
     */
    private final String worklistId;

    /**
     * 下发顺序号，用于 RPC 审计
     */
    private final long rpcId;

    /**
     * 等待 CPE 回应的凭据，超时或收到响应时完成
     */
    private final CompletableFuture<SoapMessage> response = new CompletableFuture<>();

    /**
     * 下发时刻
     */
    private final long startTime = System.currentTimeMillis();

    public PendingRpc(long rpcId, String rpcName, Map<String, Object> args, String worklistId) {
        this.rpcId = rpcId;
        this.rpcName = rpcName;
        this.args = args;
        this.worklistId = worklistId;
    }
}
