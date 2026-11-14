package com.tr069.acs.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.service.DeviceAccountService;
import com.tr069.acs.acs.service.DeviceBusinessService;
import com.tr069.acs.acs.service.DeviceTelnetService;
import com.tr069.acs.acs.service.DirectDispatchService;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.common.PageResult;
import com.tr069.acs.common.Result;
import com.tr069.acs.cwmp.RpcMethod;
import com.tr069.acs.exec.DeviceTaskProgressService;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.queue.RpcTaskQueue;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.api.service.DeviceListService;
import com.tr069.acs.worklist.model.WifiEdit;
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

/** 设备管理接口：设备台账查询与 CWMP 方法手动下发 */
@Slf4j
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final CpeDeviceMapper cpeDeviceMapper;
    private final SessionManager sessionManager;
    private final RpcDispatchService rpcDispatchService;
    private final RpcTaskQueue rpcTaskQueue;
    private final DeviceBusinessService businessService;
    private final DeviceAccountService accountService;
    private final DeviceTelnetService telnetService;
    private final DeviceTaskProgressService taskProgressService;
    private final DeviceListService deviceListService;
    private final DirectDispatchService directDispatchService;
    private final com.tr069.acs.acs.service.DeviceEventRecorder eventRecorder;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 设备页直接下发：逐台受理即发，不建工单；入参 sns / category / values / templateId / wifi / username */
    @PostMapping("/dispatch")
    public Result<Map<String, Object>> dispatch(@RequestBody Map<String, Object> body) {
        List<String> sns = new ArrayList<>();
        Object snsRaw = body.get("sns");
        if (snsRaw instanceof List) {
            for (Object s : (List<?>) snsRaw) {
                if (s != null && !s.toString().isBlank()) {
                    sns.add(s.toString().trim());
                }
            }
        } else if (snsRaw != null) {
            for (String s : snsRaw.toString().split("[,;\\s]+")) {
                if (!s.isBlank()) {
                    sns.add(s.trim());
                }
            }
        }
        if (sns.isEmpty()) {
            return Result.failure("缺少设备序列号");
        }
        String category = body.get("category") == null ? "" : body.get("category").toString();
        Long templateId = body.get("templateId") == null ? null
                : Long.valueOf(String.valueOf(body.get("templateId")));
        String username = body.get("username") == null ? "admin" : body.get("username").toString();
        Map<String, String> values = new LinkedHashMap<>();
        if (body.get("values") instanceof Map) {
            // lambda 捕获要求事实终值，先落到局部变量再填
            final Map<String, String> captured = values;
            ((Map<?, ?>) body.get("values")).forEach((k, v) ->
                    captured.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        // WLAN 编辑的业务字段：翻译成业务类型与意图键，与工单通道同款
        if (body.get("wifi") instanceof Map) {
            WifiEdit wifi = objectMapper.convertValue(body.get("wifi"), WifiEdit.class);
            if (wifi.getSsid() == null || wifi.getSsid().isBlank()) {
                return Result.failure("SSID 名称不能为空");
            }
            category = wifi.toCategory();
            values = wifi.toValues();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int failed = 0;
        for (String sn : sns) {
            Map<String, Object> row = directDispatchService.dispatchOne(sn, category, templateId,
                    values, username);
            Object status = row.get("status");
            if (DeviceTask.STATUS_REJECTED.equals(status) || "fail".equals(status)) {
                failed++;
            }
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submitted", rows.size() - failed);
        data.put("failed", failed);
        data.put("rows", rows);
        return Result.success(data);
    }

    /** 一批设备当前正在进行的下发任务，列表页每行用它显示「正在下发 xxx」 */
    @GetMapping("/tasks/active")
    public Result<Map<String, Map<String, Object>>> activeTasks(@RequestParam String sns) {
        List<String> snList = java.util.Arrays.stream(sns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (snList.isEmpty()) {
            return Result.success(Map.of());
        }
        return Result.success(taskProgressService.activeBatch(snList));
    }

    /** 可下发的 CWMP 方法清单（下拉建议项，仍允许手工输入其它方法） */
    @GetMapping("/rpc/methods")
    public Result<List<String>> rpcMethods() {
        return Result.success(List.of(
                RpcMethod.GET_RPC_METHODS.getValue(),
                RpcMethod.GET_PARAMETER_NAMES.getValue(),
                RpcMethod.GET_PARAMETER_VALUES.getValue(),
                RpcMethod.SET_PARAMETER_VALUES.getValue(),
                RpcMethod.GET_PARAMETER_ATTRIBUTES.getValue(),
                RpcMethod.SET_PARAMETER_ATTRIBUTES.getValue(),
                RpcMethod.ADD_OBJECT.getValue(),
                RpcMethod.DELETE_OBJECT.getValue(),
                RpcMethod.REBOOT.getValue(),
                RpcMethod.FACTORY_RESET.getValue(),
                RpcMethod.DOWNLOAD.getValue(),
                RpcMethod.UPLOAD.getValue(),
                RpcMethod.SCHEDULE_INFORM.getValue(),
                RpcMethod.CHANGE_DU_STATE.getValue()));
    }

    /** 快捷操作的「命令 → RPC」映射，页面菜单 command 与下发方法由这里对上 */
    @GetMapping("/rpc/quick-commands")
    public Result<Map<String, String>> quickCommands() {
        Map<String, String> commands = new LinkedHashMap<>();
        commands.put("probe", RpcMethod.GET_RPC_METHODS.getValue());
        commands.put("reboot", RpcMethod.REBOOT.getValue());
        commands.put("batch-reboot", RpcMethod.REBOOT.getValue());
        commands.put("factoryReset", RpcMethod.FACTORY_RESET.getValue());
        commands.put("factory-reset", RpcMethod.FACTORY_RESET.getValue());
        commands.put("factory-clear", RpcMethod.FACTORY_RESET.getValue());
        commands.put("deep-factory-reset", RpcMethod.FACTORY_RESET.getValue());
        commands.put("deep-factory-clear", RpcMethod.FACTORY_RESET.getValue());
        return Result.success(commands);
    }

    /** 列表页的轮询节奏：几秒刷一次列表、任务、业务概要、RPC 结果 */
    @GetMapping("/poll-config")
    public Result<Map<String, Object>> pollConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        // 列表（在线状态、顶栏统计）
        config.put("listSeconds", 15);
        // 下发任务进度：比列表快，下发只有几秒到几十秒
        config.put("taskSeconds", 5);
        // 业务概要回读：与后端 LAN 网口状态自动刷新（5 分钟）同频
        config.put("bizSeconds", 300);
        // 单条 RPC 结果轮询与最大等待次数（列表页行内提示）
        config.put("rpcSeconds", 3);
        config.put("rpcMaxAttempts", 60);
        // 设备详情页盯单个任务：2 秒一次，最多等约 5 分钟
        config.put("detailRpcSeconds", 2);
        config.put("detailRpcMaxAttempts", 150);
        return Result.success(config);
    }

    /** RPC 行内提示的措辞：设备上正在干什么、干完/失败怎么说 */
    @GetMapping("/rpc/progress-labels")
    public Result<Map<String, Map<String, String>>> rpcProgressLabels() {
        Map<String, Map<String, String>> labels = new LinkedHashMap<>();
        labels.put(RpcMethod.REBOOT.getValue(), Map.of(
                "running", "正在重启", "success", "重启完成", "fail", "重启失败"));
        labels.put(RpcMethod.FACTORY_RESET.getValue(), Map.of(
                "running", "正在恢复出厂", "success", "恢复出厂完成", "fail", "恢复出厂失败"));
        // 没登记的方法按这句说，页面不再自己拼
        labels.put("default", Map.of(
                "running", "正在下发", "success", "下发完成", "fail", "下发失败"));
        return Result.success(labels);
    }

    /** 设备台账统计：总数 / 在线 / 离线 */
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

    /** 设备分页查询，支持按序列号、房间号模糊匹配与运营商筛选 */
    @GetMapping("/page")
    public Result<PageResult<CpeDevice>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String isp,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String roomNo,
            @RequestParam(required = false) String optic,
            @RequestParam(required = false) String sortKey,
            @RequestParam(required = false) String sortOrder) {
        // 排序 / 光模块筛选都在后端：台账列进 SQL，派生列走业务快照（见 DeviceListService）
        return Result.success(deviceListService.page(page, size, sn, isp, deviceType, roomNo,
                optic, sortKey, sortOrder));
    }

    /** 导出台账 CSV：列、在线文案、时间格式全在后端定，页面只负责把文本存成文件 */
    @GetMapping("/export")
    public Result<Map<String, Object>> export(
            @RequestParam(required = false) String sns,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String isp,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String roomNo) {
        LambdaQueryWrapper<CpeDevice> wrapper = new LambdaQueryWrapper<>();
        if (isNotBlank(sns)) {
            List<String> snList = java.util.Arrays.stream(sns.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (!snList.isEmpty()) {
                wrapper.in(CpeDevice::getSn, snList);
            }
        } else {
            wrapper.like(isNotBlank(sn), CpeDevice::getSn, sn)
                    .like(isNotBlank(roomNo), CpeDevice::getRoomNo, roomNo)
                    .eq(isNotBlank(isp), CpeDevice::getIsp, isp)
                    .eq(isNotBlank(deviceType), CpeDevice::getDeviceType, deviceType);
        }
        wrapper.orderByDesc(CpeDevice::getLastContactTime).last("limit 5000");
        List<CpeDevice> records = cpeDeviceMapper.selectList(wrapper);
        StringBuilder csv = new StringBuilder("SN,OUI,运营商,型号,硬件版本,固件版本,状态,注册时间,最近上报");
        for (CpeDevice device : records) {
            csv.append('\n')
                    .append(cell(device.getSn())).append(',')
                    .append(cell(device.getOui())).append(',')
                    .append(cell(device.getIsp())).append(',')
                    .append(cell(device.getProductClass() == null ? device.getDeviceType() : device.getProductClass())).append(',')
                    .append(cell(device.getHardwareVersion())).append(',')
                    .append(cell(device.getSoftwareVersion())).append(',')
                    .append(CpeDevice.onlineOf(device.getStatus()) ? "在线" : "离线").append(',')
                    .append(cell(timeText(device.getCreatedAt()))).append(',')
                    .append(cell(timeText(device.getLastContactTime())));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileName", "devices.csv");
        data.put("content", csv.toString());
        data.put("count", records.size());
        return Result.success(data);
    }

    /** CSV 单元格：逗号、引号、换行都要转义，否则一行会被拆成两列 */
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String timeText(java.time.LocalDateTime time) {
        return time == null ? ""
                : time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 批量业务信息：设备列表页整页一次拉取 WAN/WLAN/SIP 概要，避免逐行发请求 */
    @GetMapping("/business/batch")
    public Result<Map<String, Map<String, Object>>> businessBatch(@RequestParam String sns) {
        List<String> snList = java.util.Arrays.stream(sns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(200)
                .toList();
        return Result.success(businessService.summaryBatch(snList));
    }

    /** 设备详情，同时回带在线会话的实时属性 */
    @GetMapping("/{sn}")
    public Result<Map<String, Object>> detail(@PathVariable String sn) {
        CpeDevice device = cpeDeviceMapper.selectOne(
                new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, sn));
        if (device == null) {
            // 日志带上 SN：前端提示和日志里都得能看出是哪台
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

    /** 设备在线状态 */
    @GetMapping("/{sn}/status")
    public Result<Map<String, Object>> status(@PathVariable String sn) {
        CpeSession session = sessionManager.find(sn);
        Map<String, Object> data = new HashMap<>();
        data.put("sn", sn);
        data.put("online", session != null && session.isOnline());
        data.put("state", session == null ? "offline" : session.getState().name());
        return Result.success(data);
    }

    /** 在线会话列表 */
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

    /** 保存设备的房间号与备注 */
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

    /** 房间号与备注的保存入参。两个字段都允许传空串表示清空 */
    @Data
    public static class RemarkRequest {

        private String roomNo;

        private String remark;
    }

    /** 查询设备本地账号（超管 telecomadmin 等） */
    @GetMapping("/{sn}/admin-account")
    public Result<Map<String, Object>> adminAccount(@PathVariable String sn) {
        Map<String, Object> data = new LinkedHashMap<>(accountService.queryAdminAccount(sn));
        // 状态中文与标签色在此统一补：页面原来自己维护码表，后端加分支就会显示成「未知」
        String status = data.get("status") == null ? "" : String.valueOf(data.get("status"));
        data.put("statusText", adminStatusText(status));
        data.put("statusLevel", adminStatusLevel(status));
        return Result.success(data);
    }

    /** 查询设备 Telnet 使能状态 */
    @GetMapping("/{sn}/telnet")
    public Result<Map<String, Object>> telnetStatus(@PathVariable String sn) {
        Map<String, Object> data = new LinkedHashMap<>(telnetService.queryTelnetStatus(sn));
        String status = data.get("status") == null ? "" : String.valueOf(data.get("status"));
        data.put("statusText", telnetStatusText(status));
        data.put("statusLevel", telnetStatusLevel(status));
        return Result.success(data);
    }

    /** 开启或关闭设备 Telnet */
    @PostMapping("/{sn}/telnet")
    public Result<Map<String, Object>> telnetSwitch(@PathVariable String sn,
                                                    @RequestBody(required = false) TelnetRequest request) {
        boolean enabled = request != null && Boolean.TRUE.equals(request.getEnabled());
        Map<String, Object> data = telnetService.setTelnetEnabled(sn, enabled);
        String status = data.get("status") == null ? "" : String.valueOf(data.get("status"));
        data.put("statusText", telnetStatusText(status));
        data.put("statusLevel", telnetStatusLevel(status));
        return Result.success(data);
    }

    /**
     * 查询 Telnet 状态及默认账号密码。
     * 未开启时会自动下发开启指令，开启成功后再读取账号 / 密码 / 端口。
     */
    @GetMapping("/{sn}/telnet/credentials")
    public Result<Map<String, Object>> telnetCredentials(@PathVariable String sn) {
        Map<String, Object> data = new LinkedHashMap<>(telnetService.queryTelnetCredentials(sn));
        String status = data.get("status") == null ? "" : String.valueOf(data.get("status"));
        data.put("statusText", telnetStatusText(status));
        data.put("statusLevel", telnetStatusLevel(status));
        String credStatus = data.get("credentialStatus") == null ? ""
                : String.valueOf(data.get("credentialStatus"));
        data.put("credentialStatusText", telnetStatusText(credStatus));
        data.put("credentialStatusLevel", telnetStatusLevel(credStatus));
        return Result.success(data);
    }

    /** Telnet 状态中文：已开启 / 已关闭 / 未读到 / 超时 / 失败 / 离线 */
    private static String telnetStatusText(String status) {
        return switch (status) {
            case "success" -> "已读取";
            case "queued" -> "已下发";
            case "empty" -> "未读到";
            case "timeout" -> "超时";
            case "fail" -> "失败";
            case "offline" -> "离线";
            default -> status;
        };
    }

    private static String telnetStatusLevel(String status) {
        return switch (status) {
            case "success", "queued" -> "success";
            case "empty", "offline" -> "info";
            case "timeout" -> "warning";
            case "fail" -> "danger";
            default -> "info";
        };
    }

    /** Telnet 开关入参 */
    @Data
    public static class TelnetRequest {
        private Boolean enabled;
    }

    /** 超管账号查询结果的中文：查到了 / 没账号 / 超时 / 失败 / 设备离线 */
    private static String adminStatusText(String status) {
        return switch (status) {
            case "success" -> "已获取";
            case "empty" -> "无账号";
            case "timeout" -> "超时";
            case "fail" -> "失败";
            case "offline" -> "离线";
            default -> "未知";
        };
    }

    /** 对应的标签配色：成功绿、无账号灰、超时橙、失败红、离线灰 */
    private static String adminStatusLevel(String status) {
        return switch (status) {
            case "success" -> "success";
            case "empty", "offline" -> "info";
            case "timeout" -> "warning";
            case "fail" -> "danger";
            default -> "info";
        };
    }

    /** 手动下发一条 CWMP RPC */
    @PostMapping("/{sn}/rpc/{rpcName}")
    public Result<Map<String, Object>> rpc(@PathVariable String sn,
                                           @PathVariable String rpcName,
                                           @RequestBody(required = false) Map<String, Object> args) {
        // 提交即返回，设备可能几十秒后才来空 POST 取走指令，不挂请求线程等回应
        RpcDispatchService.DispatchHandle handle = rpcDispatchService.submitAsync(
                sn, rpcName, args == null ? Map.of() : args, null);
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", handle.getTaskId());
        data.put("queued", handle.isQueued());
        return Result.success(data);
    }

    /** IPPing 诊断对象在 TR-181 下的挂载位置 */
    private static final String PING_BASE_TR181 = "Device.IP.Diagnostics.IPPing.";

    /** IPPing 诊断对象在 TR-098 下的挂载位置（直接挂在根节点下，没有 IP.Diagnostics 这一层） */
    private static final String PING_BASE_TR098 = "InternetGatewayDevice.IPPingDiagnostics.";

    /** TraceRoute 诊断对象在 TR-181 下的挂载位置 */
    private static final String TRACE_BASE_TR181 = "Device.IP.Diagnostics.TraceRoute.";

    /** TraceRoute 诊断对象在 TR-098 下的挂载位置 */
    private static final String TRACE_BASE_TR098 = "InternetGatewayDevice.TraceRouteDiagnostics.";

    /** 下发 IPPing 诊断 */
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

    /** 诊断对象挂载位置，按设备登记的数据模型选 */
    private String pingBase(String sn) {
        return DataModel.TR181 == DataModel.ofRoot(rootNodeOf(sn)) ? PING_BASE_TR181 : PING_BASE_TR098;
    }

    /** TraceRoute 诊断对象挂载位置 */
    private String traceBase(String sn) {
        return DataModel.TR181 == DataModel.ofRoot(rootNodeOf(sn)) ? TRACE_BASE_TR181 : TRACE_BASE_TR098;
    }

    /** 下发 TraceRoute 诊断（TR-143） */
    @PostMapping("/{sn}/diagnostics/trace")
    public Result<Map<String, Object>> traceDiagnostics(@PathVariable String sn,
                                                         @RequestBody(required = false) PingRequest request) {
        String host = request == null ? null : request.getHost();
        if (host == null || host.isBlank()) {
            return Result.failure("缺少诊断目标地址");
        }
        String target = host.trim();
        String base = traceBase(sn);
        List<Map<String, String>> params = new ArrayList<>();
        params.add(paramEntry(base + "Host", target, "string"));
        params.add(paramEntry(base + "NumberOfTries", "3", "unsignedInt"));
        params.add(paramEntry(base + "Timeout", "5000", "unsignedInt"));
        params.add(paramEntry(base + "DataBlockSize", "32", "unsignedInt"));
        // 必须最后写：设备看到 Requested 就开跑，前面参数没写进去会拿空 Host 去诊断
        params.add(paramEntry(base + "DiagnosticsState", "Requested", "string"));
        RpcDispatchService.DispatchHandle handle =
                rpcDispatchService.submitAsync(sn, "SetParameterValues",
                        spvArgs("acs-trace", params), null);
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", handle.getTaskId());
        data.put("target", target);
        data.put("path", base);
        return Result.success(data);
    }

    private String rootNodeOf(String sn) {
        CpeDevice device = cpeDeviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn)
                .last("LIMIT 1"));
        return device == null ? null : device.getRootNode();
    }

    /** 手动设置若干 CWMP 参数 */
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

    /** {@code SetParameterValues} 的入参 */
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

    /** IPPing 诊断入参 */
    @Data
    public static class PingRequest {
        /** 目标 IP 或域名 */
        private String host;
    }

    /** 自定义参数下发入参 */
    @Data
    public static class ParamSetRequest {
        /** 参数键，设备回传时会带上，便于把回应与本次下发对上 */
        private String key;
        private List<ParamItem> items;
    }

    /** 一条待设置的参数：路径 + 值 + xsd 类型 */
    @Data
    public static class ParamItem {
        private String name;
        private String value;
        private String type;
    }

    // ==================== 设备事件与 syslog 查询 ====================

    /** 设备事件流水：重启、连接请求、下发唤醒、会话异常等（cpe_device_event） */
    @GetMapping("/{sn}/events")
    public Result<List<Map<String, Object>>> events(@PathVariable String sn,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return Result.success(eventRecorder.events(sn.trim(), limit));
    }

    /** 设备侧 syslog 原文（cpe_device_syslog，UDP 采集） */
    @GetMapping("/{sn}/syslog")
    public Result<List<Map<String, Object>>> syslog(@PathVariable String sn,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return Result.success(eventRecorder.syslogs(sn.trim(), limit));
    }
}
