package com.tr069.acs.acs.service;

import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.PendingRpc;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.cwmp.model.FaultStruct;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.queue.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 向设备下发 CWMP 指令的通用通道。
 *
 * <p>只有 capability 原先会下发 RPC，它把「队列 / 会话 outbox」的二选一逻辑写在自己内部。
 * 下发执行器与北向接口同样需要这条路（SetParameterValues / AddObject / DeleteObject / Reboot），
 * 所以抽出来复用，避免各处重复一套排队与降级判断。</p>
 *
 * <p>队列开启时指令进 Redis 等 ACS 下发器取走；关闭时退回会话内存 outbox，
 * 只要设备在线就能发。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CwmpRpcService {

    /**
     * 会话级故障码：设备离线、下发超时、连接断开，由会话层生成，区别于设备返回的 CWMP 故障。
     */
    private static final String FAULT_SESSION = "8002";

    /**
     * 指令入队失败（队列不可用或该设备队列已满）。
     */
    private static final String FAULT_ENQUEUE = "8003";

    private final com.tr069.acs.acs.session.SessionManager sessionManager;

    private final RpcDispatchService rpcDispatchService;

    private final QueueProperties queueProperties;

    /**
     * 队列关闭时退回会话内存 outbox，此时拿不到 rpc_record 主键，
     * 用负数序号避免与数据库主键相撞。
     */
    private final AtomicInteger outboxSequence = new AtomicInteger(0);

    /**
     * 提交一条下发指令，返回设备回应（凭据）。
     */
    public CompletableFuture<SoapMessage> submit(String sn, String rpcName,
                                                 Map<String, Object> args) {
        if (queueProperties.isEnabled()) {
            RpcDispatchService.DispatchHandle handle =
                    rpcDispatchService.submitAsync(sn, rpcName, args, null);
            if (!handle.isQueued()) {
                log.warn("指令未入队: sn={}, rpc={}, taskId={}", sn, rpcName, handle.getTaskId());
            }
            return handle.getFuture();
        }
        CpeSession session = sessionManager.find(sn);
        if (session == null) {
            log.warn("会话不存在，指令无法下发: sn={}, rpc={}", sn, rpcName);
            return CompletableFuture.completedFuture(sessionFault("会话不存在"));
        }
        PendingRpc rpc = session.enqueue(-outboxSequence.incrementAndGet(), rpcName, args, null);
        return rpc.getResponse();
    }

    /**
     * SetParameterValues：values 为 name / value / valueType 三元组的列表。
     */
    public CompletableFuture<SoapMessage> setParameterValues(String sn,
                                                             List<ParameterValueStruct> values,
                                                             String parameterKey) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterList", values);
        args.put("ParameterKey", parameterKey == null ? "" : parameterKey);
        return submit(sn, "SetParameterValues", args);
    }

    /**
     * AddObject：在 objectName（父路径）下新建一个对象实例。
     */
    public CompletableFuture<SoapMessage> addObject(String sn, String objectName, String key) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ObjectName", objectName);
        args.put("ParameterKey", key == null ? "" : key);
        return submit(sn, "AddObject", args);
    }

    /**
     * DeleteObject：删除 objectName 指向的实例（通常路径末段带实例号）。
     */
    public CompletableFuture<SoapMessage> deleteObject(String sn, String objectName, String key) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ObjectName", objectName);
        args.put("ParameterKey", key == null ? "" : key);
        return submit(sn, "DeleteObject", args);
    }

    /**
     * Reboot。
     */
    public CompletableFuture<SoapMessage> reboot(String sn, String key) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("CommandKey", key == null ? "" : key);
        return submit(sn, "Reboot", args);
    }

    /**
     * 设备是否在线（有可用会话），下发前先过这一关。
     */
    public boolean isOnline(String sn) {
        CpeSession session = sessionManager.find(sn);
        return session != null && session.isOnline();
    }

    /**
     * 设备是否正处在这一轮会话里：有会话、在线、且最近 {@code seconds} 秒内有通信。
     *
     * <p>只看 {@link #isOnline} 不够：CWMP 是短连接，设备报完一轮就把 TCP 断了，
     * 会话对象还在、在线标志要等扫描才清掉，此时提交指令只能等它下一次上报。
     * 下发前先等到「刚通信过」的会话，指令才会被设备紧接着的空 POST 取走。</p>
     */
    public boolean isSessionFresh(String sn, long seconds) {
        CpeSession session = sessionManager.find(sn);
        return session != null && session.isOnline() && !session.isExpired(seconds);
    }



    /**
     * 会话级故障：设备离线、超时、队列不可用。这类故障重试没有意义，直接判失败。
     */
    public static boolean isSessionFault(SoapMessage resp) {
        if (resp == null || resp.getFault() == null) {
            return false;
        }
        String code = resp.getFault().getFaultCode();
        return FAULT_SESSION.equals(code) || FAULT_ENQUEUE.equals(code);
    }

    /**
     * 设备返回的故障描述，包含 CWMP 故障码。
     */
    public static String faultText(SoapMessage resp) {
        if (resp == null || resp.getFault() == null) {
            return "无响应";
        }
        FaultStruct fault = resp.getFault();
        StringBuilder sb = new StringBuilder();
        sb.append(fault.getFaultCode()).append(' ').append(fault.getFaultString());
        if (fault.getSetParameterValuesFaultList() != null
                && !fault.getSetParameterValuesFaultList().isEmpty()) {
            sb.append("，失败参数: ");
            List<String> items = new ArrayList<>();
            for (FaultStruct.SetParameterValuesFault item : fault.getSetParameterValuesFaultList()) {
                items.add(item.getParameterName() + "(" + item.getFaultCode() + ")");
            }
            sb.append(String.join(", ", items));
        }
        return sb.toString();
    }

    /**
     * SetParameterValues / AddObject / DeleteObject 响应里的 Status：
     * 0 为成功，1 为部分成功或失败。
     */
    public static String statusOf(SoapMessage resp) {
        if (resp == null || resp.getBody() == null) {
            return null;
        }
        Object status = resp.getBody().get("Status");
        return status == null ? null : String.valueOf(status);
    }

    /**
     * AddObjectResponse 里新建出来的实例号；没有则为空。
     */
    public static String instanceNumberOf(SoapMessage resp) {
        if (resp == null || resp.getBody() == null) {
            return null;
        }
        Object number = resp.getBody().get("InstanceNumber");
        return number == null ? null : String.valueOf(number);
    }

    private static SoapMessage sessionFault(String reason) {
        return SoapMessage.builder()
                .fault(FaultStruct.builder()
                        .faultCode(FAULT_SESSION)
                        .faultString(reason)
                        .build())
                .build();
    }
}
