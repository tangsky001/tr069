package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.adaptation.AdaptationManager;
import com.tr069.acs.acs.adaptation.IspAdaptation;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 光猫 Telnet 使能查询与开关。
 * <p>
 * 不同运营商/厂商的 Telnet 使能参数路径差异很大，统一在这里按适配策略探测：
 * <ul>
 *   <li>移动：DeviceInfo.X_CMCC_ServiceManage.TelnetEnable</li>
 *   <li>电信：DeviceInfo.X_CT-COM_ServiceManage.TelnetEnable</li>
 *   <li>联通：DeviceInfo.X_CU_ServiceManage.TelnetEnable</li>
 *   <li>通用：UserInterface.TelnetServer.Enable / DeviceInfo.X_HW_Telnet.Enable</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTelnetService {

    private static final String LEAF_ENABLE = "TelnetEnable";
    private static final String LEAF_STD_ENABLE = "Enable";
    /** Telnet 账号叶子名：移动/电信/联通定制节点统一写 TelnetUserName */
    private static final String LEAF_TELNET_USER = "TelnetUserName";
    private static final String LEAF_TELNET_PWD = "TelnetPassword";
    private static final String LEAF_TELNET_PORT = "TelnetPort";

    private final CpeDeviceMapper deviceMapper;
    private final CpeParameterMapper parameterMapper;
    private final SessionManager sessionManager;
    private final DeviceCapabilityService capabilityService;
    private final RpcDispatchService rpcDispatchService;
    private final AdaptationManager adaptationManager;

    @Value("${acs.device.telnet.read-timeout-seconds:30}")
    private long readTimeoutSeconds;

    @Value("${acs.device.telnet.total-timeout-seconds:70}")
    private long totalTimeoutSeconds;

    @Value("${acs.device.telnet.offline-wakeup-wait-seconds:30}")
    private long offlineWakeupWaitSeconds;

    @Value("${acs.device.telnet.wakeup-wait-seconds:15}")
    private long wakeupWaitSeconds;

    /** 下发开启 Telnet 后同步等待设备应答的时间（秒） */
    @Value("${acs.device.telnet.enable-wait-seconds:20}")
    private long enableWaitSeconds;

    /**
     * 查询设备 Telnet 当前是否开启。
     * 返回：sn, online, status(success/empty/timeout/fail/offline), enabled, path, message
     */
    public Map<String, Object> queryTelnetStatus(String sn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        String root = resolveRoot(sn);
        data.put("rootNode", root);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(totalTimeoutSeconds);

        CpeSession session = sessionManager.find(sn);
        boolean online = session != null && session.isOnline();
        data.put("online", online);
        if (!online) {
            if (!capabilityService.canWakeUp(sn)) {
                data.put("status", "offline");
                data.put("enabled", null);
                data.put("path", null);
                data.put("message", "设备离线且无法反向唤醒：台账里没有这台设备的连接请求地址");
                return data;
            }
            online = capabilityService.wakeUpAndAwait(sn,
                    Math.min(offlineWakeupWaitSeconds, remaining(deadline)));
            data.put("online", online);
            if (!online) {
                data.put("status", "offline");
                data.put("enabled", null);
                data.put("path", null);
                data.put("message", "设备离线，已发连接请求但 " + offlineWakeupWaitSeconds
                        + " 秒内没上来");
                return data;
            }
            log.info("设备原本离线，已被连接请求唤醒: sn={}", sn);
        } else {
            capabilityService.wakeUpAndAwait(sn, Math.min(wakeupWaitSeconds, remaining(deadline)));
        }

        List<String> candidates = telnetEnableCandidates(sn, root);
        Map<String, String> values = new LinkedHashMap<>();
        String status = "success";
        try {
            CompletableFuture<Map<String, String>> future =
                    capabilityService.readPathValues(sn, candidates);
            values.putAll(future.get(Math.min(readTimeoutSeconds, remaining(deadline)),
                    TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            status = "timeout";
            log.warn("读取 Telnet 状态超时: sn={}", sn);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "fail";
        } catch (Exception e) {
            status = "fail";
            log.warn("读取 Telnet 状态失败: sn={}, err={}", sn, e.getMessage());
        }

        // 整批被拒时逐条重试：避免设备因为某一条不认识的路径把整批都拒绝
        for (String path : candidates) {
            if (values.containsKey(path)) {
                continue;
            }
            if (expired(deadline)) {
                break;
            }
            try {
                values.putAll(capabilityService.readPathValues(sn, List.of(path))
                        .get(Math.min(8L, remaining(deadline)), TimeUnit.SECONDS));
            } catch (Exception e) {
                // 单条读不到就是设备没这个节点，跳过
            }
        }

        TelnetStatus found = parseStatus(values, candidates);
        if (found == null && "success".equals(status)) {
            status = "empty";
        }

        data.put("status", status);
        if (found != null) {
            data.put("enabled", found.enabled);
            data.put("path", found.path);
            data.put("message", "已读取到 Telnet 使能状态");
        } else {
            data.put("enabled", null);
            data.put("path", null);
            if ("timeout".equals(status)) {
                data.put("message", readTimeoutSeconds + " 秒内没有读到 Telnet 状态，稍后重试");
            } else if ("fail".equals(status)) {
                data.put("message", "读取 Telnet 状态失败，请查后端日志");
            } else {
                data.put("message", "设备未提供可识别的 Telnet 使能参数："
                        + String.join(", ", candidates));
            }
        }
        log.info("查询设备 Telnet 状态: sn={}, status={}, enabled={}", sn, status,
                found == null ? null : found.enabled);
        return data;
    }

    /**
     * 查询 Telnet 状态及默认账号密码。
     * <p>
     * 流程：
     * <ol>
     *   <li>读取 Telnet 使能状态（复用 {@link #queryTelnetStatus}）</li>
     *   <li>若已开启，直接读取账号 / 密码 / 端口</li>
     *   <li>若未开启，同步下发 SetParameterValues 开启，等待设备应答成功后再读取账号密码</li>
     * </ol>
     * 返回字段：在 queryTelnetStatus 基础上追加
     * username / password / port / credentialStatus(enabled/empty/timeout/fail)
     */
    public Map<String, Object> queryTelnetCredentials(String sn) {
        Map<String, Object> data = queryTelnetStatus(sn);
        data.putIfAbsent("credentialStatus", "empty");
        Object enabledObj = data.get("enabled");
        boolean enabled = Boolean.TRUE.equals(enabledObj);

        String status = String.valueOf(data.getOrDefault("status", ""));
        // 离线 / 读不到使能参数：账号密码也读不到，直接返回
        if (!"success".equals(status) && !"empty".equals(status)) {
            data.put("credentialStatus", status);
            data.put("username", null);
            data.put("password", null);
            data.put("port", null);
            return data;
        }

        String root = resolveRoot(sn);
        // 未开启：先同步下发开启，等设备应答成功再继续读账号密码
        if (!enabled) {
            String enablePath = data.get("path") instanceof String p && !p.isBlank()
                    ? p : telnetEnableCandidates(sn, root).stream().findFirst().orElse(null);
            if (enablePath == null) {
                data.put("credentialStatus", "fail");
                data.put("message", "找不到 Telnet 使能参数路径，无法自动开启");
                return data;
            }
            data.put("enablePath", enablePath);
            EnableOutcome outcome = enableAndWait(sn, enablePath);
            data.put("enableQueued", outcome.queued);
            data.put("enableTaskId", outcome.taskId);
            if (!outcome.success) {
                data.put("credentialStatus", "fail");
                data.put("username", null);
                data.put("password", null);
                data.put("port", null);
                data.put("message", outcome.reason);
                return data;
            }
            enabled = true;
            data.put("enabled", true);
            data.put("status", "success");
            data.put("message", "Telnet 未开启，已自动下发开启指令并读取默认账号密码");
        }

        // 读取账号 / 密码 / 端口：与使能参数同节点下，按候选路径逐个试
        Map<String, String> creds = readCredentials(sn, root,
                data.get("path") instanceof String p && !p.isBlank() ? p : null);
        data.putAll(creds);
        if (creds.get("username") == null && creds.get("password") == null) {
            data.put("credentialStatus", "empty");
        } else {
            data.put("credentialStatus", "enabled");
        }
        log.info("查询 Telnet 账号密码: sn={}, enabled={}, user={}, port={}", sn, enabled,
                creds.get("username"), creds.get("port"));
        return data;
    }

    /** 同步下发开启 Telnet 指令并等待设备应答 */
    private EnableOutcome enableAndWait(String sn, String enablePath) {
        List<Map<String, String>> params = List.of(paramEntry(enablePath, "true", "boolean"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterKey", "acs-telnet-on");
        args.put("ParameterList", params);
        try {
            SoapMessage resp = rpcDispatchService.dispatch(sn, "SetParameterValues", args, null);
            if (resp == null) {
                return new EnableOutcome(false, false, 0, "下发开启指令无响应");
            }
            if (resp.isFault()) {
                String reason = resp.getFault() == null ? "设备返回 Fault"
                        : "设备返回 Fault: " + resp.getFault().getFaultCode() + " "
                                + resp.getFault().getFaultString();
                return new EnableOutcome(true, false, 0, reason);
            }
            return new EnableOutcome(true, true, 0, "设备已应用开启指令");
        } catch (Exception e) {
            log.warn("同步下发开启 Telnet 失败: sn={}, err={}", sn, e.getMessage());
            return new EnableOutcome(false, false, 0, "下发开启指令异常: " + e.getMessage());
        }
    }

    /**
     * 读取 Telnet 账号 / 密码 / 端口。
     * 先按使能参数所在节点拼账号密码叶子路径；读不到再按全部候选节点试一遍。
     * 返回 Map 里字段：username / password / port
     */
    private Map<String, String> readCredentials(String sn, String root, String knownEnablePath) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("username", null);
        result.put("password", null);
        result.put("port", null);

        List<String> paths = new ArrayList<>();
        // 优先用读到的使能参数同节点：账号密码一般挂在同一个 ServiceManage 节点下
        if (knownEnablePath != null) {
            String parent = parentOf(knownEnablePath);
            paths.add(parent + LEAF_TELNET_USER);
            paths.add(parent + LEAF_TELNET_PWD);
            paths.add(parent + LEAF_TELNET_PORT);
        }
        // 兜底：所有运营商定制节点 + 快照里已有的 Telnet 账号路径
        for (String node : allTelnetNodes()) {
            paths.add(root + node + LEAF_TELNET_USER);
            paths.add(root + node + LEAF_TELNET_PWD);
            paths.add(root + node + LEAF_TELNET_PORT);
        }
        paths.addAll(snapshotCredentialPaths(sn));

        Map<String, String> values = new LinkedHashMap<>();
        try {
            CompletableFuture<Map<String, String>> future =
                    capabilityService.readPathValues(sn, paths);
            values.putAll(future.get(readTimeoutSeconds, TimeUnit.SECONDS));
        } catch (Exception e) {
            log.debug("整批读取 Telnet 账号密码失败，改为逐条重试: sn={}, err={}", sn, e.getMessage());
        }
        // 整批被拒时逐条重试
        for (String path : paths) {
            if (values.containsKey(path)) {
                continue;
            }
            try {
                values.putAll(capabilityService.readPathValues(sn, List.of(path))
                        .get(8L, TimeUnit.SECONDS));
            } catch (Exception e) {
                // 单条读不到就是设备没这个节点，跳过
            }
        }

        for (Map.Entry<String, String> e : values.entrySet()) {
            String leaf = DeviceCapabilityService.nameOf(e.getKey());
            String v = e.getValue();
            if (v == null || v.isBlank()) {
                continue;
            }
            if (LEAF_TELNET_USER.equalsIgnoreCase(leaf) && result.get("username") == null) {
                result.put("username", v);
            } else if (LEAF_TELNET_PWD.equalsIgnoreCase(leaf) && result.get("password") == null) {
                result.put("password", v);
            } else if (LEAF_TELNET_PORT.equalsIgnoreCase(leaf) && result.get("port") == null) {
                result.put("port", v);
            }
        }
        return result;
    }

    /** 收集所有适配实现里登记的 Telnet 节点（相对根，带尾点） */
    private List<String> allTelnetNodes() {
        LinkedHashSet<String> nodes = new LinkedHashSet<>();
        for (IspAdaptation a : adaptationManager.all()) {
            nodes.addAll(a.telnetEnableNodes());
        }
        return new ArrayList<>(nodes);
    }

    /** 能力树快照里真实存在的 Telnet 账号 / 密码 / 端口路径 */
    private List<String> snapshotCredentialPaths(String sn) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        try {
            List<CpeParameter> rows = parameterMapper.selectList(
                    new LambdaQueryWrapper<CpeParameter>()
                            .eq(CpeParameter::getSn, sn)
                            .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                            .and(w -> w.like(CpeParameter::getParamPath, "TelnetUserName")
                                    .or().like(CpeParameter::getParamPath, "TelnetPassword")
                                    .or().like(CpeParameter::getParamPath, "TelnetPort")));
            for (CpeParameter row : rows) {
                String path = row.getParamPath();
                if (path != null && !path.isBlank()) {
                    paths.add(path);
                }
            }
        } catch (Exception e) {
            log.debug("查询快照 Telnet 账号路径失败: sn={}, err={}", sn, e.getMessage());
        }
        return new ArrayList<>(paths);
    }

    /** 取参数路径的父节点（去掉末段叶子，保留尾点） */
    private static String parentOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('.');
        return idx < 0 ? p + "." : p.substring(0, idx + 1);
    }

    private record EnableOutcome(boolean queued, boolean success, long taskId, String reason) {
    }

    /**
     * 开启或关闭设备 Telnet。
     * 返回：sn, status(queued/fail/offline), taskId, path, message
     */
    public Map<String, Object> setTelnetEnabled(String sn, boolean enabled) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        String root = resolveRoot(sn);

        CpeSession session = sessionManager.find(sn);
        boolean online = session != null && session.isOnline();
        data.put("online", online);
        if (!online && !capabilityService.canWakeUp(sn)) {
            data.put("status", "offline");
            data.put("taskId", null);
            data.put("path", null);
            data.put("message", "设备离线且无法反向唤醒");
            return data;
        }

        // 先尝试快速读取，拿到真实路径再写；读不到就用候选首条
        List<String> candidates = telnetEnableCandidates(sn, root);
        String targetPath = resolveWritablePath(sn, candidates);
        if (targetPath == null && !candidates.isEmpty()) {
            targetPath = candidates.get(0);
        }
        if (targetPath == null) {
            data.put("status", "fail");
            data.put("message", "找不到可用的 Telnet 使能参数路径");
            return data;
        }

        String value = enabled ? "true" : "false";
        List<Map<String, String>> params = List.of(paramEntry(targetPath, value, "boolean"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterKey", "acs-telnet-" + (enabled ? "on" : "off"));
        args.put("ParameterList", params);

        RpcDispatchService.DispatchHandle handle =
                rpcDispatchService.submitAsync(sn, "SetParameterValues", args, null);
        data.put("status", handle.isQueued() ? "queued" : "fail");
        data.put("taskId", handle.getTaskId());
        data.put("path", targetPath);
        data.put("message", handle.isQueued()
                ? "已下发 Telnet " + (enabled ? "开启" : "关闭") + " 指令"
                : "指令入队失败");
        log.info("下发 Telnet 开关: sn={}, enabled={}, path={}, taskId={}", sn, enabled,
                targetPath, handle.getTaskId());
        return data;
    }

    /**
     * 生成 Telnet 使能参数的候选路径清单。
     * 优先级：运营商定制节点 > 能力树快照里已有的 TelnetEnable 路径 > 通用标准路径。
     */
    private List<String> telnetEnableCandidates(String sn, String root) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        IspAdaptation adaptation = adaptationOf(sn);
        for (String node : adaptation.telnetEnableNodes()) {
            paths.add(root + node + LEAF_ENABLE);
        }
        paths.addAll(snapshotTelnetPaths(sn, root));
        // 通用标准路径兜底
        paths.add(root + "UserInterface.TelnetServer." + LEAF_STD_ENABLE);
        paths.add(root + "UserInterface.1.TelnetServer." + LEAF_STD_ENABLE);
        paths.add(root + "DeviceInfo.X_HW_Telnet." + LEAF_ENABLE);
        // 台账运营商可能登记错：把其它运营商的定制节点也加入候选
        for (IspAdaptation a : adaptationManager.all()) {
            if (a.isp() == adaptation.isp()) {
                continue;
            }
            for (String node : a.telnetEnableNodes()) {
                paths.add(root + node + LEAF_ENABLE);
            }
        }
        return new ArrayList<>(paths);
    }

    /** 能力树快照里真实存在且叶子是 TelnetEnable / Enable 的路径 */
    private List<String> snapshotTelnetPaths(String sn, String root) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        try {
            List<CpeParameter> rows = parameterMapper.selectList(
                    new LambdaQueryWrapper<CpeParameter>()
                            .eq(CpeParameter::getSn, sn)
                            .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                            .and(w -> w.like(CpeParameter::getParamPath, "TelnetEnable")
                                    .or().like(CpeParameter::getParamPath, "TelnetServer.Enable")));
            for (CpeParameter row : rows) {
                String path = row.getParamPath();
                if (path == null || path.isBlank()) {
                    continue;
                }
                String leaf = DeviceCapabilityService.nameOf(path);
                if (LEAF_ENABLE.equalsIgnoreCase(leaf)
                        || (path.contains("TelnetServer") && LEAF_STD_ENABLE.equalsIgnoreCase(leaf))) {
                    paths.add(path);
                }
            }
        } catch (Exception e) {
            log.debug("查询快照 Telnet 路径失败: sn={}, err={}", sn, e.getMessage());
        }
        return new ArrayList<>(paths);
    }

    /**
     * 快速探测一条真实可写的 Telnet 使能路径，用于下发前定位参数。
     * 只等最多 5 秒，不影响下发接口响应时间；探测失败由调用方使用候选首条兜底。
     */
    private String resolveWritablePath(String sn, List<String> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        try {
            Map<String, String> values = capabilityService.readPathValues(sn, candidates)
                    .get(5, TimeUnit.SECONDS);
            TelnetStatus status = parseStatus(values, candidates);
            return status == null ? null : status.path;
        } catch (Exception e) {
            log.debug("快速探测 Telnet 路径失败，将使用候选路径: sn={}, err={}", sn, e.getMessage());
            return null;
        }
    }

    private TelnetStatus parseStatus(Map<String, String> values, List<String> candidates) {
        for (String path : candidates) {
            String v = values.get(path);
            if (v == null || v.isBlank()) {
                continue;
            }
            Boolean enabled = parseBoolean(v);
            if (enabled != null) {
                return new TelnetStatus(path, enabled);
            }
        }
        return null;
    }

    private static Boolean parseBoolean(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return Boolean.TRUE;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private IspAdaptation adaptationOf(String sn) {
        CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn)
                .last("LIMIT 1"));
        String isp = device == null || device.getIsp() == null || device.getIsp().isBlank()
                ? "standard" : device.getIsp().trim();
        return adaptationManager.of(isp);
    }

    private String resolveRoot(String sn) {
        try {
            CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .last("LIMIT 1"));
            if (device != null && device.getRootNode() != null && !device.getRootNode().isBlank()) {
                return device.getRootNode();
            }
        } catch (Exception e) {
            log.warn("查询设备根节点失败，回退默认根: sn={}, err={}", sn, e.getMessage());
        }
        return DataModel.TR098.getRoot();
    }

    private static long remaining(long deadline) {
        return Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
    }

    private static boolean expired(long deadline) {
        return System.nanoTime() >= deadline;
    }

    private static Map<String, String> paramEntry(String name, String value, String type) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("Name", name);
        entry.put("Value", value);
        entry.put("Value_type", type);
        return entry;
    }

    private record TelnetStatus(String path, boolean enabled) {
    }
}
