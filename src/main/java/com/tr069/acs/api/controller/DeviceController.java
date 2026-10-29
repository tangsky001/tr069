package com.tr069.acs.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.service.DeviceAccountService;
import com.tr069.acs.acs.service.DeviceBusinessService;
import com.tr069.acs.acs.service.DeviceTagService;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.common.PageResult;
import com.tr069.acs.common.Result;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.queue.RpcTaskQueue;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备管理接口：设备台账查询与 CWMP 方法手动下发。
 */
@Slf4j
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final CpeDeviceMapper cpeDeviceMapper;
    private final DeviceTagService tagService;
    private final SessionManager sessionManager;
    private final RpcDispatchService rpcDispatchService;
    private final RpcTaskQueue rpcTaskQueue;
    private final DeviceBusinessService businessService;
    private final DeviceAccountService accountService;

    /**
     * 设备台账统计：总数 / 在线 / 离线。
     *
     * <p>口径与列表每一行的在线标签一致，都取 {@code cpe_device.status}：
     * 顶上这三个数就是列表里绿标灰标的数量，两者不会打架。首页概览用的是
     * 「最近上报落在窗口内」的另一套口径，那边统计的是业务在线率，不要混用。</p>
     */
    @GetMapping("/stat")
    public Result<Map<String, Object>> stat() {
        long total = cpeDeviceMapper.selectCount(new LambdaQueryWrapper<>());
        long online = cpeDeviceMapper.selectCount(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getStatus, "online"));
        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("online", online);
        data.put("offline", Math.max(0, total - online));
        return Result.success(data);
    }

    /**
     * 设备分页查询，支持按序列号模糊匹配与运营商筛选。
     */
    @GetMapping("/page")
    public Result<PageResult<CpeDevice>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String isp,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String tag) {
        LambdaQueryWrapper<CpeDevice> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(isNotBlank(sn), CpeDevice::getSn, sn)
                .eq(isNotBlank(isp), CpeDevice::getIsp, isp)
                .eq(isNotBlank(deviceType), CpeDevice::getDeviceType, deviceType)
                .orderByDesc(CpeDevice::getLastContactTime);
        if (isNotBlank(tag)) {
            // 标签在另一张表，先按标签把序列号取出来再筛；
            // 标签下面一台设备都没有就直接回空页，不必把台账扫一遍
            List<String> sns = tagService.devicesOf(tag);
            if (sns.isEmpty()) {
                return Result.success(PageResult.of(List.of(), 0, page, size));
            }
            wrapper.in(CpeDevice::getSn, sns);
        }

        IPage<CpeDevice> result = cpeDeviceMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(PageResult.of(result.getRecords(), result.getTotal(), page, size));
    }

    /**
     * 批量业务信息：设备列表页整页一次拉取 WAN/WLAN/SIP 概要，避免逐行发请求。
     *
     * <p>sns 为逗号分隔的序列号，单台失败不影响其余。</p>
     */
    @GetMapping("/business/batch")
    public Result<Map<String, Map<String, Object>>> businessBatch(@RequestParam String sns) {
        List<String> snList = java.util.Arrays.stream(sns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(200)
                .toList();
        return Result.success(businessService.summaryBatch(snList));
    }

    /**
     * 设备详情，同时回带在线会话的实时属性。
     */
    @GetMapping("/{sn}")
    public Result<Map<String, Object>> detail(@PathVariable String sn) {
        CpeDevice device = cpeDeviceMapper.selectOne(
                new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, sn));
        if (device == null) {
            // 带上 SN：清过型号档案、设备还没重新上报时这里会命中，
            // 前端提示和日志里都得能看出是哪台
            log.info("设备详情查询不到台账: sn={}", sn);
            return Result.failure("设备不存在: " + sn);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("device", device);

        CpeSession session = sessionManager.find(sn);
        if (session != null) {
            Map<String, Object> live = new HashMap<>();
            live.put("online", session.isOnline());
            live.put("state", session.getState());
            live.put("lastContactTime", session.getLastContactTime());
            live.put("pendingCount", session.getOutbox().size());
            data.put("session", live);
        }
        return Result.success(data);
    }

    /**
     * 设备在线状态。
     */
    @GetMapping("/{sn}/status")
    public Result<Map<String, Object>> status(@PathVariable String sn) {
        CpeSession session = sessionManager.find(sn);
        Map<String, Object> data = new HashMap<>();
        data.put("sn", sn);
        data.put("online", session != null && session.isOnline());
        data.put("state", session == null ? "offline" : session.getState().name());
        return Result.success(data);
    }

    /**
     * 在线会话列表。
     */
    @GetMapping("/online")
    public Result<List<Map<String, Object>>> online() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CpeSession session : sessionManager.allSessions()) {
            if (!session.isOnline()) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("sn", session.getSn());
            item.put("oui", session.getOui());
            item.put("state", session.getState().name());
            item.put("softwareVersion", session.getSoftwareVersion());
            item.put("hardwareVersion", session.getHardwareVersion());
            item.put("connectionRequestUrl", session.getConnectionRequestUrl());
            item.put("lastContactTime", session.getLastContactTime());
            // 待下发指令已放到 Redis 队列，不再统计内存 outbox
            item.put("pendingCount", rpcTaskQueue.pendingCount(session.getSn()));
            list.add(item);
        }
        return Result.success(list);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 保存设备的房间号与备注。
     *
     * <p>房间号是下发时替换模板变量 {@code %room%} 的取值来源，例如把备注设为 0001，
     * 下发 WiFi 名称时就会用 0001 替换掉模板里的 {@code %room%}。两者都是台账侧的
     * 运维字段，不参与 CWMP 交互，因此直接改设备表即可，不需要设备在线。</p>
     */
    @PostMapping("/{sn}/remark")
    public Result<Boolean> updateRemark(@PathVariable String sn,
                                        @RequestBody(required = false) RemarkRequest request) {
        CpeDevice device = cpeDeviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn));
        if (device == null) {
            return Result.failure("设备不存在: " + sn);
        }
        if (request != null) {
            device.setRoomNo(request.getRoomNo());
            device.setRemark(request.getRemark());
        }
        int rows = cpeDeviceMapper.updateById(device);
        log.info("更新设备房间号/备注: sn={}, roomNo={}, remark={}",
                sn, device.getRoomNo(), device.getRemark());
        return Result.success(rows > 0);
    }

    /**
     * 房间号与备注的保存入参。两个字段都允许传空串表示清空。
     */
    @Data
    public static class RemarkRequest {

        private String roomNo;

        private String remark;
    }

    /**
     * 查询设备本地账号（超管 telecomadmin 等）。
     *
     * <p>每台设备的超管口令可能不一样，只能到设备上现读。这里对在线设备下发
     * {@code GetParameterValues} 读 {@code User.{i}.Username} / {@code Password}，
     * 账号与口令都是明文返回，前端直接展示给运维登后台用。</p>
     *
     * <p>请求会挂起等设备回值，最长 {@code acs.device.account.read-timeout-seconds}
     * （默认 30s）；设备离线立即返回 offline，不空等。</p>
     *
     * @return {@code accounts}：账号列表，每项 instance / path / username / password；
     *         {@code status}：success / empty / timeout / fail / offline
     */
    @GetMapping("/{sn}/admin-account")
    public Result<Map<String, Object>> adminAccount(@PathVariable String sn) {
        return Result.success(accountService.queryAdminAccount(sn));
    }

    /**
     * 手动下发一条 CWMP RPC。
     */
    @PostMapping("/{sn}/rpc/{rpcName}")
    public Result<Map<String, Object>> rpc(@PathVariable String sn,
                                           @PathVariable String rpcName,
                                           @RequestBody(required = false) Map<String, Object> args) {
        // 提交即返回，不再挂起请求线程等设备回应：
        // 设备可能几十秒后才来空 POST 把指令取走
        RpcDispatchService.DispatchHandle handle = rpcDispatchService.submitAsync(
                sn, rpcName, args == null ? Map.of() : args, null);
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", handle.getTaskId());
        data.put("queued", handle.isQueued());
        return Result.success(data);
    }

    /** IPPing 诊断对象在 TR-181 下的挂载位置。 */
    private static final String PING_BASE_TR181 = "Device.IP.Diagnostics.IPPing.";

    /** IPPing 诊断对象在 TR-098 下的挂载位置（直接挂在根节点下，没有 IP.Diagnostics 这一层）。 */
    private static final String PING_BASE_TR098 = "InternetGatewayDevice.IPPingDiagnostics.";

    /**
     * 下发 IPPing 诊断。
     *
     * <p>诊断对象的挂载位置两个数据模型完全不同：TR-181 藏在 {@code Device.IP.Diagnostics.} 下，
     * TR-098 直接挂在 {@code InternetGatewayDevice.} 下。路径写错设备回 9005（无效参数名），
     * 页面上只看得到「诊断没跑」，分不清是路径错了还是设备没执行——
     * 这个分支因此放在后端：只有后端知道这台设备按哪种模型解读，页面传目标地址就行。</p>
     *
     * <p>三个参数必须一次性下发：只设 {@code DiagnosticsState=Requested} 而不先设 Host，
     * 设备会拿上一次残留的目标去 ping，页面显示的结果是对着错目标跑出来的。</p>
     */
    @PostMapping("/{sn}/diagnostics/ping")
    public Result<Map<String, Object>> pingDiagnostics(@PathVariable String sn,
                                                        @RequestBody(required = false) PingRequest request) {
        String host = request == null ? null : request.getHost();
        if (host == null || host.isBlank()) {
            return Result.failure("缺少诊断目标地址");
        }
        String target = host.trim();
        String base = pingBase(sn);
        List<Map<String, String>> params = new ArrayList<>();
        params.add(paramEntry(base + "Host", target, "string"));
        params.add(paramEntry(base + "NumberOfRepetitions", "4", "unsignedInt"));
        params.add(paramEntry(base + "DiagnosticsState", "Requested", "string"));
        RpcDispatchService.DispatchHandle handle =
                rpcDispatchService.submitAsync(sn, "SetParameterValues",
                        spvArgs("acs-ping", params), null);
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", handle.getTaskId());
        data.put("target", target);
        // 回给页面的是实际下发的路径前缀，「诊断没结果」时先看它对不对得上设备型号
        data.put("path", base);
        return Result.success(data);
    }

    /** 诊断对象挂载位置，按设备登记的数据模型选。 */
    private String pingBase(String sn) {
        return DataModel.TR181 == DataModel.ofRoot(rootNodeOf(sn)) ? PING_BASE_TR181 : PING_BASE_TR098;
    }

    private String rootNodeOf(String sn) {
        CpeDevice device = cpeDeviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn)
                .last("LIMIT 1"));
        return device == null ? null : device.getRootNode();
    }

    /**
     * 手动设置若干 CWMP 参数。
     *
     * <p>页面只提交「路径 + 值 + xsd 类型」三元组，{@code SetParameterValues} 的报文结构
     * 由后端拼。类型不填按 string 处理——设备的自检多半只认自己声明的类型，
     * 拿不准时在页面上选对类型，比让后端猜更直接。</p>
     */
    @PostMapping("/{sn}/params/set")
    public Result<Map<String, Object>> setParams(@PathVariable String sn,
                                                 @RequestBody(required = false) ParamSetRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            return Result.failure("缺少要设置的参数");
        }
        List<Map<String, String>> params = new ArrayList<>();
        for (ParamItem item : request.getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                return Result.failure("参数路径不能为空");
            }
            params.add(paramEntry(item.getName().trim(),
                    item.getValue() == null ? "" : item.getValue(),
                    item.getType() == null || item.getType().isBlank() ? "string" : item.getType().trim()));
        }
        RpcDispatchService.DispatchHandle handle =
                rpcDispatchService.submitAsync(sn, "SetParameterValues",
                        spvArgs(request.getKey(), params), null);
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", handle.getTaskId());
        data.put("count", params.size());
        return Result.success(data);
    }

    /**
     * {@code SetParameterValues} 的入参。
     *
     * <p>用 LinkedHashMap 是为了保住 Name → Value → Value_type 的顺序：
     * CWMP 的 ParameterValueStruct 是 sequence，元素写反了设备会拒收，
     * 而 Map.of() 的迭代顺序由哈希决定，不能保证。</p>
     */
    private static Map<String, Object> spvArgs(String key, List<Map<String, String>> params) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterKey", key == null || key.isBlank() ? "acs-param-set" : key.trim());
        args.put("ParameterList", params);
        return args;
    }

    private static Map<String, String> paramEntry(String name, String value, String type) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("Name", name);
        entry.put("Value", value);
        entry.put("Value_type", type);
        return entry;
    }

    /** IPPing 诊断入参。 */
    @Data
    public static class PingRequest {
        /** 目标 IP 或域名 */
        private String host;
    }

    /** 自定义参数下发入参。 */
    @Data
    public static class ParamSetRequest {
        /** 参数键，设备回传时会带上，便于把回应与本次下发对上 */
        private String key;
        private List<ParamItem> items;
    }

    /** 一条待设置的参数：路径 + 值 + xsd 类型。 */
    @Data
    public static class ParamItem {
        private String name;
        private String value;
        private String type;
    }
}
