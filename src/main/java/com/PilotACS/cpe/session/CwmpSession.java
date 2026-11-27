/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.session;

import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.DeviceData;
import com.PilotACS.cpe.model.RpcRequest;
import com.PilotACS.cpe.model.TaskRun;
import lombok.Data;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 一个 CPE 的 CWMP 会话 */
@Data
public class CwmpSession {

    public static final int STATE_CREATED = 0;
    public static final int STATE_INFORMED = 1;
    public static final int STATE_RPC_RUNNING = 2;

    private final String sessionId;
    /** 设备序列号 */
    private final String sn;
    /** 设备 ID（Inform 时填） */
    private String deviceId;

    /** 会话状态 */
    private volatile int state = STATE_CREATED;

    /** 在飞 RPC 的步骤用途 */
    private volatile String pendingPurpose;
    private final long created = System.currentTimeMillis();
    private volatile long lastActivity = created;
    /** 设备声明的会话超时（毫秒，0=未声明） */
    private volatile long timeoutMs;
    /** 基本信息（Inform 时填） */
    private String manufacturer;
    private String oui;
    private String productClass;
    private String hardwareVersion;
    private String softwareVersion;
    private String connectionRequestUrl;
    private String cwmpVersion;
    /** 设备使用的 CWMP 命名空间（响应按同版本回，默认 1-0） */
    private String cwmpNamespace = "urn:dslforum-org:cwmp-1-0";
    private List<String> events = new ArrayList<>();
    private boolean bootstrap;
    /** 设备来源地址 */
    private volatile String remoteAddr;

    /** 会话内存参数树 */
    private final DeviceData data = new DeviceData();

    /** 设备绑定的机型模板 */
    private transient Map<String, Object> modelRow;
    private transient Map<String, CpeParam> modelParams;

    /** 待执行任务队列 */
    private final Deque<TaskRun> queue = new ConcurrentLinkedDeque<>();
    /** 当前任务 */
    private volatile TaskRun current;
    /** 当前在飞的 ACS RPC */
    private volatile RpcRequest pending;
    /** 在飞 RPC 的下发时刻（毫秒），配合 collect-timeout 做无回执看门狗 */
    private volatile long pendingDispatchAt;
    private volatile int rpcCount;

    /** 会话是否已结束 */
    private volatile boolean ended;

    public void touch() {
        this.lastActivity = System.currentTimeMillis();
    }

    public boolean expired(long timeoutMs) {
        return System.currentTimeMillis() - lastActivity > timeoutMs;
    }

    /** 空闲超时口径（优先设备声明，限 30s~30min） */
    public long effectiveTimeout(long defaultMs) {
        long t = timeoutMs;
        if (t <= 0) {
            return defaultMs;
        }
        return Math.min(Math.max(t, 30_000L), 30 * 60_000L);
    }

    /** 会话是否还有活干 */
    public boolean hasWork() {
        return !queue.isEmpty() || (current != null && !current.finished());
    }
}
