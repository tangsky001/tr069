package com.tr069.acs.acs.session;

import com.tr069.acs.cwmp.model.SoapMessage;
import lombok.Data;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 待下发给 CPE 的一条 CWMP RPC。ACS 只能在 CPE 主动 POST 的响应体里捎带下发，
 * 一个 HTTP 事务只带一条 SOAP 消息。
 */
@Data
public class PendingRpc {

    private final String rpcName;

    private final Map<String, Object> args;

    private final String worklistId;

    private final long rpcId;

    /** 等待 CPE 回应的凭据，超时或收到响应时完成。 */
    private final CompletableFuture<SoapMessage> response = new CompletableFuture<>();

    private final long startTime = System.currentTimeMillis();

    /** 所属采集批次，超时重排队列时要带回，否则重发的这条会被当作上一轮残留清掉。 */
    private String batch;

    /** 已尝试下发的次数。只有没等到回应才重试。 */
    private int attempts;

    public PendingRpc(long rpcId, String rpcName, Map<String, Object> args, String worklistId) {
        this.rpcId = rpcId;
        this.rpcName = rpcName;
        this.args = args;
        this.worklistId = worklistId;
    }
}
