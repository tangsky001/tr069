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

/** 向设备下发 CWMP 指令的通用通道 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CwmpRpcService {

    /** 会话级故障码：设备离线、下发超时、连接断开，由会话层生成，区别于设备返回的 CWMP 故障 */
    private static final String FAULT_SESSION = "8002";

    /** 指令入队失败（队列不可用或该设备队列已满） */
    private static final String FAULT_ENQUEUE = "8003";

    private final com.tr069.acs.acs.session.SessionManager sessionManager;

    private final RpcDispatchService rpcDispatchService;

    private final QueueProperties queueProperties;

    /** 队列关闭时退回会话内存 outbox，此时拿不到 rpc_record 主键 */
    private final AtomicInteger outboxSequence = new AtomicInteger(0);

    /**
     * 正在下发的设备 SN。下发标志按 SN 存而不是只放会话对象上：
     * 任务受理时设备往往还没上来，会话对象不存在，只写在会话上的标志会静默丢失——
     * 之后整个下发期间空 POST 只挂 1.5 秒就被 204 断会话，表现就是「下发到一半会话中断」
     */
    private final java.util.Set<String> deliveringSns = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 提交一条下发指令，返回设备回应（凭据） */
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

    /** SetParameterValues：values 为 name / value / valueType 三元组的列表 */
    public CompletableFuture<SoapMessage> setParameterValues(String sn,
                                                             List<ParameterValueStruct> values,
                                                             String parameterKey) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterList", values);
        args.put("ParameterKey", parameterKey == null ? "" : parameterKey);
        return submit(sn, "SetParameterValues", args);
    }

    /** AddObject：在 objectName（父路径）下新建一个对象实例 */
    public CompletableFuture<SoapMessage> addObject(String sn, String objectName, String key) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ObjectName", objectName);
        args.put("ParameterKey", key == null ? "" : key);
        return submit(sn, "AddObject", args);
    }

    /** DeleteObject：删除 objectName 指向的实例（通常路径末段带实例号） */
    public CompletableFuture<SoapMessage> deleteObject(String sn, String objectName, String key) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ObjectName", objectName);
        args.put("ParameterKey", key == null ? "" : key);
        return submit(sn, "DeleteObject", args);
    }

    /** Reboot */
    public CompletableFuture<SoapMessage> reboot(String sn, String key) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("CommandKey", key == null ? "" : key);
        return submit(sn, "Reboot", args);
    }

    /** 设备是否在线（有可用会话），下发前先过这一关 */
    public boolean isOnline(String sn) {
        CpeSession session = sessionManager.find(sn);
        return session != null && session.isOnline();
    }

    /**
     * 标记设备会话为「下发进行中」：空 POST 来取指令时，CwmpController 会据此延长挂起时间，
     * 不再1.5秒就回204断会话。IntentDeliveryService 在 execute 开始时调 beginDelivery，
     * 在 finish/异常时调 endDelivery。标志同时记在 SN 集合里，会话对象此时可能还没建。
     */
    public void beginDelivery(String sn) {
        if (sn == null || sn.isBlank()) {
            return;
        }
        deliveringSns.add(sn);
        CpeSession session = sessionManager.find(sn);
        if (session != null) {
            session.setDeliveryInProgress(true);
        }
    }

    /** 标记设备「下发完成」：ACS 回 204 告诉设备本轮可以断了 */
    public void endDelivery(String sn) {
        if (sn == null || sn.isBlank()) {
            return;
        }
        deliveringSns.remove(sn);
        CpeSession session = sessionManager.find(sn);
        if (session != null) {
            session.setDeliveryInProgress(false);
        }
    }

    /** 这台设备是否有下发在进行中（含会话对象尚未建立的情况） */
    public boolean isDelivering(String sn) {
        if (sn != null && deliveringSns.contains(sn)) {
            return true;
        }
        CpeSession session = sn == null ? null : sessionManager.find(sn);
        return session != null && session.isDeliveryInProgress();
    }

    /** 设备是否正处在这一轮会话里：有会话、在线、且最近 {@code seconds} 秒内有通信 */
    public boolean isSessionFresh(String sn, long seconds) {
        CpeSession session = sessionManager.find(sn);
        return session != null && session.isOnline() && !session.isExpired(seconds);
    }



    /** 会话级故障：设备离线、超时、队列不可用。这类故障重试没有意义，直接判失败 */
    public static boolean isSessionFault(SoapMessage resp) {
        if (resp == null || resp.getFault() == null) {
            return false;
        }
        String code = resp.getFault().getFaultCode();
        return FAULT_SESSION.equals(code) || FAULT_ENQUEUE.equals(code);
    }

    /** 设备返回的故障描述，包含 CWMP 故障码 */
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

    /** SetParameterValues / AddObject / DeleteObject 响应里的 Status */
    public static String statusOf(SoapMessage resp) {
        if (resp == null || resp.getBody() == null) {
            return null;
        }
        Object status = resp.getBody().get("Status");
        return status == null ? null : String.valueOf(status);
    }

    /** AddObjectResponse 里新建出来的实例号；没有则为空 */
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
