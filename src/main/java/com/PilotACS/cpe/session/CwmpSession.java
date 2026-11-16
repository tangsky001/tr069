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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 一个 CPE 的 CWMP 会话
 * <p>三态：0=已建会话等 Inform → 1=Inform 已回、等空 POST → 2=ACS RPC 在飞
 */
@Data
public class CwmpSession {

    public static final int STATE_CREATED = 0;
    public static final int STATE_INFORMED = 1;
    public static final int STATE_RPC_RUNNING = 2;

    private final String sessionId;
    /** 设备序列号（会话/任务主键） */
    private final String sn;
    /** 设备 ID 形如 oui-productClass-sn（Inform 时填） */
    private String deviceId;

    private int state = STATE_CREATED;

    /** 在飞 RPC 的步骤用途（scan/probe/collect/apply），Fault 时判断能不能跳过着继续 */
    private String pendingPurpose;
    private final long created = System.currentTimeMillis();
    private volatile long lastActivity = created;
    /** 一次性基本信息（Inform 时填） */
    private String manufacturer;
    private String oui;
    private String productClass;
    private String hardwareVersion;
    private String softwareVersion;
    private String connectionRequestUrl;
    private String cwmpVersion;
    private List<String> events = new ArrayList<>();
    private boolean bootstrap;
    /** 设备来源地址（Inform 时填）；老设备不回传 Cookie 时按它找回会话 */
    private volatile String remoteAddr;

    /** 会话内存参数树 */
    private final DeviceData data = new DeviceData();

    /** 设备绑定的机型模板（会话内缓存，运行期字段） */
    private transient Map<String, Object> modelRow;
    private transient Map<String, CpeParam> modelParams;

    /** 待执行任务队列（会话开始时快照 + 会话中新创建的任务） */
    private final Deque<TaskRun> queue = new ArrayDeque<>();
    private TaskRun current;
    /** 当前在飞的 ACS RPC（state=2 时非空） */
    private RpcRequest pending;
    private int rpcCount;

    /** 会话是否已结束（防重复收尾） */
    private volatile boolean ended;

    public void touch() {
        this.lastActivity = System.currentTimeMillis();
    }

    public boolean expired(long timeoutMs) {
        return System.currentTimeMillis() - lastActivity > timeoutMs;
    }

    /** 该会话是否还有活干（队列或当前任务还有步骤） */
    public boolean hasWork() {
        return !queue.isEmpty() || (current != null && !current.finished());
    }
}
