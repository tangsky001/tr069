package com.tr069.acs.acs.session;

import com.tr069.acs.cwmp.model.SoapMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 一个 CPE 的会话。
 *
 * <p>ACS 与 CPE 之间为反向轮询模型：一个 HTTP 事务只携带一条 SOAP 消息，
 * ACS 只能在 CPE 主动 POST 的响应体中捎带下发 RPC。</p>
 */
@Slf4j
@Data
public class CpeSession {

    /**
     * 设备唯一标识，格式 OUI-SN
     */
    private final String serialNumber;

    /**
     * 设备序列号（不含 OUI 前缀）
     */
    private final String sn;

    private final String oui;

    /**
     * 下发串行锁，保护「取指令 -> 标记在途 -> 写进响应」：两条连接同时进来捎带时，
     * 后一条会把先发那条在途的指令覆盖掉，那条就永远等不到回应。
     */
    private final ReentrantLock deliveryLock = new ReentrantLock();

    private volatile SessionState state = SessionState.INIT;

    /**
     * 本轮 CWMP 会话标识，写进 Set-Cookie 让 CPE 后续请求带回来。
     * 设备的空 POST 与 RPC 响应可能从另一个源端口过来（NAT 换端口、设备重连），
     * 只凭 ip:端口 认不出同一台设备，靠它把同一轮会话串起来。
     */
    private volatile String sessionId;

    /**
     * 最近一次 TCP 连接标识，用于双会话检测
     */
    private volatile String connectionId;

    /**
     * 待下发的 RPC 队列，按提交顺序串行执行
     */
    private final ConcurrentLinkedQueue<PendingRpc> outbox = new ConcurrentLinkedQueue<>();

    /**
     * 已下发、正在等待回应的 RPC，同一时刻最多一个
     */
    private volatile PendingRpc awaiting;

    /**
     * 最后通信时间
     */
    private volatile long lastContactTime = System.currentTimeMillis();

    /**
     * 在线状态
     */
    private volatile boolean online = true;

    /**
     * CWMP 版本，如 cwmp-1-0
     */
    private volatile String cwmpVersion = "cwmp-1-0";

    /**
     * 反向连接地址，取自 ManagementServer.ConnectionRequestURL
     */
    private volatile String connectionRequestUrl;

    private volatile String softwareVersion;

    private volatile String hardwareVersion;

    private volatile String productClass;

    private volatile String manufacturer;

    /**
     * 根数据模型节点，如 InternetGatewayDevice. 或 Device.
     */
    private volatile String rootNode = "InternetGatewayDevice.";

    /**
     * SOAP Inform 超时，可由设备级配置覆盖
     */
    private volatile int soapInformTimeout = 60;

    private volatile ScheduledFuture<?> waitPostNullTimer;

    private volatile ScheduledFuture<?> waitRpcResponseTimer;

    public CpeSession(String oui, String sn) {
        this.oui = oui;
        this.sn = sn;
        this.serialNumber = oui + "-" + sn;
    }

    public PendingRpc enqueue(long rpcId, String rpcName,
                              java.util.Map<String, Object> args, String worklistId) {
        PendingRpc rpc = new PendingRpc(rpcId, rpcName, args, worklistId);
        outbox.offer(rpc);
        return rpc;
    }

    /**
     * 取出下一条待下发 RPC，仅在无未完成 RPC 时允许下发。
     */
    public PendingRpc pollForSend() {
        if (awaiting != null && !awaiting.getResponse().isDone()) {
            return null;
        }
        return outbox.poll();
    }

    public void markAwaiting(PendingRpc rpc) {
        this.awaiting = rpc;
    }

    /**
     * 是否还有已下发、尚未收到回应的 RPC。回应常从另一条 TCP 连接回来，
     * 不能因为连接变了就当成双会话把会话关掉。
     */
    public boolean hasPendingAwaiting() {
        PendingRpc rpc = this.awaiting;
        return rpc != null && !rpc.getResponse().isDone();
    }

    /**
     * 用 CPE 的上报完成等待中的 RPC。
     *
     * @return 被完成的 RPC，无等待中的 RPC 时返回 null
     */
    public PendingRpc completeAwaiting(SoapMessage message) {
        PendingRpc rpc = this.awaiting;
        if (rpc != null) {
            rpc.getResponse().complete(message);
            this.awaiting = null;
        }
        return rpc;
    }

    /**
     * 超时或以异常结束等待中的 RPC。
     */
    public PendingRpc failAwaiting(String reason) {
        PendingRpc rpc = this.awaiting;
        if (rpc != null && !rpc.getResponse().isDone()) {
            SoapMessage failure = SoapMessage.builder()
                    .fault(com.tr069.acs.cwmp.model.FaultStruct.builder()
                            .faultCode("8002")
                            .faultString(reason)
                            .build())
                    .build();
            rpc.getResponse().complete(failure);
            this.awaiting = null;
        }
        return rpc;
    }

    /**
     * 判断是否已超时未通信。
     */
    public boolean isExpired(long intervalSeconds) {
        return System.currentTimeMillis() - lastContactTime > intervalSeconds * 1000L;
    }

    public void touch() {
        this.lastContactTime = System.currentTimeMillis();
    }

    /**
     * 取消全部定时器。
     */
    public void cancelTimers() {
        cancel(waitPostNullTimer);
        cancel(waitRpcResponseTimer);
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    /**
     * 替换等待空 POST 的定时器。
     *
     * <p>必须先取消上一个：设备周期上报会不断重新布防，只覆盖字段而不取消，
     * 旧的定时器就会在下一个周期到点时把刚建好的会话关掉。</p>
     */
    public void setWaitPostNullTimer(ScheduledFuture<?> timer) {
        cancel(waitPostNullTimer);
        this.waitPostNullTimer = timer;
    }

    public void setWaitRpcResponseTimer(ScheduledFuture<?> timer) {
        cancel(waitRpcResponseTimer);
        this.waitRpcResponseTimer = timer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CpeSession other)) {
            return false;
        }
        return Objects.equals(serialNumber, other.serialNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serialNumber);
    }
}
