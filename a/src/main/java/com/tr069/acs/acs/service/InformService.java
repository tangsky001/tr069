package com.tr069.acs.acs.service;

import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.cwmp.model.EventStruct;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeParameterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.tr069.acs.common.log.AcsCoreLog;

/** Inform 事务处理 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InformService {

    /** 0 BOOTSTRAP 事件码，处理时须排在最前：它要先把设备档案建起来，其余事件才有着落 */
    private static final String EVENT_BOOTSTRAP = "0 BOOTSTRAP";

    /** ManagementServer.ConnectionRequestURL 的后缀 */
    private static final String CONN_REQUEST_URL_SUFFIX = "ManagementServer.ConnectionRequestURL";

    /** ManagementServer.UDPConnectionRequestAddress 的后缀（TR-111 NAT 穿越） */
    private static final String UDP_CONN_REQUEST_SUFFIX = "ManagementServer.UDPConnectionRequestAddress";

    private static final String SOFTWARE_VERSION_SUFFIX = "DeviceInfo.SoftwareVersion";

    private static final String HARDWARE_VERSION_SUFFIX = "DeviceInfo.HardwareVersion";

    private final DeviceRegistryService deviceRegistry;

    private final AcsCoreLog coreLog;

    private final CpeParameterMapper parameterMapper;

    /** 设备事件落台账 */
    private final DeviceEventRecorder eventRecorder;

    /** 业务参数采集（WAN/WIFI/SIP 全量读值 + WAN 实例盲探），重启类上报后自动触发 */
    private final com.tr069.acs.acs.service.DeviceBusinessService businessService;

    /** 重启类上报后的业务采集走独立线程池，不占用 Inform 处理线程 */
    private final java.util.concurrent.ExecutorService bizCollectExecutor =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "biz-inform-collect");
                thread.setDaemon(true);
                return thread;
            });

    /** Inform 上报批次号前缀 */
    private static final String INFORM_BATCH_PREFIX = "inform-";

    /** 单批 upsert 的最大条目数，防止 SQL 包过长 */
    private static final int UPSERT_CHUNK = 200;

    /** 处理一次 Inform：更新会话属性并返回排序后的事件码列表 */
    public List<String> handle(CpeSession session, InformRequest inform, String rawXml) {
        List<ParameterValueStruct> parameters = inform.getParameterList() == null
                ? List.of() : inform.getParameterList();

        for (ParameterValueStruct p : parameters) {
            String name = p.getName();
            if (name == null) {
                continue;
            }
            if (name.endsWith(CONN_REQUEST_URL_SUFFIX)) {
                session.setConnectionRequestUrl(p.getValue());
            } else if (name.endsWith(UDP_CONN_REQUEST_SUFFIX)) {
                session.setUdpConnectionRequestAddress(p.getValue());
            } else if (name.endsWith(SOFTWARE_VERSION_SUFFIX)) {
                session.setSoftwareVersion(p.getValue());
            } else if (name.endsWith(HARDWARE_VERSION_SUFFIX)) {
                session.setHardwareVersion(p.getValue());
            }
        }

        // 判定根数据模型节点：遍历全部上报参数取多数，只看第一条会被扩展节点带偏
        List<String> paramPaths = new ArrayList<>(parameters.size());
        for (ParameterValueStruct p : parameters) {
            if (p.getName() != null) {
                paramPaths.add(p.getName());
            }
        }
        session.setRootNode(DataModel.detect(paramPaths).getRoot());

        if (inform.getDeviceId() != null) {
            session.setProductClass(inform.getDeviceId().getProductClass());
            session.setManufacturer(inform.getDeviceId().getManufacturer());
        }

        session.touch();
        session.setOnline(true);

        List<String> eventCodes = sortEventCodes(inform.getEventList());
        // 台账落库：设备列表与离线判定都读这张表。
        // 顺带把上报参数交出去认运营商（电信的 X_CT-COM_UserInfo 随 Inform 就来了），
        // 采集一开始就能按这家运营商的标准走，不用等能力树采完
        deviceRegistry.upsertOnline(session, paramPaths);

        log.info("收到 Inform: sn={}, events={}, sw={}, hw={}",
                session.getSn(), eventCodes, session.getSoftwareVersion(), session.getHardwareVersion());
        // 核心日志：设备上报单独记一行到 log/ACS_LOG.LOG
        coreLog.inform(session.getSn(), eventCodes, session.getConnectionId());

        // 事件码落台账（cpe_device_event）：重启（BOOT/BOOTSTRAP）、连接请求、传输完成等
        // 运维要按设备查"上周重启过几次"。周期上报是噪音，跳过不记
        recordInformEvents(session.getSn(), eventCodes);

        // Inform 上报的参数值写入 cpe_parameter：设备首次上报就带了 WAN / LAN 等业务参数，
        // 不写的话业务面板要等能力采集（GPN+GPV）完成后才有数据。
        // 用 upsert：能力树已有同路径就更新值，没有就插入——不破坏后续能力采集的结构。
        saveInformParameters(session.getSn(), parameters);

        // 重启类上报（下发后重启 / 恢复出厂）= 设备配置刚变过，业务值快照必定过期：
        // 自动把 WAN/WIFI/SIP 全量重读一遍落库。万台规模不可能靠人点「重新采集」，
        // 重启上报就是唯一可靠的刷新时机。延迟几秒给能力树采集让路（同一次 Inform 触发）
        scheduleBizCollectOnReboot(session.getSn(), eventCodes);

        return eventCodes;
    }

    /** 重启类上报（1 BOOT / 0 BOOTSTRAP）后自动做一次业务参数采集 */
    private void scheduleBizCollectOnReboot(String sn, List<String> eventCodes) {
        boolean reboot = eventCodes != null && eventCodes.stream()
                .anyMatch(c -> c != null && (c.startsWith("0 BOOTSTRAP") || c.startsWith("1 BOOT")));
        if (!reboot) {
            return;
        }
        java.util.concurrent.CompletableFuture
                .delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS, bizCollectExecutor)
                .execute(() -> {
                    try {
                        businessService.collect(sn);
                        log.info("重启上报后已自动刷新业务参数: sn={}", sn);
                    } catch (Exception e) {
                        log.warn("重启上报后自动刷新业务参数失败（不影响上报流程）: sn={}, err={}",
                                sn, e.getMessage());
                    }
                });
    }

    /** 事件码排序：0 BOOTSTRAP 强制排在最前，其余保持原顺序 */
    private List<String> sortEventCodes(List<EventStruct> events) {
        List<String> codes = new ArrayList<>();
        if (events == null) {
            return codes;
        }
        List<EventStruct> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingInt(e -> EVENT_BOOTSTRAP.equals(e.getEventCode()) ? 0 : 1));
        for (EventStruct e : sorted) {
            if (e.getEventCode() != null && !e.getEventCode().isBlank()) {
                codes.add(e.getEventCode());
            }
        }
        return codes;
    }

    /** 事件码落台账：周期上报（2 PERIODIC）这种每几十分钟一条的噪音不记，其余全留 */
    private void recordInformEvents(String sn, List<String> eventCodes) {
        try {
            for (String code : eventCodes) {
                if (code == null || code.startsWith("2 ") || code.startsWith("3 ")) {
                    continue;
                }
                eventRecorder.recordEvent(sn, "inform", code, null);
            }
        } catch (Exception e) {
            log.warn("Inform 事件落台账失败（不影响上报流程）: sn={}, err={}", sn, e.getMessage());
        }
    }

    /**
     * 把 Inform 上报的参数值写入 cpe_parameter 表。
     * <p>
     * 设备 Inform 时自带了当前运行参数（WAN 连接状态、IP 地址、SSID 等），
     * 这些值在能力采集（GPN+GPV）完成前就能让业务面板展示。
     * 用 ON DUPLICATE KEY UPDATE：已有能力树行只更新值，没有的插入为 param 类型行。
     */
    private void saveInformParameters(String sn, List<ParameterValueStruct> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return;
        }
        String batch = INFORM_BATCH_PREFIX + System.currentTimeMillis();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        List<CpeParameter> rows = new ArrayList<>();
        for (ParameterValueStruct p : parameters) {
            String path = p.getName();
            if (path == null || path.isBlank()) {
                continue;
            }
            // 跳过对象节点（以句点结尾），只写叶子参数值
            if (path.endsWith(".")) {
                continue;
            }
            CpeParameter row = new CpeParameter();
            row.setSn(sn);
            row.setParamPath(path);
            row.setParentPath(parentOf(path));
            row.setNodeName(nameOf(path));
            row.setNodeType(CpeParameter.TYPE_PARAM);
            row.setWritable(0);
            row.setDepth(depthOf(path));
            row.setParamValue(p.getValue());
            row.setValueType(p.getValueType() != null ? p.getValueType() : "string");
            row.setCollectBatch(batch);
            row.setCollectTime(now);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return;
        }
        for (int i = 0; i < rows.size(); i += UPSERT_CHUNK) {
            List<CpeParameter> chunk = rows.subList(i, Math.min(i + UPSERT_CHUNK, rows.size()));
            try {
                parameterMapper.upsertInformValues(chunk);
            } catch (Exception e) {
                log.warn("Inform 参数落库失败: sn={}, size={}, err={}", sn, chunk.size(), e.getMessage());
            }
        }
    }

    /** 父对象路径，根节点为空串 */
    private static String parentOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('.');
        return idx < 0 ? "" : p.substring(0, idx + 1);
    }

    /** 路径末段名 */
    private static String nameOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('.');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    /** 层级深度，根节点为 0 */
    private static int depthOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int depth = 0;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == '.') {
                depth++;
            }
        }
        return depth;
    }
}
