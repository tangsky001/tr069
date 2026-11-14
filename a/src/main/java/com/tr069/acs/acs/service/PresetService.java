package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.cwmp.model.EventStruct;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpePreset;
import com.tr069.acs.domain.entity.CpeProvisionState;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpePresetMapper;
import com.tr069.acs.mapper.CpeProvisionStateMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 预设引擎：设备上报后按条件把配置收敛到目标值 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresetService {

    /** 会话内收敛轮数上限 */
    private static final int MAX_ROUNDS = 4;

    private static final String BOOTSTRAP = "0 BOOTSTRAP";

    private static final String ROOT_TR098 = "InternetGatewayDevice.";

    private static final String AUTH_AUTO = "auto";

    private static final String DEFAULT_CR_USER = "itms";

    private static final String DEFAULT_CR_PASSWORD = "itms";

    /**
     * 内置纳管预设的执行间隔（小时）：0 = 快照永久有效。
     * 纳管参数是否重发只由 {@code cpe_provision_state} 快照决定——
     * 目标值变化或 BOOTSTRAP（恢复出厂/首上线）才重发，其余一律静默跳过
     */
    private static final int BUILTIN_INTERVAL_HOURS = 0;

    private static final String BUILTIN_NAME = "纳管（内置）";

    /** 内置纳管的动作：把 ACS 地址、上报间隔与连接请求凭据写回设备 */
    private static final String BUILTIN_ACTIONS = "["
            + "{\"type\":\"set\",\"path\":\"ManagementServer.URL\",\"value\":\"${acsUrl}\"},"
            + "{\"type\":\"set\",\"path\":\"ManagementServer.PeriodicInformInterval\","
            + "\"value\":\"${informInterval}\"},"
            + "{\"type\":\"set\",\"path\":\"ManagementServer.ConnectionRequestUsername\","
            + "\"value\":\"${crUsername}\"},"
            + "{\"type\":\"set\",\"path\":\"ManagementServer.ConnectionRequestPassword\","
            + "\"value\":\"${crPassword}\"}]";

    private final CpePresetMapper presetMapper;

    private final CpeDeviceMapper deviceMapper;

    private final CpeProvisionStateMapper provisionStateMapper;

    private final CwmpRpcService cwmpRpcService;

    private final ObjectMapper objectMapper;

    /** 连接请求派生凭据：配置了主密钥时，纳管写进设备的就是按序列号派生的凭据（与反连侧同一算法） */
    private final ConnectionRequestGuard connReqGuard;

    /** 下发给设备的周期上报间隔(秒)，0 表示不改这一项 */
    @Value("${acs.device.provision.inform-interval-seconds:300}")
    private long informIntervalSeconds;

    /** 下发给设备的 ACS 上报地址；留空按 public-host 与上报端口拼 */
    @Value("${acs.device.provision.acs-url:}")
    private String acsUrl;

    @Value("${acs.public-host:127.0.0.1}")
    private String publicHost;

    @Value("${acs.cwmp.listen-ports:9090}")
    private String listenPorts;

    @Value("${acs.cwmp.cpe-path:/ACS-server/ACS}")
    private String cpePath;

    /** 下发给设备的连接请求凭据；留空或 auto 时按 itms 处理 */
    @Value("${acs.device.provision.connection-request-username:}")
    private String crUsername;

    @Value("${acs.device.provision.connection-request-password:}")
    private String crPassword;

    /** 每台设备每个预设上次执行的时间戳，进程内记忆 */
    private final Map<String, Map<Long, Long>> lastRun = new ConcurrentHashMap<>();

    private final Map<Long, List<Condition>> conditionCache = new ConcurrentHashMap<>();

    private final Map<Long, List<Action>> actionCache = new ConcurrentHashMap<>();

    private volatile boolean builtinReady = false;

    /** 上报后收敛：按优先级跑预设，把设备配置拉到目标值 */
    public void converge(CpeSession session, InformRequest inform) {
        if (session == null) {
            return;
        }
        String sn = session.getSn();
        boolean bootstrap = hasBootstrap(inform);
        Map<String, String> known = new LinkedHashMap<>();
        collectReported(inform, known);
        // 恢复出厂/深度恢复出厂（BOOTSTRAP）：设备的纳管配置已被清掉，
        // 先清除这台设备的下发快照，让预设重新收敛一轮并重新入库
        if (bootstrap) {
            clearProvisionState(sn);
        }
        List<CpePreset> presets = listEnabled();
        if (presets.isEmpty()) {
            return;
        }
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            List<ParameterValueStruct> values = new ArrayList<>();
            List<Action> others = new ArrayList<>();
            // 本轮各预设的下发快照（presetId → path=value）：下发成功后落库
            Map<Long, Map<String, String>> snapshots = new LinkedHashMap<>();
            for (CpePreset preset : presets) {
                if (!due(sn, preset, bootstrap)) {
                    continue;
                }
                if (!matches(preset, session, known)) {
                    continue;
                }
                collect(preset, session, known, values, others, snapshots);
                markRun(sn, preset);
            }
            for (Action action : others) {
                sendOther(sn, action);
            }
            // 没有要改的参数就到此为止：本轮没产生变化，下一轮也不会。
            // 设备值已对上但快照还没记的（Inform 恰好带了这些参数），补记快照，之后静默跳过
            if (values.isEmpty()) {
                persistSnapshots(sn, snapshots);
                return;
            }
            send(sn, values, known, snapshots);
        }
    }

    /** 预设改动后清掉解析缓存，下次命中重新解析 */
    public void evict(Long id) {
        if (id == null) {
            return;
        }
        conditionCache.remove(id);
        actionCache.remove(id);
    }

    /** 内置纳管预设只在第一次用到时落库，避免启动期就访问库 */
    private List<CpePreset> listEnabled() {
        ensureBuiltin();
        return presetMapper.selectList(new LambdaQueryWrapper<CpePreset>()
                .eq(CpePreset::getEnabled, 1)
                .orderByAsc(CpePreset::getPriority)
                .orderByAsc(CpePreset::getId));
    }

    private void ensureBuiltin() {
        if (builtinReady) {
            return;
        }
        synchronized (this) {
            if (builtinReady) {
                return;
            }
            try {
                Long count = presetMapper.selectCount(
                        new LambdaQueryWrapper<CpePreset>().eq(CpePreset::getBuiltIn, 1));
                if (count == null || count == 0) {
                    CpePreset preset = new CpePreset();
                    preset.setName(BUILTIN_NAME);
                    preset.setPriority(10);
                    preset.setEnabled(1);
                    preset.setConditionsJson("[]");
                    preset.setActionsJson(BUILTIN_ACTIONS);
                    preset.setReprovisionIntervalHours(BUILTIN_INTERVAL_HOURS);
                    preset.setForceOnBootstrap(1);
                    preset.setBuiltIn(1);
                    preset.setRemark("上报后把 ACS 地址、上报间隔与连接请求凭据写回设备");
                    presetMapper.insert(preset);
                    log.info("已初始化内置纳管预设");
                } else {
                    // 内置预设的执行间隔跟随配置文件：存量库里是旧值时同步一次
                    CpePreset builtin = presetMapper.selectOne(new LambdaQueryWrapper<CpePreset>()
                            .eq(CpePreset::getBuiltIn, 1).last("LIMIT 1"));
                    if (builtin != null && !Integer.valueOf(BUILTIN_INTERVAL_HOURS)
                            .equals(builtin.getReprovisionIntervalHours())) {
                        CpePreset patch = new CpePreset();
                        patch.setId(builtin.getId());
                        patch.setReprovisionIntervalHours(BUILTIN_INTERVAL_HOURS);
                        presetMapper.updateById(patch);
                        log.info("内置纳管预设执行间隔已同步为 {}（快照永久有效）", BUILTIN_INTERVAL_HOURS);
                    }
                }
                builtinReady = true;
            } catch (Exception e) {
                log.warn("初始化内置纳管预设失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 收集一条预设要下的参数，已经下发过的跳过。
     * <p>跳过依据（按序列号寻库，重启不丢）：
     * ① Inform 里设备上报的值与目标一致；② {@code cpe_provision_state} 快照里的值与目标一致
     * 且还在校正时效内（快照只在该预设下发成功后写入）。命中即静默跳过，不打日志。
     * 超过校正时效（预设的 reprovisionIntervalHours 小时）后强制重发一遍并刷新快照，
     * 防止设备侧配置被人为改动后 ACS 永远发现不了。</p>
     */
    private void collect(CpePreset preset, CpeSession session, Map<String, String> known,
                         List<ParameterValueStruct> values, List<Action> others,
                         Map<Long, Map<String, String>> snapshots) {
        String root = rootOf(session);
        Snapshot stored = null;
        Map<String, String> target = new LinkedHashMap<>();
        for (Action action : actionCache(preset)) {
            if (action == null || action.getType() == null) {
                continue;
            }
            if ("set".equals(action.getType())) {
                String path = absPath(action.getPath(), root);
                String value = render(action.getValue(), session);
                if (path == null || value == null) {
                    continue;
                }
                target.put(path, value);
                // 已经对得上的不重复下发：设备每次上报都下发一遍只会占住下发窗口
                if (value.equals(knownValue(known, action.getPath()))) {
                    continue;
                }
                // 快照命中且还在校正时效内：之前下发成功过，静默跳过；
                // 时效已过就放行重发，下发成功后快照随之刷新，进入下一个校正周期
                if (stored == null) {
                    stored = storedTargets(session.getSn(), preset.getId());
                }
                if (stored != null && value.equals(stored.targets().get(path))
                        && withinWindow(stored, preset)) {
                    continue;
                }
                put(values, ParameterValueStruct.of(path, value));
            } else {
                others.add(action);
            }
        }
        if (!target.isEmpty()) {
            snapshots.put(preset.getId(), target);
        }
    }

    /** 下发快照：目标参数值 + 落库时间（用于判断校正时效） */
    private record Snapshot(Map<String, String> targets, LocalDateTime updatedAt) {
    }

    /** 快照是否还在校正周期内；hours<=0 表示快照永久有效（仅 BOOTSTRAP/目标变化时重发） */
    private boolean withinWindow(Snapshot snapshot, CpePreset preset) {
        Integer hours = preset.getReprovisionIntervalHours();
        if (hours == null || hours <= 0) {
            return true;
        }
        if (snapshot.updatedAt() == null) {
            return false;
        }
        return snapshot.updatedAt().isAfter(LocalDateTime.now().minusHours(hours));
    }

    /** 按序列号 + 预设 ID 读下发快照，没有记录或读不出来返回 null（按未下发处理） */
    private Snapshot storedTargets(String sn, Long presetId) {
        if (sn == null || presetId == null) {
            return null;
        }
        try {
            CpeProvisionState state = provisionStateMapper.selectOne(
                    new LambdaQueryWrapper<CpeProvisionState>()
                            .eq(CpeProvisionState::getSn, sn)
                            .eq(CpeProvisionState::getPresetId, presetId)
                            .last("LIMIT 1"));
            if (state == null || state.getTargetsJson() == null || state.getTargetsJson().isBlank()) {
                return null;
            }
            Map<String, String> targets = objectMapper.readValue(state.getTargetsJson(),
                    new TypeReference<Map<String, String>>() { });
            return new Snapshot(targets, state.getUpdatedAt());
        } catch (Exception e) {
            log.warn("读取纳管下发快照失败，按未下发处理: sn={}, preset={}, err={}", sn, presetId, e.getMessage());
            return null;
        }
    }

    /** 下发成功后回写快照（sn + presetId 唯一），之后这台设备这组参数就静默跳过 */
    private void persistSnapshots(String sn, Map<Long, Map<String, String>> snapshots) {
        if (snapshots == null || snapshots.isEmpty() || sn == null) {
            return;
        }
        for (Map.Entry<Long, Map<String, String>> entry : snapshots.entrySet()) {
            try {
                CpeProvisionState state = new CpeProvisionState();
                state.setSn(sn);
                state.setPresetId(entry.getKey());
                state.setTargetsJson(objectMapper.writeValueAsString(entry.getValue()));
                CpeProvisionState exist = provisionStateMapper.selectOne(
                        new LambdaQueryWrapper<CpeProvisionState>()
                                .eq(CpeProvisionState::getSn, sn)
                                .eq(CpeProvisionState::getPresetId, entry.getKey()));
                if (exist == null) {
                    provisionStateMapper.insert(state);
                } else {
                    state.setId(exist.getId());
                    provisionStateMapper.updateById(state);
                }
            } catch (Exception e) {
                log.warn("记录纳管下发快照失败: sn={}, preset={}, err={}", sn, entry.getKey(), e.getMessage());
            }
        }
    }

    /** BOOTSTRAP（恢复出厂/首次上线）时清除这台设备的全部下发快照，让预设重新收敛 */
    private void clearProvisionState(String sn) {
        if (sn == null) {
            return;
        }
        try {
            provisionStateMapper.delete(new LambdaQueryWrapper<CpeProvisionState>()
                    .eq(CpeProvisionState::getSn, sn));
        } catch (Exception e) {
            log.warn("清除纳管下发快照失败: sn={}, err={}", sn, e.getMessage());
        }
    }

    /** 合并成一条 SetParameterValues 下发，并按新值继续收敛 */
    private void send(String sn, List<ParameterValueStruct> values, Map<String, String> known,
                      Map<Long, Map<String, String>> snapshots) {
        log.info("预设下发: sn={}, 参数={}", sn, describe(values));
        List<ParameterValueStruct> copy = new ArrayList<>(values);
        cwmpRpcService.setParameterValues(sn, values, null)
                .whenComplete((resp, ex) -> {
                    logResult(sn, resp, ex);
                    if (ex == null && !CwmpRpcService.isSessionFault(resp)) {
                        // 设备应答成功才算"已下发"：落快照，之后同参数静默跳过；
                        // 下发失败不落快照，设备下次上报会重新收敛
                        rememberCredentials(sn, copy);
                        persistSnapshots(sn, snapshots);
                    }
                });
        // 乐观更新：不等回应就按新值继续，失败的话下次上报会用设备真实值重新匹配
        for (ParameterValueStruct value : values) {
            known.put(value.getName(), value.getValue());
        }
    }

    private void sendOther(String sn, Action action) {
        String type = action.getType();
        String key = action.getKey() == null ? "" : action.getKey();
        CompletableFuture<com.tr069.acs.cwmp.model.SoapMessage> future;
        if ("reboot".equals(type)) {
            future = cwmpRpcService.reboot(sn, key);
        } else if ("addObject".equals(type)) {
            future = cwmpRpcService.addObject(sn, action.getObjectName(), key);
        } else if ("deleteObject".equals(type)) {
            future = cwmpRpcService.deleteObject(sn, action.getObjectName(), key);
        } else {
            log.warn("预设动作类型不支持: sn={}, type={}", sn, type);
            return;
        }
        future.whenComplete((resp, ex) -> {
            if (ex != null) {
                log.warn("预设动作下发异常: sn={}, type={}, err={}", sn, type, ex.getMessage());
            } else if (CwmpRpcService.isSessionFault(resp)) {
                log.warn("预设动作下发失败: sn={}, type={}, err={}",
                        sn, type, CwmpRpcService.faultText(resp));
            } else {
                log.info("预设动作下发完成: sn={}, type={}", sn, type);
            }
        });
    }

    /** 下发改写过连接请求凭据时登记进台账 */
    private void rememberCredentials(String sn, List<ParameterValueStruct> values) {
        String user = null;
        String password = null;
        for (ParameterValueStruct value : values) {
            if (value.getName() == null) {
                continue;
            }
            if (value.getName().endsWith("ManagementServer.ConnectionRequestUsername")) {
                user = value.getValue();
            } else if (value.getName().endsWith("ManagementServer.ConnectionRequestPassword")) {
                password = value.getValue();
            }
        }
        if (user == null && password == null) {
            return;
        }
        try {
            CpeDevice device = new CpeDevice();
            if (user != null) {
                device.setAcsToCpeName(user);
            }
            if (password != null) {
                device.setAcsToCpePassword(password);
            }
            deviceMapper.update(device,
                    new LambdaUpdateWrapper<CpeDevice>().eq(CpeDevice::getSn, sn));
        } catch (Exception e) {
            log.warn("登记设备连接请求凭据失败: sn={}, err={}", sn, e.getMessage());
        }
    }

    private boolean matches(CpePreset preset, CpeSession session, Map<String, String> known) {
        List<Condition> conditions = conditionCache(preset);
        if (conditions.isEmpty()) {
            return true;
        }
        for (Condition condition : conditions) {
            if (!matchOne(condition, session, known)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchOne(Condition condition, CpeSession session, Map<String, String> known) {
        String field = condition.getField();
        if (field == null || field.isBlank()) {
            return true;
        }
        String actual = field.startsWith("param:")
                ? knownValue(known, field.substring(6).trim())
                : deviceField(session, field);
        return compare(condition.getOp(), actual, condition.getValue());
    }

    private String deviceField(CpeSession session, String field) {
        if ("sn".equals(field)) {
            return session.getSn();
        }
        if ("oui".equals(field)) {
            return session.getOui();
        }
        if ("productClass".equals(field)) {
            return session.getProductClass();
        }
        if ("softwareVersion".equals(field)) {
            return session.getSoftwareVersion();
        }
        if ("hardwareVersion".equals(field)) {
            return session.getHardwareVersion();
        }
        if ("manufacturer".equals(field)) {
            return session.getManufacturer();
        }
        if ("rootNode".equals(field)) {
            return session.getRootNode();
        }
        if ("isp".equals(field)) {
            return ispOf(session.getSn());
        }
        return null;
    }

    private String ispOf(String sn) {
        if (sn == null) {
            return null;
        }
        try {
            CpeDevice device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, sn).last("limit 1"));
            return device == null ? null : device.getIsp();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean compare(String op, String actual, String expected) {
        String opName = op == null || op.isBlank() ? "eq" : op.trim().toLowerCase();
        if (actual == null || actual.isBlank()) {
            return false;
        }
        String a = actual.trim();
        String e = expected == null ? "" : expected.trim();
        switch (opName) {
            case "neq":
                return !a.equalsIgnoreCase(e);
            case "contains":
                return a.toLowerCase().contains(e.toLowerCase());
            case "startswith":
                return a.toLowerCase().startsWith(e.toLowerCase());
            case "in":
                for (String part : e.split("[,;]")) {
                    if (part.trim().equalsIgnoreCase(a)) {
                        return true;
                    }
                }
                return false;
            case "regex":
                try {
                    return Pattern.compile(e).matcher(a).find();
                } catch (Exception ex) {
                    log.warn("预设条件的正则有误: {}", e);
                    return false;
                }
            case "gt":
                return number(a) > number(e);
            case "lt":
                return number(a) < number(e);
            default:
                return a.equalsIgnoreCase(e);
        }
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 距离上次执行是否已超过间隔；BOOTSTRAP 时按预设配置决定是否强制重跑 */
    private boolean due(String sn, CpePreset preset, boolean bootstrap) {
        Integer hours = preset.getReprovisionIntervalHours();
        if (hours == null || hours <= 0) {
            return true;
        }
        if (bootstrap && Integer.valueOf(1).equals(preset.getForceOnBootstrap())) {
            return true;
        }
        Map<Long, Long> perDevice = lastRun.get(sn);
        if (perDevice == null) {
            return true;
        }
        Long last = perDevice.get(preset.getId());
        return last == null || System.currentTimeMillis() - last > hours * 3_600_000L;
    }

    private void markRun(String sn, CpePreset preset) {
        lastRun.computeIfAbsent(sn, k -> new ConcurrentHashMap<>())
                .put(preset.getId(), System.currentTimeMillis());
    }

    private String render(String text, CpeSession session) {
        if (text == null) {
            return null;
        }
        String out = text
                .replace("${acsUrl}", acsUrl())
                .replace("${informInterval}", interval())
                .replace("${crUsername}", crUser(session))
                .replace("${crPassword}", crPassword(session))
                .replace("${sn}", nz(session.getSn()))
                .replace("${productClass}", nz(session.getProductClass()))
                .replace("${rootNode}", rootOf(session));
        return out.isBlank() ? null : out;
    }

    /** 变量替换：${acsUrl} 上报地址 / ${informInterval} 上报间隔 / ${crUsername} ${crPassword} 连接请求凭据 */
    private String acsUrl() {
        if (acsUrl != null && !acsUrl.isBlank()) {
            return acsUrl.trim();
        }
        String host = publicHost == null || publicHost.isBlank() ? "127.0.0.1" : publicHost.trim();
        String path = path();
        return host.contains("://") ? host + path : "http://" + host + ":" + firstPort() + path;
    }

    private String interval() {
        // 间隔配 0 表示不改这一项，渲染成空让这条动作自动丢弃
        return informIntervalSeconds > 0 ? String.valueOf(informIntervalSeconds) : "";
    }

    private String crUser(CpeSession session) {
        // 派生模式（conn-req-master-secret 已配置）：写进设备的用户名就是序列号，
        // 密码按序列号派生——反连侧按同一算法算出，天然对上，不再依赖全局 itms
        if (connReqGuard.derivedCredentialsEnabled()) {
            return session.getSn();
        }
        return isAuto(crUsername) ? DEFAULT_CR_USER : crUsername.trim();
    }

    private String crPassword(CpeSession session) {
        if (connReqGuard.derivedCredentialsEnabled()) {
            return connReqGuard.deriveConnReqPassword(session.getSn());
        }
        return isAuto(crPassword) ? DEFAULT_CR_PASSWORD : crPassword.trim();
    }

    private static boolean isAuto(String value) {
        return value == null || value.isBlank() || AUTH_AUTO.equalsIgnoreCase(value.trim());
    }

    private String firstPort() {
        if (listenPorts == null || listenPorts.isBlank()) {
            return "9090";
        }
        for (String part : listenPorts.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                return part.trim();
            }
        }
        return "9090";
    }

    private String path() {
        if (cpePath == null || cpePath.isBlank()) {
            return "/ACS-server/ACS";
        }
        String trimmed = cpePath.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /** 动作路径写绝对的就直接用，写相对的按设备根节点补全 */
    private static String absPath(String path, String root) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("InternetGatewayDevice.") || trimmed.startsWith("Device.")) {
            return trimmed;
        }
        return root + trimmed;
    }

    /** 同一轮里两条预设改同一个参数时后一条说了算，合并进一条 SPV 时不留重复路径 */
    private static void put(List<ParameterValueStruct> values, ParameterValueStruct value) {
        values.removeIf(v -> value.getName().equals(v.getName()));
        values.add(value);
    }

    /** 已知值里按后缀找，Inform 上报的是完整路径，预设里通常只写叶子段 */
    private static String knownValue(Map<String, String> known, String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return null;
        }
        for (Map.Entry<String, String> entry : known.entrySet()) {
            if (entry.getKey() != null && entry.getKey().endsWith(suffix.trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void collectReported(InformRequest inform, Map<String, String> known) {
        if (inform == null || inform.getParameterList() == null) {
            return;
        }
        for (ParameterValueStruct value : inform.getParameterList()) {
            if (value != null && value.getName() != null) {
                known.put(value.getName().trim(), value.getValue());
            }
        }
    }

    private static String rootOf(CpeSession session) {
        String root = session.getRootNode();
        if (root == null || root.isBlank()) {
            return ROOT_TR098;
        }
        return root.endsWith(".") ? root : root + ".";
    }

    private static boolean hasBootstrap(InformRequest inform) {
        if (inform == null || inform.getEventList() == null) {
            return false;
        }
        for (EventStruct event : inform.getEventList()) {
            if (event.getEventCode() != null && event.getEventCode().startsWith(BOOTSTRAP)) {
                return true;
            }
        }
        return false;
    }

    private List<Condition> conditionCache(CpePreset preset) {
        return readList(preset.getId(), preset.getConditionsJson(),
                new TypeReference<List<Condition>>() {
                }, conditionCache);
    }

    private List<Action> actionCache(CpePreset preset) {
        return readList(preset.getId(), preset.getActionsJson(),
                new TypeReference<List<Action>>() {
                }, actionCache);
    }

    private <T> List<T> readList(Long id, String json, TypeReference<List<T>> type,
                                 Map<Long, List<T>> cache) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<T> cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        try {
            List<T> parsed = objectMapper.readValue(json, type);
            List<T> list = parsed == null ? List.of() : parsed;
            cache.put(id, list);
            return list;
        } catch (Exception e) {
            log.warn("预设 JSON 解析失败: id={}, err={}", id, e.getMessage());
            return List.of();
        }
    }

    private void logResult(String sn, com.tr069.acs.cwmp.model.SoapMessage resp, Throwable ex) {
        if (ex != null) {
            log.warn("预设下发异常: sn={}, err={}", sn, ex.getMessage());
            return;
        }
        if (CwmpRpcService.isSessionFault(resp)) {
            log.warn("预设下发失败: sn={}, err={}", sn, CwmpRpcService.faultText(resp));
            return;
        }
        log.info("预设下发完成: sn={}", sn);
    }

    private static String describe(List<ParameterValueStruct> values) {
        StringBuilder text = new StringBuilder();
        for (ParameterValueStruct value : values) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(value.getName()).append("=").append(value.getValue());
        }
        return text.toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** 预设的匹配条件 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Condition {

        /** 字段：sn / oui / productClass / softwareVersion / hardwareVersion */
        private String field;

        /** 比较方式：eq / neq / contains / startsWith / in / regex / gt / lt */
        private String op;

        private String value;
    }

    /** 预设的动作 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Action {

        /** set 设值 / reboot 重启 / addObject 建实例 / deleteObject 删实例 */
        private String type;

        /** set 用：参数路径，可写相对路径，按设备根节点补全 */
        private String path;

        /** set 用：目标值，支持 ${acsUrl} ${informInterval} ${crUsername} */
        private String value;

        /** addObject / deleteObject 用：对象路径 */
        private String objectName;

        /** addObject / deleteObject / reboot 用：命令关键字 */
        private String key;
    }
}
