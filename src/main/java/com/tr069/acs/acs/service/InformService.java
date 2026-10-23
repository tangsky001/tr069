package com.tr069.acs.acs.service;

import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.cwmp.model.EventStruct;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.tr069.acs.common.log.AcsCoreLog;

/**
 * Inform 事务处理。
 *
 * <p>收到 Inform 后更新设备属性、记录 RPC 与 SOAP 报文、按事件码调度后续业务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InformService {

    /**
     * 0 BOOTSTRAP 事件码，处理时须排在最前：它要先把设备档案建起来，其余事件才有着落
     */
    private static final String EVENT_BOOTSTRAP = "0 BOOTSTRAP";

    /**
     * ManagementServer.ConnectionRequestURL 的后缀
     */
    private static final String CONN_REQUEST_URL_SUFFIX = "ManagementServer.ConnectionRequestURL";

    private static final String SOFTWARE_VERSION_SUFFIX = "DeviceInfo.SoftwareVersion";

    private static final String HARDWARE_VERSION_SUFFIX = "DeviceInfo.HardwareVersion";

    private final DeviceRegistryService deviceRegistry;

    private final AcsCoreLog coreLog;

    /**
     * 处理一次 Inform：更新会话属性并返回排序后的事件码列表。
     *
     * @param session 设备会话
     * @param inform  Inform 内容
     * @param rawXml  原始报文，用于审计留痕
     * @return 排序后的事件码，用于后续异步调度
     */
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
        return eventCodes;
    }

    /**
     * 事件码排序：0 BOOTSTRAP 强制排在最前，其余保持原顺序。
     */
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
}
