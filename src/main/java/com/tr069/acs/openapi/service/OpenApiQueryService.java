package com.tr069.acs.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.service.DeviceBusinessService;
import com.tr069.acs.acs.service.DeviceCapabilityService;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.domain.entity.Worklist;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.mapper.DeviceTaskMapper;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.mapper.WorklistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 北向只读查询：给上层系统看设备台账、在线状态、能力树、业务快照与下发任务结果。
 *
 * <p>读口径与管理端完全一致，差别只在返回结构——管理端要适配页面组件，
 * 北向要的是稳定的字段名与含义，所以这里重新组装视图，而不是直接把管理端的 Map 透出去。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiQueryService {

    /** 单次最多返回的明细条数，防止一台设备的超大能力树把调用方打爆。 */
    private static final int MAX_DETAIL = 5000;

    private final CpeDeviceMapper deviceMapper;
    private final CpeParameterMapper parameterMapper;
    private final WorklistMapper worklistMapper;
    private final DeviceTaskMapper taskMapper;
    private final SessionManager sessionManager;
    private final DeviceCapabilityService capabilityService;
    private final DeviceBusinessService businessService;

    /**
     * 设备台账分页查询。
     */
    public Map<String, Object> devices(String keyword, String isp, long page, long size) {
        LambdaQueryWrapper<CpeDevice> query = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(CpeDevice::getSn, kw)
                    .or().like(CpeDevice::getProductClass, kw)
                    .or().like(CpeDevice::getManufacturer, kw));
        }
        if (isp != null && !isp.isBlank()) {
            query.eq(CpeDevice::getIsp, isp.trim());
        }
        query.orderByDesc(CpeDevice::getLastContactTime);
        Page<CpeDevice> pager = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 500));
        Page<CpeDevice> result = deviceMapper.selectPage(pager, query);
        List<Map<String, Object>> items = new ArrayList<>(result.getRecords().size());
        for (CpeDevice device : result.getRecords()) {
            items.add(deviceView(device));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("size", result.getSize());
        data.put("items", items);
        return data;
    }

    /**
     * 单台设备的完整视图：台账 + 会话状态 + 数据模型。
     */
    public Map<String, Object> device(String sn) {
        CpeDevice device = findDevice(sn);
        if (device == null) {
            return null;
        }
        Map<String, Object> data = deviceView(device);
        data.put("capability", capabilityService.summary(sn));
        return data;
    }

    /**
     * 在线状态：会话存在且未超时才算在线。
     */
    public Map<String, Object> online(String sn) {
        CpeSession session = sessionManager.find(sn);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        data.put("online", session != null && session.isOnline());
        data.put("state", session == null ? "offline" : session.getState().name());
        data.put("lastContactTime", session == null ? null : session.getLastContactTime());
        return data;
    }

    /**
     * 能力树的某一层：返回 parent 的直接子节点，调用方从根节点开始逐级展开。
     *
     * @param parent 父路径，传 null 或空时返回根节点层
     */
    public List<Map<String, Object>> capabilityTree(String sn, String parent) {
        LambdaQueryWrapper<CpeParameter> query = new LambdaQueryWrapper<CpeParameter>()
                .eq(CpeParameter::getSn, sn)
                .orderByAsc(CpeParameter::getParamPath)
                .last("LIMIT " + MAX_DETAIL);
        if (parent == null || parent.isBlank()) {
            query.eq(CpeParameter::getParentPath, "").or().isNull(CpeParameter::getParentPath);
        } else {
            query.eq(CpeParameter::getParentPath, parent.trim());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (CpeParameter row : parameterMapper.selectList(query)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("path", row.getParamPath());
            view.put("name", row.getNodeName());
            view.put("nodeType", row.getNodeType());
            view.put("writable", row.getWritable() != null && row.getWritable() == 1);
            view.put("value", row.getParamValue());
            items.add(view);
        }
        return items;
    }

    /**
     * 能力树上的参数值：支持按前缀过滤。
     */
    public List<Map<String, Object>> params(String sn, String prefix) {
        LambdaQueryWrapper<CpeParameter> query = new LambdaQueryWrapper<CpeParameter>()
                .eq(CpeParameter::getSn, sn)
                .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                .orderByAsc(CpeParameter::getParamPath)
                .last("LIMIT " + MAX_DETAIL);
        if (prefix != null && !prefix.isBlank()) {
            query.likeRight(CpeParameter::getParamPath, prefix.trim());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (CpeParameter row : parameterMapper.selectList(query)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("path", row.getParamPath());
            view.put("value", row.getParamValue());
            items.add(view);
        }
        return items;
    }

    /**
     * 业务快照：WAN / WiFi / SIP 分区，数据来源是能力树快照，不与设备交互。
     */
    public Map<String, Object> business(String sn) {
        return businessService.summary(sn);
    }

    /**
     * 该设备的下发任务历史，最新的在前。
     */
    public List<Map<String, Object>> tasks(String sn, int limit) {
        List<DeviceTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<DeviceTask>()
                .eq(DeviceTask::getSn, sn)
                .orderByDesc(DeviceTask::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
        List<Map<String, Object>> items = new ArrayList<>(tasks.size());
        for (DeviceTask task : tasks) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", task.getId());
            view.put("taskNo", task.getTaskNo());
            view.put("category", task.getCategory());
            view.put("status", task.getStatus());
            view.put("errorMsg", task.getErrorMsg());
            view.put("source", task.getSource());
            view.put("createdAt", task.getCreatedAt());
            view.put("finishedAt", task.getFinishedAt());
            items.add(view);
        }
        return items;
    }

    /**
     * 工单执行结果查询。工单表是历史链路留下的执行记录，北向按设备或外部单号查。
     */
    public List<Map<String, Object>> workOrders(String sn, String worklistDesc, int limit) {
        LambdaQueryWrapper<Worklist> query = new LambdaQueryWrapper<Worklist>()
                .orderByDesc(Worklist::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        if (sn != null && !sn.isBlank()) {
            query.eq(Worklist::getSn, sn.trim());
        }
        if (worklistDesc != null && !worklistDesc.isBlank()) {
            query.eq(Worklist::getWorklistDesc, worklistDesc.trim());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Worklist row : worklistMapper.selectList(query)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.getId());
            view.put("worklistDesc", row.getWorklistDesc());
            view.put("worklistName", row.getWorklistName());
            view.put("sn", row.getSn());
            view.put("isp", row.getIsp());
            view.put("domain", row.getDomain());
            view.put("status", row.getStatus());
            view.put("createdAt", row.getCreatedAt());
            items.add(view);
        }
        return items;
    }

    public CpeDevice findDevice(String sn) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn)
                .last("LIMIT 1"));
    }

    private Map<String, Object> deviceView(CpeDevice device) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sn", device.getSn());
        view.put("oui", device.getOui());
        view.put("manufacturer", device.getManufacturer());
        view.put("productClass", device.getProductClass());
        view.put("softwareVersion", device.getSoftwareVersion());
        view.put("hardwareVersion", device.getHardwareVersion());
        view.put("isp", device.getIsp());
        view.put("status", device.getStatus());
        view.put("lastContactTime", device.getLastContactTime());
        // 数据模型写清楚：调用方据此判断该按 TR-098 还是 TR-181 解读能力树路径
        String root = device.getRootNode();
        view.put("rootNode", root);
        view.put("dataModel", DataModel.ofRoot(root).getLabel());
        CpeSession session = sessionManager.find(device.getSn());
        view.put("online", session != null && session.isOnline());
        return view;
    }
}
