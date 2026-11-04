package com.tr069.acs.olt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.domain.entity.SysUser;
import com.tr069.acs.mapper.SysUserMapper;
import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.entity.OltSyncRecord;
import com.tr069.acs.olt.mapper.OltBoardMapper;
import com.tr069.acs.olt.mapper.OltDeviceMapper;
import com.tr069.acs.olt.mapper.OltSyncRecordMapper;
import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltActionParam;
import com.tr069.acs.olt.model.OltDbaForm;
import com.tr069.acs.olt.model.OltDbaType;
import com.tr069.acs.olt.model.OltDetail;
import com.tr069.acs.olt.model.OltDbaProfileRequest;
import com.tr069.acs.olt.model.OltExecRequest;
import com.tr069.acs.olt.model.OltExecResult;
import com.tr069.acs.olt.model.OltOntQueryRequest;
import com.tr069.acs.olt.model.OltOntInfo;
import com.tr069.acs.olt.model.OltOntQueryResult;
import com.tr069.acs.olt.model.OltLineMappingRow;
import com.tr069.acs.olt.model.OltLineProfile;
import com.tr069.acs.olt.model.OltLineProfileForm;
import com.tr069.acs.olt.model.OltLineProfilePreviewRequest;
import com.tr069.acs.olt.model.OltGemport;
import com.tr069.acs.olt.model.OltGemMapping;
import com.tr069.acs.olt.model.OltTcont;
import com.tr069.acs.olt.model.OltDbaProfile;
import com.tr069.acs.olt.model.OltTcontRequest;
import com.tr069.acs.olt.model.OltGemportRequest;
import com.tr069.acs.olt.model.OltGemMappingRequest;
import com.tr069.acs.olt.model.OltServicePort;
import com.tr069.acs.olt.model.OltQuickSetupRequest;
import com.tr069.acs.olt.model.HuaweiQuickSetupRequest;
import com.tr069.acs.olt.model.OltOntAutofind;
import com.tr069.acs.olt.model.OltOntAutoFindStatus;
import com.tr069.acs.olt.model.OltOntDeleteAllRequest;
import com.tr069.acs.olt.model.OltOntDeleteResult;
import com.tr069.acs.olt.model.OltTerminalUserRequest;
import com.tr069.acs.olt.model.OltBoardProfile;
import com.tr069.acs.olt.model.OltBoardStatusRule;
import com.tr069.acs.olt.model.OltCodeTables;
import com.tr069.acs.olt.model.OltDeviceOptions;
import com.tr069.acs.olt.model.OltDeviceView;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltOption;
import com.tr069.acs.olt.model.OltQuickSetupForm;
import com.tr069.acs.olt.model.OltQuickSetupPreview;
import com.tr069.acs.olt.model.OltTr069Form;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltSaveRequest;
import com.tr069.acs.olt.model.OltSyncRecordView;
import com.tr069.acs.olt.model.OltTemplateInfo;
import com.tr069.acs.olt.model.OltTestResult;
import com.tr069.acs.olt.model.OltTr069Profile;
import com.tr069.acs.olt.model.OltTr069ProfileRequest;
import com.tr069.acs.olt.model.OltPortVlan;
import com.tr069.acs.olt.model.OltVlan;
import com.tr069.acs.olt.model.OltVlanInfo;
import com.tr069.acs.olt.model.OltVlanForm;
import com.tr069.acs.olt.model.OltVlanPort;
import com.tr069.acs.olt.model.OltVlanRequest;
import com.tr069.acs.olt.model.OltPortVlanRequest;
import com.tr069.acs.olt.model.VlanIdExpression;
import com.tr069.acs.olt.telnet.OltCliSession;
import com.tr069.acs.olt.telnet.OltInfoParser;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;
import com.tr069.acs.olt.vendor.Hsgq.HsgqModelCatalog;
import com.tr069.acs.olt.vendor.OltBoardCatalog;
import com.tr069.acs.olt.vendor.OltVendorProfile;
import com.tr069.acs.olt.vendor.OltVendorProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** OLT 台账管理 + 连接测试 + 同步入库 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OltService {

    private static final String PROTOCOL_TELNET = "TELNET";

    private static final String PROTOCOL_SSH = "SSH";

    private static final String STATUS_ONLINE = "ONLINE";

    private static final String STATUS_OFFLINE = "OFFLINE";

    private static final String STATUS_UNKNOWN = "UNKNOWN";

    private static final int DEFAULT_TELNET_PORT = 23;

    private static final int DEFAULT_SSH_PORT = 22;

    private static final Set<String> CHARSETS = Set.of("AUTO", "UTF-8", "GBK", "GB18030");

    /** 采集记录里给页面看的回显片段，够看清 display board / temperature 的输出 */
    private static final int MAX_RAW_PREVIEW = 8000;

    /** 后台补采线程池：OLT 的 Telnet 并发会话数很小（华为一般只放几个），两台并行足够 */
    private static final ExecutorService DEEP_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "olt-deep-sync");
        thread.setDaemon(true);
        return thread;
    });

    /** 正在后台补采的 OLT：同一台不重复排队 */
    private final Set<Long> deepSyncing = ConcurrentHashMap.newKeySet();

    /** 每台 OLT 一把「前台采集」锁：页签同步、板卡同步、手动同步串行执行 */
    private final ConcurrentHashMap<Long, ReentrantLock> syncLocks = new ConcurrentHashMap<>();

    /** 前台采集排队时长：连点页签时后到的等前一轮跑完，超过才放弃（前端请求超时 180 秒，够等） */
    private static final long SYNC_QUEUE_WAIT_MS = 45_000L;

    /** 各设备各采集段最近一次页签同步的时间戳（deviceId:scope → 毫秒）：节流策略在后端统一做 */
    private final ConcurrentHashMap<String, Long> sectionSyncAt = new ConcurrentHashMap<>();

    /** 打开详情页的自动板卡刷新：板卡数据在这个时间内同步过，就不再碰设备 */
    private static final long BOARDS_FRESH_MS = 300_000L;

    /** 页签同步节流：同一设备同一采集段在这个时间内重复请求，直接回库里的详情 */
    private static final long SECTION_THROTTLE_MS = 60_000L;

    private final OltDeviceMapper deviceMapper;

    private final OltBoardMapper boardMapper;

    private final OltSyncRecordMapper recordMapper;

    private final SysUserMapper sysUserMapper;

    private final OltTelnetService telnetService;

    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------- 查询

    public List<OltDeviceView> list(String keyword) {
        LambdaQueryWrapper<OltDevice> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(OltDevice::getName, kw)
                    .or().like(OltDevice::getHost, kw)
                    .or().like(OltDevice::getModel, kw));
        }
        wrapper.orderByDesc(OltDevice::getId);
        return deviceMapper.selectList(wrapper).stream()
                .map(OltDeviceView::from)
                .collect(Collectors.toList());
    }

    public OltDetail detail(Long id) {
        OltDevice device = requireDevice(id);
        OltDetail detail = new OltDetail();
        detail.setDevice(OltDeviceView.from(device));
        List<OltBoard> boards = boardMapper.selectList(new LambdaQueryWrapper<OltBoard>()
                .eq(OltBoard::getOltId, id)
                .orderByAsc(OltBoard::getSlotNo));
        boards.forEach(board -> {
            fillPorts(board);
            fillBoardProfile(board);
        });
        detail.setBoards(boards);
        detail.setRecords(records(id, 10));
        OltVlanInfo vlan = readVlan(device.getVlanJson());
        completeVlanMemberPorts(boards, vlan);
        detail.setVlan(vlan);
        detail.setTemplate(readTemplate(device.getTemplateJson()));
        // 派生数据（接口号 / VLAN 文本 / ONT 汇总 / 灯态 / VLAN 分组）统一在后端算，
        // 页面只渲染；口径只有这一份，面板、列表、汇总不会各算各的
        enrichDetail(detail, boards, vlan);
        // 后台正在补采慢数据（端口 / ONT / VLAN / 模板）时告诉页面，页面据此提示并轮询
        detail.setDeepSyncing(deepSyncing.contains(id));
        return detail;
    }

    /** 按型号档案把板卡补齐：面板样式、是否主控板，端口类型/数量在设备没给时按档案兜底 */
    private static void fillBoardProfile(OltBoard board) {
        // 整机（鸿升光）：没有板卡插框，面板按型号规格画，不做板卡档案匹配。
        // 旧快照里没有这块（运行时字段），按型号现补，免得页面整机面板空着。
        if (board.getFrontPanel() == null) {
            board.setFrontPanel(HsgqModelCatalog.frontPanelOf(board.getBoardType()));
        }
        if (board.getFrontPanel() != null) {
            board.setPanelKind("whole");
            board.setControlBoard(Boolean.FALSE);
            fillBoardStatus(board);
            return;
        }
        OltBoardProfile profile = OltBoardCatalog.match(board.getBoardType());
        if (profile != null) {
            board.setPanelKind(profile.panel());
            board.setControlBoard(profile.controlBoard());
            if (board.getPortMode() == null || board.getPortMode().isBlank()) {
                board.setPortMode(profile.portMode());
            }
            if (board.getPortCount() == null) {
                board.setPortCount(profile.ports());
            }
        } else {
            board.setPanelKind(OltBoardCatalog.panelKindOf(board.getPortMode()));
            board.setControlBoard(Boolean.FALSE);
        }
        fillBoardStatus(board);
    }

    /** 设备回的英文状态 → 中文说法 + 等级（整机和板卡共用） */
    private static void fillBoardStatus(OltBoard board) {
        OltBoardStatusRule rule = OltBoardCatalog.statusOf(board.getStatus());
        if (rule == null) {
            board.setStatusText(board.getStatus() == null || board.getStatus().isBlank()
                    ? "未知" : board.getStatus());
            board.setStatusLevel("info");
        } else {
            board.setStatusText(rule.label());
            board.setStatusLevel(rule.level());
        }
    }

    /** DBA / 线路模板快照同样是 JSON 存在 device 行上，解析失败就当没采过 */
    private OltTemplateInfo readTemplate(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, OltTemplateInfo.class);
        } catch (JsonProcessingException e) {
            log.warn("DBA/线路模板快照解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String writeTemplate(OltTemplateInfo template) {
        if (template == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(template);
        } catch (JsonProcessingException e) {
            log.warn("DBA/线路模板快照序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** VLAN 快照按 JSON 存 device 行上，读出来时反序列化，解析失败就当没采过 */
    private OltVlanInfo readVlan(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, OltVlanInfo.class);
        } catch (JsonProcessingException e) {
            log.warn("VLAN 快照解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String writeVlan(OltVlanInfo vlan) {
        if (vlan == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(vlan);
        } catch (JsonProcessingException e) {
            log.warn("VLAN 快照序列化失败: {}", e.getMessage());
            return null;
        }
    }

    public List<OltSyncRecordView> records(Long id, int limit) {
        int size = limit <= 0 ? 10 : Math.min(limit, 100);
        return recordMapper.selectList(new LambdaQueryWrapper<OltSyncRecord>()
                        .eq(OltSyncRecord::getOltId, id)
                        .orderByDesc(OltSyncRecord::getId)
                        .last("LIMIT " + size))
                .stream()
                .map(r -> OltSyncRecordView.from(r, MAX_RAW_PREVIEW))
                .collect(Collectors.toList());
    }

    /** 可勾选的 Web 登录账号，取自管理端用户表 */
    public List<Map<String, Object>> webAccounts() {
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getId));
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (SysUser user : users) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("username", user.getUsername());
            item.put("nickname", user.getNickname());
            accounts.add(item);
        }
        return accounts;
    }

    // ---------------------------------------------------------------- 编辑

    public OltDeviceView save(OltSaveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String host = trimToNull(request.getHost());
        if (host == null) {
            throw new IllegalArgumentException("OLT 地址不能为空");
        }
        if (host.contains(" ") || host.contains("/")) {
            throw new IllegalArgumentException("OLT 地址填写 IP 或域名，不要带协议和路径");
        }
        String protocol = PROTOCOL_SSH.equalsIgnoreCase(trim(request.getProtocol()))
                ? PROTOCOL_SSH : PROTOCOL_TELNET;
        int port = request.getPort() == null
                ? (PROTOCOL_SSH.equals(protocol) ? DEFAULT_SSH_PORT : DEFAULT_TELNET_PORT)
                : request.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 1-65535 之间");
        }
        String charset = normalizeCharset(request.getCharset());
        String name = trimToNull(request.getName());
        if (name == null) {
            name = host;
        }
        String username = trimToNull(request.getUsername());
        String bindLocalIp = trimToNull(request.getBindLocalIp());
        String remark = trimToNull(request.getRemark());
        String webAccounts = request.getWebAccounts() == null || request.getWebAccounts().isEmpty()
                ? null
                : request.getWebAccounts().stream()
                        .map(this::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .collect(Collectors.joining(","));

        Long id = request.getId();
        if (id == null) {
            ensureNameAvailable(name, null);
            OltDevice device = new OltDevice();
            device.setName(name);
            device.setHost(host);
            device.setProtocol(protocol);
            device.setUsername(username);
            device.setPassword(trimToNull(request.getPassword()));
            device.setPort(port);
            device.setCharset(charset);
            device.setBindLocalIp(bindLocalIp);
            device.setWebAccounts(webAccounts);
            device.setRemark(remark);
            device.setStatus(STATUS_UNKNOWN);
            // 页面测试连接时识别出来的厂商，直接带上，添加完就能按厂商进对应页面
            device.setVendor(normalizeVendor(request.getVendor()));
            deviceMapper.insert(device);
            return OltDeviceView.from(deviceMapper.selectById(device.getId()));
        }

        OltDevice existing = requireDevice(id);
        ensureNameAvailable(name, id);

        LambdaUpdateWrapper<OltDevice> update = new LambdaUpdateWrapper<OltDevice>()
                .eq(OltDevice::getId, id)
                .set(OltDevice::getName, name)
                .set(OltDevice::getHost, host)
                .set(OltDevice::getProtocol, protocol)
                .set(OltDevice::getUsername, username)
                .set(OltDevice::getPort, port)
                .set(OltDevice::getCharset, charset)
                .set(OltDevice::getBindLocalIp, bindLocalIp)
                .set(OltDevice::getWebAccounts, webAccounts)
                .set(OltDevice::getRemark, remark);
        String password = request.getPassword();
        if (password != null && !password.isEmpty()) {
            update.set(OltDevice::getPassword, password);
        }
        // 连接参数变了，之前采集到的型号/板卡已经不代表这台设备
        boolean endpointChanged = !host.equals(existing.getHost())
                || !protocol.equalsIgnoreCase(existing.getProtocol())
                || !Integer.valueOf(port).equals(existing.getPort());
        if (endpointChanged) {
            update.set(OltDevice::getStatus, STATUS_UNKNOWN)
                    .set(OltDevice::getVendor, null)
                    .set(OltDevice::getModel, null)
                    .set(OltDevice::getSoftwareVersion, null)
                    .set(OltDevice::getPatchVersion, null)
                    .set(OltDevice::getBackplane, null)
                    .set(OltDevice::getMacAddress, null)
                    .set(OltDevice::getUptime, null)
                    .set(OltDevice::getLastError, null)
                    .set(OltDevice::getSyncSummary, null);
        }
        // 换了一台设备（地址/协议/端口变了），页面带过来的厂商是旧设备的，作废
        String vendor = normalizeVendor(request.getVendor());
        if (vendor != null && !endpointChanged) {
            update.set(OltDevice::getVendor, vendor);
        }
        deviceMapper.update(null, update);
        return OltDeviceView.from(deviceMapper.selectById(id));
    }

    /** 厂商码归一：留空或不在枚举内都当没传，避免把脏值写进台账后页面分发出错 */
    private String normalizeVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            return null;
        }
        try {
            return OltVendor.valueOf(vendor.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            log.warn("未知的 OLT 厂商码：{}", vendor);
            return null;
        }
    }

    public void delete(Long id) {
        requireDevice(id);
        boardMapper.delete(new LambdaQueryWrapper<OltBoard>().eq(OltBoard::getOltId, id));
        deviceMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 测试与同步

    /** 诊断测试：用请求里的参数（或已保存的参数）连一次设备，不写板卡 */
    public OltTestResult test(OltSaveRequest request, String operator) {
        long start = System.currentTimeMillis();
        OltDevice stored = request != null && request.getId() != null ? requireDevice(request.getId()) : null;
        OltDevice probe = buildProbe(request, stored);
        String host = trimToNull(probe.getHost());
        if (host == null) {
            return OltTestResult.fail("OLT 地址不能为空");
        }
        probe.setHost(host);
        if (probe.getProtocol() == null) {
            probe.setProtocol(PROTOCOL_TELNET);
        }
        try {
            OltSnapshot snapshot = telnetService.collect(probe, false);
            long cost = System.currentTimeMillis() - start;
            String message = describe(snapshot);
            saveRecord(stored == null ? null : stored.getId(), OltSyncRecord.ACTION_TEST,
                    true, message, snapshot.getTranscript(), cost, operator);
            if (stored != null) {
                updateAfterConnect(stored.getId(), snapshot, false, null);
            }
            OltTestResult result = OltTestResult.ok(message);
            result.setVendor(snapshot.getVendor() == null ? null : snapshot.getVendor().name());
            result.setVendorLabel(snapshot.getVendor() == null ? null : snapshot.getVendor().getLabel());
            result.setCliStyle(snapshot.getCliStyle() == null ? null : snapshot.getCliStyle().name());
            result.setModel(snapshot.getModel());
            result.setSoftwareVersion(snapshot.getSoftwareVersion());
            result.setPrompt(snapshot.getPrompt());
            result.setBoardCount(snapshot.getBoards() == null ? 0 : snapshot.getBoards().size());
            result.setCostMs(cost);
            result.setRawOutput(telnetService.truncateRaw(snapshot.getTranscript()));
            return result;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String message = rootMessage(e);
            log.warn("OLT 诊断测试失败 host={}: {}", probe.getHost(), message);
            saveRecord(stored == null ? null : stored.getId(), OltSyncRecord.ACTION_TEST,
                    false, message, null, cost, operator);
            if (stored != null) {
                markOffline(stored.getId(), message);
            }
            OltTestResult result = OltTestResult.fail("连接失败：" + message);
            result.setCostMs(cost);
            return result;
        }
    }

    /** 同步：登录采集版本与板卡，覆盖写板卡表并刷新台账 */
    public OltDetail sync(Long id, String operator) {
        return underDeviceSyncLock(id, "同步", () -> collectAndPersist(id, operator, "同步"));
    }

    /** 实时刷新：直连 OLT 采最新数据，写库后把最新那份返回给页面 */
    public OltDetail refresh(Long id, String operator) {
        return underDeviceSyncLock(id, "实时刷新", () -> collectAndPersist(id, operator, "实时刷新"));
    }

    /** 确认所有待确认板卡，确认完自动重采一遍板卡返回最新状态 */
    public OltDetail confirmBoards(Long id, String operator) {
        String command = profileOf(id).boardConfirmCommand();
        if (command == null) {
            throw new IllegalArgumentException("该厂商没有板卡确认命令（不存在待确认状态）");
        }
        return dispatchConfig(id, List.of(command), operator, "确认所有业务板");
    }

    /** 快速同步：一次短连接把板卡（型号 / 板卡类型 / 槽位 / 机框槽位数）拿回来写库 */
    public OltDetail quickSync(Long id, String operator) {
        return quickSync(id, operator, null);
    }

    /** 快速同步：一次短连接把板卡（型号 / 板卡类型 / 槽位 / 机框槽位数）拿回来写库 */
    public OltDetail quickSync(Long id, String operator, String scope) {
        return underDeviceSyncLock(id, "板卡同步", () -> doQuickSync(id, operator, scope));
    }

    private OltDetail doQuickSync(Long id, String operator, String scope) {
        OltDevice device = requireDevice(id);
        // 打开详情页的自动板卡刷新（scope=boards）：板卡数据刚同步过就不碰设备。
        // 「要不要占用会话」由后端统一决策，前端只是调 API，F5/来回切设备不会把会话名额打满
        if ("boards".equalsIgnoreCase(scope) && boardsFresh(id, device)) {
            return detail(id);
        }
        long start = System.currentTimeMillis();
        OltSnapshot snapshot;
        try {
            snapshot = telnetService.collectBoards(device);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String message = rootMessage(e);
            log.warn("OLT 快速同步失败 id={}: {}", id, message);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, false, "同步失败：" + message, null, cost, operator);
            markOffline(id, message);
            throw new IllegalStateException("同步失败：" + message, e);
        }
        long cost = System.currentTimeMillis() - start;
        List<OltBoard> boards = snapshot.getBoards() == null ? List.of() : snapshot.getBoards();
        // 快速采集没有端口和温度：沿用上次深度采集留下的，别把页面正显示着的数据抹成空
        writeBoards(id, boards, LocalDateTime.now(), true);
        String summary = "已同步 " + boards.size() + " 块板卡" + kindSummary(boards)
                + (snapshot.getModel() == null ? "" : "，型号 " + snapshot.getModel());
        if (snapshot.getNotes() != null && !snapshot.getNotes().isEmpty()) {
            summary = summary + "；" + String.join("；", snapshot.getNotes());
        }
        updateAfterConnect(id, snapshot, true, summary);
        saveRecord(id, OltSyncRecord.ACTION_SYNC, true,
                "同步（板卡）：" + describe(snapshot) + "；" + summary, snapshot.getTranscript(), cost, operator);
        if (!"boards".equalsIgnoreCase(scope)) {
            submitDeepSync(id, operator);
        }
        return detail(id);
    }

    /** 按页签同步：点进哪个页签只采那个页签需要的数据段，不再一次采全部 */
    public OltDetail syncSection(Long id, String scope, String operator, boolean force) {
        return underDeviceSyncLock(id, "页签同步", () -> doSyncSection(id, scope, operator, force));
    }

    private OltDetail doSyncSection(Long id, String scope, String operator, boolean force) {
        OltDevice device = requireDevice(id);
        String s = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        String throttleKey = id + ":" + s;
        Long lastAt = sectionSyncAt.get(throttleKey);
        if (!force && lastAt != null
                && System.currentTimeMillis() - lastAt < SECTION_THROTTLE_MS) {
            return detail(id);
        }
        Set<OltCliSession.Section> sections;
        String label;
        switch (s) {
            case "boards":
                sections = EnumSet.of(OltCliSession.Section.TEMPERATURES);
                label = "页签同步（OLT状态/配置信息）";
                break;
            case "ont":
                sections = EnumSet.of(OltCliSession.Section.PORT_STATES,
                        OltCliSession.Section.ONTS, OltCliSession.Section.OPTICS);
                label = "页签同步（ONT配置/状态）";
                break;
            case "vlan":
                sections = EnumSet.of(OltCliSession.Section.VLANS);
                label = "页签同步（VLAN）";
                break;
            case "template":
                sections = EnumSet.of(OltCliSession.Section.TEMPLATES);
                label = "页签同步（DBA/业务/流量模板）";
                break;
            default:
                throw new IllegalArgumentException("不支持的同步范围：" + scope);
        }
        List<OltBoard> boards = boardMapper.selectList(new LambdaQueryWrapper<OltBoard>()
                .eq(OltBoard::getOltId, id)
                .orderByAsc(OltBoard::getSlotNo));
        boards.forEach(this::fillPorts);
        if (boards.isEmpty()) {
            // 库里还没有板卡（刚添加的设备第一次打开）：先走一次板卡同步把机框建出来
            return quickSync(id, operator, null);
        }
        long start = System.currentTimeMillis();
        try {
            // 页签同步只采整板表：ONT 明细一张表就回来；逐台的管理IP/MAC/模板是
            // 一台一条命令的慢活（几十台要几分钟），交给后台补采，页面轮询刷新看到补齐
            OltSnapshot snapshot = telnetService.collectSections(device, boards, sections, false);
            long cost = System.currentTimeMillis() - start;
            boolean boardsTouched = sections.contains(OltCliSession.Section.TEMPERATURES)
                    || sections.contains(OltCliSession.Section.PORT_STATES)
                    || sections.contains(OltCliSession.Section.ONTS)
                    || sections.contains(OltCliSession.Section.OPTICS);
            if (boardsTouched) {
                // mergeOld=true：本次没采的段（端口/温度/ONT 明细/光模块）沿用库里旧值，别抹成空
                writeBoards(id, snapshot.getBoards(), LocalDateTime.now(), true, sections);
            }
            String summary = sectionSummary(sections, snapshot);
            updateAfterConnect(id, snapshot, true, summary);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, true, label + "：" + summary,
                    snapshot.getTranscript(), cost, operator);
            sectionSyncAt.put(throttleKey, System.currentTimeMillis());
            if (sections.contains(OltCliSession.Section.ONTS)
                    && countOntsMissingDetail(snapshot.getBoards()) > 0) {
                // 有 ONT 缺管理IP/模板号：先回页面（整板表已入库），慢活后台补
                submitOntDetailSync(id, operator);
            }
            return detail(id);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String message = rootMessage(e);
            log.warn("OLT {}失败 id={}: {}", label, id, message);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, false, label + "失败：" + message,
                    null, cost, operator);
            markOffline(id, message);
            throw new IllegalStateException(label + "失败：" + message, e);
        }
    }

    /** 板卡数据是否还新鲜：库里有机框、且这台设备不久前同步成功过（任意一次同步 */
    private boolean boardsFresh(Long id, OltDevice device) {
        LocalDateTime last = device.getLastSyncAt();
        if (last == null || last.isBefore(LocalDateTime.now().minus(Duration.ofMillis(BOARDS_FRESH_MS)))) {
            return false;
        }
        Long count = boardMapper.selectCount(new LambdaQueryWrapper<OltBoard>()
                .eq(OltBoard::getOltId, id));
        return count != null && count > 0;
    }

    /** 按页签同步的摘要：本次真正采到了什么，采不到也要写明白，别让人以为白点了 */
    private String sectionSummary(Set<OltCliSession.Section> sections, OltSnapshot snapshot) {
        List<String> parts = new ArrayList<>();
        if (sections.contains(OltCliSession.Section.ONTS) && snapshot.getBoards() != null) {
            int total = 0;
            for (OltBoard board : snapshot.getBoards()) {
                if (board.getPorts() == null) {
                    continue;
                }
                for (OltPort port : board.getPorts()) {
                    total += port.getOnts() == null ? 0 : port.getOnts().size();
                }
            }
            parts.add("ONT 明细 " + total + " 条");
        }
        if (snapshot.getVlanInfo() != null && snapshot.getVlanInfo().getVlans() != null
                && !snapshot.getVlanInfo().getVlans().isEmpty()) {
            parts.add("VLAN " + snapshot.getVlanInfo().getVlans().size() + " 个");
        }
        if (snapshot.getTemplateInfo() != null && snapshot.getTemplateInfo().getDbaProfiles() != null) {
            parts.add("DBA 模板 " + snapshot.getTemplateInfo().getDbaProfiles().size()
                    + " 条 / 线路模板 " + snapshot.getTemplateInfo().getLineProfiles().size() + " 条");
        }
        if (snapshot.getNotes() != null && !snapshot.getNotes().isEmpty()) {
            parts.add(String.join("；", snapshot.getNotes()));
        }
        if (parts.isEmpty()) {
            parts.add("本次没有采到新数据（设备回显为空或该段不受支持）");
        }
        return String.join("；", parts);
    }

    /** 后台补采慢数据：温度、端口状态与光模块、ONT 明细、VLAN、模板 */
    /** 同一台 OLT 的前台采集串行执行（板卡同步 / 页签同步 / 手动同步） */
    private <T> T underDeviceSyncLock(Long id, String action, Supplier<T> body) {
        ReentrantLock lock = syncLocks.computeIfAbsent(id, key -> new ReentrantLock());
        boolean locked;
        try {
            locked = lock.tryLock(SYNC_QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(action + "排队等待被中断");
        }
        if (!locked) {
            throw new IllegalStateException(action + "：这台 OLT 上一轮采集还没跑完，请稍后重试");
        }
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /** 有多少台 ONT 还缺单台详情（管理 IP 或模板号没采到）：这些列页签同步不采，后台补 */
    private static long countOntsMissingDetail(List<OltBoard> boards) {
        if (boards == null) {
            return 0;
        }
        return boards.stream()
                .filter(board -> board.getPorts() != null)
                .flatMap(board -> board.getPorts().stream())
                .filter(port -> port.getOnts() != null)
                .flatMap(port -> port.getOnts().stream())
                .filter(ont -> ont.getOntId() != null)
                .filter(ont -> ont.getMgmtIp() == null || ont.getLineProfileId() == null)
                .count();
    }

    /** 后台补 ONT 单台详情：页签同步只采整板表（秒级返回） */
    public void submitOntDetailSync(Long id, String operator) {
        if (!deepSyncing.add(id)) {
            return;
        }
        DEEP_POOL.submit(() -> {
            try {
                collectOntDetailsAndPersist(id, operator);
            } catch (Exception e) {
                log.warn("OLT ONT 详情补采失败 id={}: {}", id, e.getMessage());
            } finally {
                deepSyncing.remove(id);
            }
        });
    }

    private void collectOntDetailsAndPersist(Long id, String operator) {
        OltDevice device = requireDevice(id);
        List<OltBoard> boards = boardMapper.selectList(new LambdaQueryWrapper<OltBoard>()
                .eq(OltBoard::getOltId, id)
                .orderByAsc(OltBoard::getSlotNo));
        boards.forEach(this::fillPorts);
        long missing = countOntsMissingDetail(boards);
        if (missing == 0) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            OltSnapshot snapshot = telnetService.collectOntDetails(device, boards);
            writeBoards(id, snapshot.getBoards(), LocalDateTime.now(), true,
                    EnumSet.of(OltCliSession.Section.ONTS));
            long stillMissing = countOntsMissingDetail(snapshot.getBoards());
            saveRecord(id, OltSyncRecord.ACTION_SYNC, true,
                    "ONT 详情补采：补齐 " + (missing - stillMissing) + " 台"
                            + (stillMissing > 0 ? "（还缺 " + stillMissing + " 台，设备未回详情）" : ""),
                    snapshot.getTranscript(), System.currentTimeMillis() - start, operator);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String message = rootMessage(e);
            log.warn("OLT ONT 详情补采失败 id={}: {}", id, message);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, false, "ONT 详情补采失败：" + message,
                    null, cost, operator);
        }
    }

    public void submitDeepSync(Long id, String operator) {
        if (!deepSyncing.add(id)) {
            return;
        }
        DEEP_POOL.submit(() -> {
            try {
                collectAndPersist(id, operator, "深度同步");
            } catch (Exception e) {
                log.warn("OLT 后台补采失败 id={}: {}", id, e.getMessage());
            } finally {
                deepSyncing.remove(id);
            }
        });
    }

    private int writeBoards(Long id, List<OltBoard> boards, LocalDateTime now, boolean mergeOld) {
        return writeBoards(id, boards, now, mergeOld, null);
    }

    /** 写库前把「本次没采到的字段」沿用库里旧值，别把上次采到的 ONT 明细 / 光模块抹掉 */
    private static void mergeUnsyncedPortData(OltBoard board, OltBoard old,
                                              Set<OltCliSession.Section> sections) {
        if (old.getPorts() == null || old.getPorts().isEmpty()) {
            return;
        }
        // sections 为 null（全量 / 快速同步）时按字段级兜底：哪个字段没采到（null）就沿旧
        boolean needStates = sections == null || !sections.contains(OltCliSession.Section.PORT_STATES);
        boolean needOnts = sections == null || !sections.contains(OltCliSession.Section.ONTS);
        boolean needOptics = sections == null || !sections.contains(OltCliSession.Section.OPTICS);
        if (!needStates && !needOnts && !needOptics) {
            return;
        }
        Map<Integer, OltPort> previous = new HashMap<>();
        for (OltPort port : old.getPorts()) {
            if (port.getPortNo() != null) {
                previous.put(port.getPortNo(), port);
            }
        }
        for (OltPort port : board.getPorts()) {
            OltPort prev = port.getPortNo() == null ? null : previous.get(port.getPortNo());
            if (prev == null) {
                continue;
            }
            if (needStates && port.getOpticStatus() == null && port.getLinkStatus() == null) {
                port.setOpticStatus(prev.getOpticStatus());
                port.setOpticalPresent(prev.getOpticalPresent());
                port.setLinkStatus(prev.getLinkStatus());
            }
            if (needOnts && port.getOnts() == null) {
                // 明细没采到但数量还在（>0）：多半是长表超时 / 解析没出来，沿旧别把列表抹成空；
                // 设备真删光了 onuTotal 是 0，照样落库清空
                if (port.getOnuTotal() == null || port.getOnuTotal() > 0) {
                    port.setOnts(prev.getOnts());
                }
                if (port.getOnuTotal() == null) {
                    port.setOnuTotal(prev.getOnuTotal());
                    port.setOnuOnline(prev.getOnuOnline());
                }
            }
            if (needOptics && port.getTxPower() == null && port.getRxPower() == null
                    && port.getModuleStatus() == null) {
                port.setTxPower(prev.getTxPower());
                port.setRxPower(prev.getRxPower());
                port.setOpticTemperature(prev.getOpticTemperature());
                port.setBiasCurrent(prev.getBiasCurrent());
                port.setSupplyVoltage(prev.getSupplyVoltage());
                port.setModuleStatus(prev.getModuleStatus());
                port.setLaserState(prev.getLaserState());
                port.setTxFault(prev.getTxFault());
            }
        }
    }

    private int writeBoards(Long id, List<OltBoard> boards, LocalDateTime now, boolean mergeOld,
                            Set<OltCliSession.Section> sections) {
        Map<Integer, OltBoard> previous = new HashMap<>();
        if (mergeOld) {
            boardMapper.selectList(new LambdaQueryWrapper<OltBoard>().eq(OltBoard::getOltId, id))
                    .forEach(board -> {
                        fillPorts(board);
                        if (board.getSlotNo() != null) {
                            previous.put(board.getSlotNo(), board);
                        }
                    });
        }
        boardMapper.delete(new LambdaQueryWrapper<OltBoard>().eq(OltBoard::getOltId, id));
        for (OltBoard board : boards) {
            OltBoard old = previous.get(board.getSlotNo());
            if (old != null) {
                if ((board.getPorts() == null || board.getPorts().isEmpty())
                        && old.getPorts() != null && !old.getPorts().isEmpty()) {
                    board.setPorts(old.getPorts());
                } else if (board.getPorts() != null) {
                    // 本次采到的端口上，没采的段沿用旧值（按端口号对齐）
                    mergeUnsyncedPortData(board, old, sections);
                }
                if (board.getTemperature() == null) {
                    board.setTemperature(old.getTemperature());
                }
            }
            board.setOltId(id);
            board.setSyncedAt(now);
            board.setPortsJson(writePorts(board.getPorts()));
            boardMapper.insert(board);
        }
        return boards.size();
    }

    /** 采集并落库的公共实现：返回落库后的最新详情 */
    private OltDetail collectAndPersist(Long id, String operator, String actionLabel) {        OltDevice device = requireDevice(id);
        long start = System.currentTimeMillis();
        try {
            OltSnapshot snapshot = telnetService.collect(device, true);
            long cost = System.currentTimeMillis() - start;
            LocalDateTime now = LocalDateTime.now();

            List<OltBoard> boards = snapshot.getBoards() == null ? List.of() : snapshot.getBoards();
            writeBoards(id, boards, now, false);

            String summary = "已同步 " + boards.size() + " 块板卡" + kindSummary(boards)
                    + (snapshot.getModel() == null ? "" : "，型号 " + snapshot.getModel());
            OltVlanInfo vlan = snapshot.getVlanInfo();
            if (vlan != null && !vlan.getVlans().isEmpty()) {
                summary = summary + "，VLAN " + (vlan.getTotal() == null ? vlan.getVlans().size() : vlan.getTotal())
                        + " 个（上行口 " + vlan.getPortVlans().size() + " 个已采放通）";
            }
            OltTemplateInfo template = snapshot.getTemplateInfo();
            if (template != null) {
                summary = summary + "，DBA 模板 " + template.getDbaProfiles().size()
                        + " 条 / 线路模板 " + template.getLineProfiles().size() + " 条";
            }
            // 命令没回显 / 回显解析不了时把原因带出来，页面不用去翻回显才知道为什么是 0 块
            if (snapshot.getNotes() != null && !snapshot.getNotes().isEmpty()) {
                summary = summary + "；" + String.join("；", snapshot.getNotes());
            }
            updateAfterConnect(id, snapshot, true, summary);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, true,
                    actionLabel + "：" + describe(snapshot) + "；" + summary,
                    snapshot.getTranscript(), cost, operator);
            return detail(id);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String message = rootMessage(e);
            log.warn("OLT {} 失败 id={}: {}", actionLabel, id, message);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, false, actionLabel + "失败：" + message,
                    null, cost, operator);
            markOffline(id, message);
            throw new IllegalStateException(actionLabel + "失败：" + message, e);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 单条 ONT 按需查询的动作 → 实际命令子串 */
    private static final Map<String, String> ONT_QUERY_ACTIONS = Map.of(
            "optical", "optical-info",
            "version", "version",
            "register", "register-info",
            "info", "info",
            "capability", "capability");

    /** 单条 ONT 按需查询：配置摘录 / 详情 / 光信息 / 版本 / 注册历史 */
    public OltOntQueryResult queryOnt(Long id, OltOntQueryRequest request, String operator) {
        OltDevice device = requireDevice(id);
        String action = request == null || request.getAction() == null ? "" : request.getAction().trim();
        if (request == null || request.getSlotNo() == null
                || request.getPortNo() == null || request.getOntId() == null) {
            throw new IllegalArgumentException("槽位、端口和 ONT ID 都不能为空");
        }
        long start = System.currentTimeMillis();
        try {
            OltOntQueryResult result;
            if ("config".equals(action)) {
                // 点序列号一次会话三条命令：配置摘录（授权行）+ 业务流表格 + 详情（管理 IP 在这里）
                String command = "display current-configuration ont 0/" + request.getSlotNo() + "/"
                        + request.getPortNo() + " " + request.getOntId()
                        + "；display service-port port 0/" + request.getSlotNo() + "/"
                        + request.getPortNo() + " ont " + request.getOntId()
                        + "；display ont info 0 " + request.getSlotNo() + " "
                        + request.getPortNo() + " " + request.getOntId();
                String[] outs = telnetService.queryOntConfigAndServicePorts(device,
                        request.getSlotNo(), request.getPortNo(), request.getOntId());
                String configRaw = outs[0];
                String portsRaw = outs[1];
                String infoRaw = outs[2];
                // 业务流优先表格回显（带 VLAN 属性 / 用户侧 VLAN / 状态），
                // 表格解不出再退回配置摘录里的 service-port 行；老版本整口查询按 VPI 筛本 ONT
                List<OltServicePort> servicePorts = new ArrayList<>(OltInfoParser.parseServicePorts(portsRaw));
                if (servicePorts.isEmpty()) {
                    servicePorts = OltInfoParser.parseServicePortsFromConfig(configRaw);
                }
                servicePorts.removeIf(port -> port.getOntId() != null
                        && !request.getOntId().equals(port.getOntId()));
                servicePorts.sort(Comparator.comparing(port -> port.getIndex() == null ? 0 : port.getIndex()));
                // 授权行给 SN/模板/描述，详情补管理 IP 等整板表没有的字段
                OltOntInfo ontInfo = OltInfoParser.parseOntBindInfo(configRaw);
                OltOntInfo detail = OltInfoParser.parseOntInfoDetail(infoRaw);
                if (ontInfo.getIp() == null) {
                    ontInfo.setIp(detail.getIp());
                }
                if (ontInfo.getSn() == null) {
                    ontInfo.setSn(detail.getSn());
                }
                if (ontInfo.getDesc() == null) {
                    ontInfo.setDesc(detail.getDesc());
                }
                result = new OltOntQueryResult();
                result.setCommand(command);
                result.setRaw(configRaw);
                result.setOntInfo(ontInfo);
                result.setServicePorts(servicePorts);
            } else if ("service-port".equals(action)) {
                // 业务流列「查看」：只查业务流表格，不带配置摘录
                String command = "display service-port port 0/" + request.getSlotNo() + "/"
                        + request.getPortNo() + " ont " + request.getOntId();
                String raw = telnetService.queryOntServicePorts(device,
                        request.getSlotNo(), request.getPortNo(), request.getOntId());
                List<OltServicePort> servicePorts = new ArrayList<>(OltInfoParser.parseServicePorts(raw));
                if (servicePorts.isEmpty()) {
                    servicePorts = OltInfoParser.parseServicePortsFromConfig(raw);
                }
                servicePorts.removeIf(port -> port.getOntId() != null
                        && !request.getOntId().equals(port.getOntId()));
                servicePorts.sort(Comparator.comparing(port -> port.getIndex() == null ? 0 : port.getIndex()));
                result = new OltOntQueryResult();
                result.setCommand(command);
                result.setRaw(raw);
                result.setServicePorts(servicePorts);
            } else {
                String subcommand = ONT_QUERY_ACTIONS.get(action);
                if (subcommand == null) {
                    throw new IllegalArgumentException("不支持的查询类型：" + action);
                }
                // info 用全坐标（display ont info 0 1 0 1）；capability 是 0/<槽位> <端口> <ONT ID>；
                // 光信息/版本/注册历史现场是在端口视图敲的
                String command;
                if ("info".equals(action)) {
                    command = "display ont info 0 " + request.getSlotNo()
                            + " " + request.getPortNo() + " " + request.getOntId();
                } else if ("capability".equals(action)) {
                    command = "display ont capability 0/" + request.getSlotNo()
                            + " " + request.getPortNo() + " " + request.getOntId();
                } else {
                    command = "display ont " + subcommand + " "
                            + request.getPortNo() + " " + request.getOntId();
                }
                String raw = telnetService.queryOnt(device,
                        request.getSlotNo(), request.getPortNo(), request.getOntId(), subcommand);
                result = new OltOntQueryResult();
                result.setCommand(command);
                result.setRaw(raw);
                List<LinkedHashMap<String, String>> blocks = OltInfoParser.parseOntKeyValueBlocks(raw);
                // optical-info 这类是表格式回显（一行列名 + 一行数据），key-value 解不出时按表格切
                if (blocks.isEmpty()) {
                    blocks = OltInfoParser.parseOntTableBlocks(raw);
                }
                result.setBlocks(blocks);
                // 详情里有线路/业务模板、IP、掉线原因这些整板表给不了的字段，单独结构化一份
                if ("info".equals(action)) {
                    result.setOntInfo(OltInfoParser.parseOntInfoDetail(raw));
                }
            }
            saveRecord(id, OltSyncRecord.ACTION_QUERY, true,
                    "ONT查询：" + result.getCommand(), result.getRaw(),
                    System.currentTimeMillis() - start, operator);
            return result;
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, OltSyncRecord.ACTION_QUERY, false,
                    "ONT查询失败：" + action + "：" + message, null,
                    System.currentTimeMillis() - start, operator);
            throw new IllegalStateException("ONT 查询失败：" + message, e);
        }
    }

    /** 新增 DBA 模板：构造 {@code dba-profile add ...} 下发，成功后自动重采写库 */
    /** 新增 DBA 模板的命令：{@code dba-profile add profile-id 10 profile-name "dba-100M" type4 max 102400} */
    public String dbaProfileCommand(OltDbaProfileRequest request) {
        Integer profileId = request.getProfileId();
        if (profileId == null || profileId < DBA_PROFILE_ID_MIN || profileId > DBA_PROFILE_ID_MAX) {
            throw new IllegalArgumentException("模板 ID 必须在 "
                    + DBA_PROFILE_ID_MIN + "-" + DBA_PROFILE_ID_MAX + " 之间");
        }
        String name = request.getName() == null ? "" : request.getName().trim();
        if (!name.matches(DBA_NAME_PATTERN)) {
            throw new IllegalArgumentException("模板名只能由数字、字母及下划线组成，最长 " + DBA_NAME_MAX + " 位");
        }
        String type = request.getType() == null ? "" : request.getType().trim().toLowerCase(Locale.ROOT);
        if (!type.matches("type[1-5]")) {
            throw new IllegalArgumentException("DBA 类型必须是 type1~type5");
        }
        Integer fix = positive(request.getFixKbps(), "固定带宽");
        Integer assure = positive(request.getAssureKbps(), "保证带宽");
        Integer max = positive(request.getMaxKbps(), "最大带宽");
        String head = "dba-profile add profile-id " + profileId + " profile-name \"" + name + "\" " + type;
        return switch (type) {
            case "type1" -> {
                requireNotNull(fix, "type1（固定带宽）需要填写固定带宽");
                yield head + " fix " + fix;
            }
            case "type2" -> {
                requireNotNull(assure, "type2（保证带宽）需要填写保证带宽");
                yield head + " assure " + assure;
            }
            case "type3" -> {
                requireNotNull(assure, "type3（保证+最大带宽）需要填写保证带宽");
                requireNotNull(max, "type3（保证+最大带宽）需要填写最大带宽");
                yield head + " assure " + assure + " max " + max;
            }
            case "type4" -> {
                requireNotNull(max, "type4（最大带宽）需要填写最大带宽");
                yield head + " max " + max;
            }
            default -> {
                requireNotNull(fix, "type5（固定+保证+最大带宽）需要填写固定带宽");
                requireNotNull(assure, "type5（固定+保证+最大带宽）需要填写保证带宽");
                requireNotNull(max, "type5（固定+保证+最大带宽）需要填写最大带宽");
                yield head + " fix " + fix + " assure " + assure + " max " + max;
            }
        };
    }

    /** 「新增 DBA 模板」表单的元数据：有哪五类、每类要填哪几个带宽、取值上下限 */
    public OltDbaForm dbaForm(Long id) {
        // 支不支持由设备命令目录说了算，不在页面里按厂商猜
        boolean supported = profileOf(id).actions().stream()
                .anyMatch(action -> action.code() != null && action.code().contains("dba"));
        return new OltDbaForm(supported, DBA_TYPES, DEFAULT_DBA_TYPE,
                DBA_PROFILE_ID_MIN, DBA_PROFILE_ID_MAX, DBA_NAME_MAX, DBA_NAME_PATTERN,
                DBA_BANDWIDTH_MIN, DBA_BANDWIDTH_MAX);
    }

    /** DBA 模板预演：不下发，只把命令算出来给页面看；填错的地方直接抛给页面显示 */
    public OltQuickSetupPreview previewDbaProfile(Long id, OltDbaProfileRequest request) {
        if (request == null) {
            return new OltQuickSetupPreview(List.of(), List.of("先填模板参数"));
        }
        List<String> warnings = new ArrayList<>();
        OltTemplateInfo template = readTemplate(requireDevice(id).getTemplateJson());
        if (template != null && request.getProfileId() != null && template.getDbaProfiles().stream()
                .anyMatch(profile -> request.getProfileId().equals(profile.getProfileId()))) {
            warnings.add("模板 ID " + request.getProfileId() + " 设备上已经有了，下发会被拒");
        }
        return new OltQuickSetupPreview(List.of(dbaProfileCommand(request)), warnings);
    }

    public OltDetail createDbaProfile(Long id, OltDbaProfileRequest request, String operator) {
        requireDevice(id);
        // 命令和校验都在 dbaProfileCommand 里，页面预览走的是同一个方法
        String command = dbaProfileCommand(request);
        Integer profileId = request.getProfileId();
        // 库里快照已有同 ID 就提前拦下（OLT 端才是最终裁决，这里只是友好提示）
        OltTemplateInfo template = readTemplate(requireDevice(id).getTemplateJson());
        if (template != null && template.getDbaProfiles().stream()
                .anyMatch(profile -> profileId.equals(profile.getProfileId()))) {
            throw new IllegalArgumentException("模板 ID " + profileId + " 已存在（如果刚在 OLT 上建过，先同步一次再操作）");
        }
        return dispatchConfig(id, List.of(command), operator, "新增DBA模板");
    }

    /** 删除 DBA 模板：逐条 {@code dba-profile delete profile-id X}，成功后自动重采写库 */
    public OltDetail deleteDbaProfiles(Long id, List<Integer> profileIds, String operator) {
        requireDevice(id);
        if (profileIds == null || profileIds.isEmpty()) {
            throw new IllegalArgumentException("先勾选要删除的模板");
        }
        List<String> commands = profileIds.stream()
                .map(profileId -> "dba-profile delete profile-id " + profileId)
                .toList();
        return dispatchConfig(id, commands, operator, "删除DBA模板");
    }

    // ------------------------------------------------- 线路模板：T-CONT / GEM Port / GEM 映射

    /** T-CONT 索引上限（华为 GPON 0-127） */
    private static final int MAX_TCONT_INDEX = 127;

    /** GEM Port 索引上限（华为 0-1023） */
    private static final int MAX_GEMPORT_INDEX = 1023;

    /** 一个 GEM Port 内的映射序号上限（华为 0-7） */
    private static final int MAX_GEM_MAPPING_INDEX = 7;

    /** 线路模板 ID 上限（华为 profile-id 0-2047，0 是系统默认模板不给改） */
    private static final int MAX_LINE_PROFILE_ID = 2047;

    /** 线路模板里加一条 T-CONT：{@code tcont 1 dba-profile-id 1000} */
    public OltDetail addLineTcont(Long id, OltTcontRequest request, String operator) {
        requireDevice(id);
        if (request == null || request.getProfileId() == null) {
            throw new IllegalArgumentException("先选线路模板");
        }
        int profileId = numberInRange(request.getProfileId(), 1, MAX_LINE_PROFILE_ID, "线路模板 ID");
        int tcontId = numberInRange(request.getTcontId(), 0, MAX_TCONT_INDEX, "T-CONT 索引");
        int dbaProfileId = numberInRange(request.getDbaProfileId(),
                DBA_PROFILE_ID_MIN, DBA_PROFILE_ID_MAX, "DBA 模板 ID");
        OltLineProfile line = snapshotLineProfile(id, profileId);
        if (line != null && line.getTconts() != null && line.getTconts().stream()
                .anyMatch(tcont -> Integer.valueOf(tcontId).equals(tcont.getTcontId()))) {
            throw new IllegalArgumentException("模板 " + profileId + " 里已经有 T-CONT " + tcontId
                    + " 了，换一个索引或直接在设备上改");
        }
        String ponType = ponTypeOf(request.getPonType(), line);
        List<String> commands = profileOf(id)
                .lineTcontAddCommands(ponType, profileId, tcontId, dbaProfileId);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("这台设备的厂商还没接入线路模板下发");
        }
        return dispatchLineProfile(id, profileId, ponType, commands, operator,
                "线路模板 " + profileId + " 加 T-CONT " + tcontId + "（DBA " + dbaProfileId + "）");
    }

    /** 线路模板里加一个 GEM Port：{@code gem add 1 eth tcont 1 encrypt on} */
    public OltDetail addLineGemport(Long id, OltGemportRequest request, String operator) {
        requireDevice(id);
        if (request == null || request.getProfileId() == null) {
            throw new IllegalArgumentException("先选线路模板");
        }
        int profileId = numberInRange(request.getProfileId(), 1, MAX_LINE_PROFILE_ID, "线路模板 ID");
        int gemportId = numberInRange(request.getGemportId(), 0, MAX_GEMPORT_INDEX, "GEM Port 索引");
        int tcontId = numberInRange(request.getTcontId(), 0, MAX_TCONT_INDEX, "T-CONT 索引");
        OltLineProfile line = snapshotLineProfile(id, profileId);
        if (line != null) {
            boolean tcontExists = line.getTconts() != null && line.getTconts().stream()
                    .anyMatch(tcont -> Integer.valueOf(tcontId).equals(tcont.getTcontId()));
            if (!tcontExists && line.getTconts() != null && !line.getTconts().isEmpty()) {
                throw new IllegalArgumentException("模板 " + profileId + " 里没有 T-CONT " + tcontId
                        + "，先把 T-CONT 配上，GEM Port 才有地方挂");
            }
            boolean gemExists = line.getGemports() != null && line.getGemports().stream()
                    .anyMatch(gem -> Integer.valueOf(gemportId).equals(gem.getGemportId()));
            if (gemExists) {
                throw new IllegalArgumentException("模板 " + profileId + " 里已经有 GEM Port "
                        + gemportId + " 了，换一个索引");
            }
        }
        String ponType = ponTypeOf(request.getPonType(), line);
        List<String> commands = profileOf(id)
                .lineGemportAddCommands(ponType, profileId, gemportId, tcontId);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("这台设备的厂商还没接入线路模板下发");
        }
        return dispatchLineProfile(id, profileId, ponType, commands, operator,
                "线路模板 " + profileId + " 加 GEM Port " + gemportId + "（T-CONT " + tcontId + "）");
    }

    /** 给 GEM Port 加映射：{@code gem mapping  <映射序号> vlan } */
    public OltDetail addLineGemMapping(Long id, OltGemMappingRequest request, String operator) {
        requireDevice(id);
        if (request == null || request.getProfileId() == null) {
            throw new IllegalArgumentException("先选线路模板");
        }
        int profileId = numberInRange(request.getProfileId(), 1, MAX_LINE_PROFILE_ID, "线路模板 ID");
        int gemportId = numberInRange(request.getGemportId(), 0, MAX_GEMPORT_INDEX, "GEM Port 索引");
        List<int[]> mappings = pairMappings(request.getMappingIds(), request.getVlans());
        OltLineProfile line = snapshotLineProfile(id, profileId);
        if (line != null && line.getGemports() != null) {
            OltGemport gem = line.getGemports().stream()
                    .filter(item -> Integer.valueOf(gemportId).equals(item.getGemportId()))
                    .findFirst().orElse(null);
            if (gem != null && gem.getMappings() != null) {
                for (int[] mapping : mappings) {
                    boolean exists = gem.getMappings().stream()
                            .anyMatch(item -> Integer.valueOf(mapping[0]).equals(item.getIndex()));
                    if (exists) {
                        throw new IllegalArgumentException("GEM Port " + gemportId + " 里已经有映射 "
                                + mapping[0] + " 了，换一个序号");
                    }
                }
            }
        }
        String ponType = ponTypeOf(request.getPonType(), line);
        List<String> commands = profileOf(id)
                .lineGemMappingAddCommands(ponType, profileId, gemportId, mappings);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("这台设备的厂商还没接入线路模板下发");
        }
        StringBuilder vlans = new StringBuilder();
        for (int[] mapping : mappings) {
            if (vlans.length() > 0) {
                vlans.append(',');
            }
            vlans.append(mapping[1]);
        }
        return dispatchLineProfile(id, profileId, ponType, commands, operator,
                "线路模板 " + profileId + " 给 GEM Port " + gemportId + " 加映射 VLAN " + vlans);
    }

    /** 映射序号与 VLAN 配对：「1,2」+「46,41」→ [[1,46],[2,41]] */
    private static List<int[]> pairMappings(String mappingIds, String vlans) {
        List<Integer> vlanIds = csvInts(vlans);
        if (vlanIds.isEmpty()) {
            throw new IllegalArgumentException("先填业务 VLAN，例如 46 或 46,41");
        }
        vlanIds.forEach(vlanId -> checkVlanId(vlanId, "业务 VLAN"));
        List<Integer> ids = csvInts(mappingIds);
        if (ids.isEmpty()) {
            // 没填序号就从 0 顺延，跟设备默认的 gem mapping <gem> 0/1/2 一致
            ids = new ArrayList<>();
            for (int index = 0; index < vlanIds.size(); index++) {
                ids.add(index);
            }
        }
        if (ids.size() != vlanIds.size()) {
            throw new IllegalArgumentException("GEM 映射索引 " + ids.size() + " 个，业务 VLAN "
                    + vlanIds.size() + " 个，必须一一对应");
        }
        List<int[]> mappings = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            int mappingId = ids.get(index);
            if (mappingId < 0 || mappingId > MAX_GEM_MAPPING_INDEX) {
                throw new IllegalArgumentException("GEM 映射索引必须在 0-" + MAX_GEM_MAPPING_INDEX + " 之间");
            }
            mappings.add(new int[]{mappingId, vlanIds.get(index)});
        }
        return mappings;
    }

    /** 「1,2」→ [1,2]：逗号/顿号/空格都认，非数字直接指出是哪一个 */
    private static List<Integer> csvInts(String text) {
        List<Integer> values = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return values;
        }
        for (String piece : text.split("[,，;；\\s]+")) {
            if (piece.isBlank()) {
                continue;
            }
            try {
                values.add(Integer.parseInt(piece.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("「" + piece + "」不是数字");
            }
        }
        return values;
    }

    /** 线路模板三个弹窗的表单元数据：默认值、已占用的索引、可用区间、提示文字 */
    public OltLineProfileForm lineProfileForm(Long id, Integer profileId, Integer gemportId) {
        requireDevice(id);
        if (profileId == null) {
            throw new IllegalArgumentException("先选线路模板");
        }
        OltLineProfile line = snapshotLineProfile(id, profileId);
        boolean supported = !profileOf(id)
                .lineTcontAddCommands("gpon", profileId, 0, DBA_PROFILE_ID_MIN).isEmpty();
        String ponType = ponTypeOf(null, line);
        String label = lineProfileLabel(profileId, line == null ? null : line.getName());

        List<Integer> tcontUsed = sortedIds(line == null || line.getTconts() == null
                ? List.of()
                : line.getTconts().stream().map(OltTcont::getTcontId).toList());
        // 新增 GEM Port 默认挂的那条：已配的 GEM Port 用哪条就用哪条（表格里那一列），
        // 快照里排在前面的号可能是设备上残留的，不能拿它当默认
        Integer tcontDefault = tcontOfGemports(line);
        if (tcontDefault == null && !tcontUsed.isEmpty()) {
            tcontDefault = tcontUsed.get(0);
        }
        Integer tcontNext = firstFree(tcontUsed, MAX_TCONT_INDEX);
        String tcontHint = tcontDefault == null
                ? "先给这条模板加 T-CONT"
                : "0-" + MAX_TCONT_INDEX;
        OltLineProfileForm.IndexForm tcont = new OltLineProfileForm.IndexForm(
                0, MAX_TCONT_INDEX, tcontDefault, tcontUsed, tcontNext, tcontHint);

        List<Integer> gemUsed = sortedIds(line == null || line.getGemports() == null
                ? List.of()
                : line.getGemports().stream().map(OltGemport::getGemportId).toList());
        Integer gemNext = firstFree(gemUsed, MAX_GEMPORT_INDEX);
        String gemHint = gemNext == null
                ? "GEM Port 已经用满（0-" + MAX_GEMPORT_INDEX + "）"
                : "可用的 GEM Port: " + gemNext + "-" + MAX_GEMPORT_INDEX;
        OltLineProfileForm.IndexForm gemport = new OltLineProfileForm.IndexForm(
                0, MAX_GEMPORT_INDEX, null, gemUsed, gemNext, gemHint);

        List<Integer> mapUsed = List.of();
        if (line != null && gemportId != null && line.getGemports() != null) {
            OltGemport gem = line.getGemports().stream()
                    .filter(item -> gemportId.equals(item.getGemportId()))
                    .findFirst().orElse(null);
            if (gem != null && gem.getMappings() != null) {
                mapUsed = sortedIds(gem.getMappings().stream().map(OltGemMapping::getIndex).toList());
            }
        }
        Integer mapNext = firstFree(mapUsed, MAX_GEM_MAPPING_INDEX);
        String mapHint = mapNext == null
                ? "GEM 映射序号已经用满（0-" + MAX_GEM_MAPPING_INDEX + "）"
                : "可用的 GEM 映射索引：" + mapNext + "-" + MAX_GEM_MAPPING_INDEX;
        OltLineProfileForm.IndexForm mapping = new OltLineProfileForm.IndexForm(
                0, MAX_GEM_MAPPING_INDEX, mapNext, mapUsed, mapNext, mapHint);

        List<OltLineProfileForm.DbaOption> dbaOptions = new ArrayList<>();
        Integer defaultDbaId = null;
        OltTemplateInfo template = readTemplate(requireDevice(id).getTemplateJson());
        if (template != null && template.getDbaProfiles() != null) {
            for (OltDbaProfile dba : template.getDbaProfiles()) {
                if (dba.getProfileId() == null) {
                    continue;
                }
                dbaOptions.add(new OltLineProfileForm.DbaOption(dba.getProfileId(),
                        dba.getProfileId() + (dba.getName() == null || dba.getName().isBlank()
                                ? "" : " · " + dba.getName())));
            }
            dbaOptions.sort(Comparator.comparing(OltLineProfileForm.DbaOption::profileId));
            // 默认沿用模板里已有那条 T-CONT 的 DBA，没有就第一条
            final Integer prefer = line != null && line.getTconts() != null && !line.getTconts().isEmpty()
                    ? line.getTconts().get(0).getDbaProfileId()
                    : null;
            defaultDbaId = prefer != null && dbaOptions.stream()
                    .anyMatch(option -> prefer.equals(option.profileId()))
                    ? prefer
                    : (dbaOptions.isEmpty() ? null : dbaOptions.get(0).profileId());
        }
        return new OltLineProfileForm(profileId, line == null ? null : line.getName(), label, ponType,
                supported, tcont, gemport, mapping, dbaOptions, defaultDbaId);
    }

    /** 线路模板三个弹窗的命令预演：不下发，只把命令算出来给页面看 */
    public OltQuickSetupPreview previewLineProfile(Long id, OltLineProfilePreviewRequest request) {
        if (request == null || request.getKind() == null || request.getKind().isBlank()) {
            throw new IllegalArgumentException("先选操作类型");
        }
        if (request.getProfileId() == null) {
            throw new IllegalArgumentException("先选线路模板");
        }
        int profileId = numberInRange(request.getProfileId(), 1, MAX_LINE_PROFILE_ID, "线路模板 ID");
        OltLineProfile line = snapshotLineProfile(id, profileId);
        String ponType = ponTypeOf(request.getPonType(), line);
        List<String> warnings = new ArrayList<>();
        String kind = request.getKind().trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "tcont" -> {
                int tcontId = numberInRange(parseIndex(request.getTcontId()),
                        0, MAX_TCONT_INDEX, "T-CONT 索引");
                int dbaProfileId = numberInRange(parseIndex(request.getDbaProfileId()),
                        DBA_PROFILE_ID_MIN, DBA_PROFILE_ID_MAX, "DBA 模板 ID");
                if (tcontExists(line, tcontId)) {
                    warnings.add("模板 " + profileId + " 里已经有 T-CONT " + tcontId + " 了，下发会被拒");
                }
                if (dbaMissing(id, dbaProfileId)) {
                    warnings.add("采到的 DBA 模板里没有 " + dbaProfileId + "，设备上可能还没建");
                }
                return new OltQuickSetupPreview(
                        profileOf(id).lineTcontAddCommands(ponType, profileId, tcontId, dbaProfileId), warnings);
            }
            case "gemport" -> {
                int gemportId = numberInRange(parseIndex(request.getGemportId()),
                        0, MAX_GEMPORT_INDEX, "GEM Port 索引");
                int tcontId = numberInRange(parseIndex(request.getTcontId()),
                        0, MAX_TCONT_INDEX, "T-CONT 索引");
                if (gemportExists(line, gemportId)) {
                    warnings.add("模板 " + profileId + " 里已经有 GEM Port " + gemportId + " 了，下发会被拒");
                }
                if (!tcontExists(line, tcontId)) {
                    warnings.add("模板 " + profileId + " 里没有 T-CONT " + tcontId + "，先把它配上");
                }
                return new OltQuickSetupPreview(
                        profileOf(id).lineGemportAddCommands(ponType, profileId, gemportId, tcontId), warnings);
            }
            case "mapping" -> {
                int gemportId = numberInRange(parseIndex(request.getGemportId()),
                        0, MAX_GEMPORT_INDEX, "GEM Port 索引");
                List<int[]> mappings = pairMappings(request.getMappingIds(), request.getVlans());
                if (line != null && line.getGemports() != null) {
                    OltGemport gem = line.getGemports().stream()
                            .filter(item -> Integer.valueOf(gemportId).equals(item.getGemportId()))
                            .findFirst().orElse(null);
                    if (gem != null && gem.getMappings() != null) {
                        for (int[] mapping : mappings) {
                            boolean exists = gem.getMappings().stream()
                                    .anyMatch(item -> Integer.valueOf(mapping[0]).equals(item.getIndex()));
                            if (exists) {
                                warnings.add("GEM Port " + gemportId + " 里已经有映射 " + mapping[0]
                                        + " 了，下发会被拒");
                            }
                        }
                    }
                }
                return new OltQuickSetupPreview(
                        profileOf(id).lineGemMappingAddCommands(ponType, profileId, gemportId, mappings), warnings);
            }
            default -> throw new IllegalArgumentException("不认识的操作类型：" + request.getKind());
        }
    }

    /** 文本框里的索引：空 → null（交给取值范围校验去说「请填写」） */
    private static Integer parseIndex(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("「" + text.trim() + "」不是数字");
        }
    }

    /** 已占用的索引里第一个空号；用满了返回 null */
    private static Integer firstFree(List<Integer> used, int max) {
        for (int index = 0; index <= max; index++) {
            final int candidate = index;
            if (used.stream().noneMatch(value -> Integer.valueOf(candidate).equals(value))) {
                return index;
            }
        }
        return null;
    }

    private static List<Integer> sortedIds(List<Integer> ids) {
        return ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static boolean tcontExists(OltLineProfile line, int tcontId) {
        return line != null && line.getTconts() != null && line.getTconts().stream()
                .anyMatch(tcont -> Integer.valueOf(tcontId).equals(tcont.getTcontId()));
    }

    private static boolean gemportExists(OltLineProfile line, int gemportId) {
        return line != null && line.getGemports() != null && line.getGemports().stream()
                .anyMatch(gem -> Integer.valueOf(gemportId).equals(gem.getGemportId()));
    }

    /** 已配的 GEM Port 都挂在哪个 T-CONT 上（新增 GEM Port 的默认值就取它） */
    private static Integer tcontOfGemports(OltLineProfile line) {
        if (line == null || line.getGemports() == null) {
            return null;
        }
        return line.getGemports().stream()
                .map(OltGemport::getTcontId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** 采到的 DBA 模板里有没有这条（没有就是悬空引用，设备大概率会拒） */
    private boolean dbaMissing(Long id, int dbaProfileId) {
        OltTemplateInfo template = readTemplate(requireDevice(id).getTemplateJson());
        return template == null || template.getDbaProfiles() == null || template.getDbaProfiles().stream()
                .noneMatch(dba -> Integer.valueOf(dbaProfileId).equals(dba.getProfileId()));
    }

    /** 「100（FTTH-GPON-1000）」：弹窗顶部那行只读的模板标识 */
    private static String lineProfileLabel(Integer profileId, String name) {
        if (profileId == null) {
            return "";
        }
        return name == null || name.isBlank() ? String.valueOf(profileId) : profileId + "（" + name + "）";
    }

    /** 库里快照中的这条线路模板；没采到返回 null */
    private OltLineProfile snapshotLineProfile(Long id, int profileId) {
        OltTemplateInfo template = readTemplate(requireDevice(id).getTemplateJson());
        if (template == null || template.getLineProfiles() == null) {
            return null;
        }
        return template.getLineProfiles().stream()
                .filter(profile -> Integer.valueOf(profileId).equals(profile.getProfileId()))
                .findFirst().orElse(null);
    }

    /** PON 类型：页面填的优先，其次跟着快照里这条模板，最后按 gpon */
    private static String ponTypeOf(String requested, OltLineProfile line) {
        String value = requested != null && !requested.isBlank()
                ? requested.trim().toLowerCase(Locale.ROOT) : "";
        if (value.isEmpty() && line != null && line.getType() != null) {
            value = line.getType().trim().toLowerCase(Locale.ROOT);
        }
        return "epon".equals(value) ? "epon" : "gpon";
    }

    private static int numberInRange(Integer value, int min, int max, String label) {
        if (value == null) {
            throw new IllegalArgumentException("请填写" + label);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(label + " 必须在 " + min + "-" + max + " 之间");
        }
        return value;
    }

    /** 线路模板配置的公共流程：下发 → 写采集记录 → 只重读这条模板 → 返回最新详情 */
    private OltDetail dispatchLineProfile(Long id, int profileId, String ponType,
                                         List<String> commands, String operator, String label) {
        OltDevice device = requireDevice(id);
        long start = System.currentTimeMillis();
        String actionType = recordTypeOf(label);
        try {
            List<String> outputs = telnetService.sendConfig(device, commands, false);
            saveRecord(id, actionType, true,
                    label + "：" + String.join("；", commands), String.join("\n", outputs),
                    System.currentTimeMillis() - start, operator);
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, actionType, false,
                    label + "失败：" + String.join("；", commands) + "：" + message, null,
                    System.currentTimeMillis() - start, operator);
            throw new IllegalStateException(message, e);
        }
        return refreshLineProfile(id, profileId, ponType, operator, label, start);
    }

    /** 只重读一条线路模板并覆盖快照里的那条（新采到的是空的字段沿用旧值，别把明细刷没了） */
    private OltDetail refreshLineProfile(Long id, int profileId, String ponType,
                                         String operator, String label, long start) {
        OltDevice device = requireDevice(id);
        OltLineProfile fresh;
        try {
            fresh = telnetService.collectLineProfile(device, profileId, ponType);
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, false,
                    label + "已下发，但重读线路模板失败：" + message, null,
                    System.currentTimeMillis() - start, operator);
            throw new IllegalStateException("配置已下发，但重新读取线路模板失败：" + message
                    + "，请点上面的「同步」再试一次", e);
        }
        if (fresh != null) {
            // 还没采过模板（template_json 空）时也照写：这条刚读到的就是第一份数据
            OltTemplateInfo template = readTemplate(device.getTemplateJson());
            if (template == null) {
                template = new OltTemplateInfo();
            }
            if (template.getLineProfiles() != null) {
                OltLineProfile old = template.getLineProfiles().stream()
                        .filter(profile -> Integer.valueOf(profileId).equals(profile.getProfileId()))
                        .findFirst().orElse(null);
                if (old != null) {
                    if ((fresh.getTconts() == null || fresh.getTconts().isEmpty())
                            && old.getTconts() != null) {
                        fresh.setTconts(old.getTconts());
                    }
                    if ((fresh.getGemports() == null || fresh.getGemports().isEmpty())
                            && old.getGemports() != null) {
                        fresh.setGemports(old.getGemports());
                    }
                    if (fresh.getName() == null || fresh.getName().isBlank()) {
                        fresh.setName(old.getName());
                    }
                    if (fresh.getType() == null || fresh.getType().isBlank()) {
                        fresh.setType(old.getType());
                    }
                }
                template.getLineProfiles().removeIf(profile ->
                        Integer.valueOf(profileId).equals(profile.getProfileId()));
                template.getLineProfiles().add(fresh);
                template.getLineProfiles().sort(Comparator.comparing(OltLineProfile::getProfileId,
                        Comparator.nullsLast(Comparator.naturalOrder())));
                template.stamp();
                deviceMapper.update(null, new LambdaUpdateWrapper<OltDevice>()
                        .eq(OltDevice::getId, id)
                        .set(OltDevice::getTemplateJson, writeTemplate(template)));
            }
        }
        saveRecord(id, OltSyncRecord.ACTION_SYNC, true, label + "后重读线路模板 " + profileId, null,
                System.currentTimeMillis() - start, operator);
        return detail(id);
    }

    /** 新建 VLAN：鸿升光下发 {@code vlan standard }（一段连续 ID 写成 */
    public OltDetail createVlan(Long id, OltVlanRequest request, String operator) {
        List<int[]> segments = vlanSegments(request);
        // 库里快照已有就提前拦下（设备端才是最终裁决，这里只是少跑一个来回）
        List<Integer> existing = existingVlanIds(id, segmentIds(segments));
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException("VLAN " + joinIds(existing)
                    + " 已存在（如果刚在设备上建过，先同步一次再操作）");
        }
        // 命令生成与页面预览同源，改一处两边一起变
        List<String> commands = vlanCommands(id, request);
        OltVendorProfile profile = profileOf(id);
        boolean withPorts = !normalizeVlanPorts(profile, request == null ? null : request.getTagged()).isEmpty()
                || !normalizeVlanPorts(profile, request == null ? null : request.getUntagged()).isEmpty();
        return dispatchConfig(id, commands, operator,
                "新增VLAN " + describeVlans(segments) + (withPorts ? "（含端口）" : ""));
    }

    /** 新建 VLAN 的命令（含校验）：{@code vlan standard 41} → {@code port epon 1-4 tagged} → {@code exit} */
    public List<String> vlanCommands(Long id, OltVlanRequest request) {
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsVlanConfig()) {
            throw new IllegalArgumentException("这台设备的厂商还没接入 VLAN 下发，先到设备上配好再同步");
        }
        List<String> tagged = normalizeVlanPorts(profile, request == null ? null : request.getTagged());
        List<String> untagged = normalizeVlanPorts(profile, request == null ? null : request.getUntagged());
        List<int[]> segments = vlanSegments(request);
        boolean withPorts = !tagged.isEmpty() || !untagged.isEmpty();
        int limit = withPorts ? MAX_VLANS_WITH_PORTS : MAX_VLANS_BATCH;
        if (segmentIds(segments).size() > limit) {
            throw new IllegalArgumentException(withPorts
                    ? "一次最多给 " + MAX_VLANS_WITH_PORTS + " 个 VLAN 配成员端口（每个 VLAN 都要单独进视图），请分几次建"
                    : "一次最多建 " + MAX_VLANS_BATCH + " 个 VLAN，请分几次建");
        }
        List<String> commands = new ArrayList<>();
        for (int[] segment : segments) {
            commands.addAll(profile.createVlanCommands(segment[0], segment[1], tagged, untagged));
        }
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("没生成要下发的命令，VLAN 未变更");
        }
        return commands;
    }

    /** 「新建 VLAN」表单的元数据：ID 范围、一次最多几个、哪些端口能做成员 */
    public OltVlanForm vlanForm(Long id) {
        OltVendorProfile profile = profileOf(id);
        List<String> portTypes = VLAN_PORT_TYPE_ORDER.stream()
                .filter(type -> profile.vlanMemberPortTypes().contains(type))
                .toList();
        return new OltVlanForm(profile.supportsVlanConfig(), 1, VlanIdExpression.MAX_VLAN_ID,
                MAX_VLANS_BATCH, MAX_VLANS_WITH_PORTS, portTypes, VLAN_PORT_EXCLUSIVE_TAG_MODE);
    }

    /** VLAN 预演：不下发，只把命令算出来给页面看；填错的地方直接抛给页面显示 */
    public OltQuickSetupPreview previewVlan(Long id, OltVlanRequest request) {
        List<String> warnings = new ArrayList<>();
        List<Integer> existing = existingVlanIds(id, segmentIds(vlanSegments(request)));
        if (!existing.isEmpty()) {
            warnings.add("VLAN " + joinIds(existing) + " 在设备快照里已经有了，下发会被拒（先同步一次再建）");
        }
        return new OltQuickSetupPreview(vlanCommands(id, request), warnings);
    }

    /** 快照里已经存在的 VLAN，用来提前提醒（设备端才是最终裁决） */
    private List<Integer> existingVlanIds(Long id, List<Integer> ids) {
        OltVlanInfo snapshot = readVlan(requireDevice(id).getVlanJson());
        if (snapshot == null || snapshot.getVlans() == null || ids.isEmpty()) {
            return List.of();
        }
        List<Integer> existing = new ArrayList<>();
        for (Integer vlanId : ids) {
            boolean hit = snapshot.getVlans().stream()
                    .anyMatch(vlan -> vlan.getVlanId() != null && vlan.getVlanId().equals(vlanId));
            if (hit) {
                existing.add(vlanId);
            }
        }
        return existing;
    }

    /** [[41,41],[45,46]] → [41,45,46] */
    private static List<Integer> segmentIds(List<int[]> segments) {
        List<Integer> ids = new ArrayList<>();
        for (int[] segment : segments) {
            for (int vlanId = segment[0]; vlanId <= segment[1]; vlanId++) {
                ids.add(vlanId);
            }
        }
        return ids;
    }

    /** [41,45] → {@code 41、45} */
    private static String joinIds(List<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining("、"));
    }

    /** TR-069 模板列表：不读库，每次实时登设备查 */
    public List<OltTr069Profile> tr069Profiles(Long id) {
        OltDevice device = requireDevice(id);
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsTr069Profile()) {
            throw new IllegalArgumentException("这台设备还没接入 TR-069 模板命令");
        }
        try {
            List<String> outputs = telnetService.queryCommands(device, profile.tr069ProfileQueryCommands());
            return profile.parseTr069Profiles(outputs.isEmpty() ? null : outputs.get(0));
        } catch (Exception e) {
            String message = rootMessage(e);
            log.warn("查询 TR-069 模板失败 id={}: {}", id, message);
            throw new IllegalStateException("查询 TR-069 模板失败：" + message, e);
        }
    }

    /** 待注册 ONT：实时登设备执行 {@code display ont autofind all}，拿到已经上报 SN、 */
    public List<OltOntAutofind> ontAutofind(Long id) {
        OltDevice device = requireDevice(id);
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsOntAutofind()) {
            throw new IllegalArgumentException("这台设备还没接入待注册 ONT 的采集命令");
        }
        String raw;
        try {
            List<String> outputs = telnetService.queryCommands(device, profile.ontAutofindCommands());
            raw = outputs.isEmpty() ? null : outputs.get(0);
        } catch (Exception e) {
            String message = rootMessage(e);
            log.warn("查询待注册 ONT 失败 id={}: {}", id, message);
            throw new IllegalStateException("查询待注册 ONT 失败：" + message, e);
        }
        // 回显为空和设备不认这条命令，都是「命令视图不对」的症状，先分开记下来
        if (raw == null || raw.isBlank()) {
            log.warn("查询待注册 ONT：设备没有回显 id={} 命令={}", id, profile.ontAutofindCommands());
            return List.of();
        }
        // 没有待注册 ONT 时华为回的是
        // "Failure: The automatically found ONTs do not exist"：带 Failure 字样，但是正常空结果
        if (OltInfoParser.autofindIsEmpty(raw)) {
            log.info("查询待注册 ONT：设备回没有待注册 ONT id={}", id);
            return List.of();
        }
        if (OltInfoParser.looksUnsupported(raw)) {
            throw new IllegalStateException("设备不认这条命令，回显：" + raw);
        }
        List<OltOntAutofind> items = profile.parseOntAutofind(raw);
        if (items.isEmpty()) {
            // 有回显却一条都没解析出来：不是设备没有，是回显格式没认出来。
            // 不打这条日志的话，页面只会显示「没有待注册光猫」，没法区分这两种情况
            log.warn("查询待注册 ONT：回显有内容但一条都没解析出来，格式没对上 id={} 回显:\n{}", id, raw);
        }
        return items;
    }

    /** 各 PON 口自动发现开关：逐块 GPON 业务板进视图，一个口一条命令问设备 */
    public OltOntAutoFindStatus ontAutoFindStatus(Long id) {
        OltDevice device = requireDevice(id);
        OltVendorProfile profile = profileOf(id);
        OltOntAutoFindStatus status = new OltOntAutoFindStatus();
        if (!profile.supportsOntAutoFind()) {
            status.setMessage("这台设备还没接入自动发现的查询命令");
            return status;
        }
        List<OltBoard> boards = gponBoards(id);
        if (boards.isEmpty()) {
            status.setMessage("没采到 GPON 业务板，先同步一次板卡信息");
            return status;
        }

        // 命令与端口一一对应：进出视图那几条 fsp 记 null，每个 display 对应一个口
        List<String> commands = new ArrayList<>();
        List<String> commandFsp = new ArrayList<>();
        int frame = 0;   // MA5600T 系列是单框设备，机框号固定 0
        for (OltBoard board : boards) {
            commands.add(profile.ponViewCommand(frame, board.getSlotNo()));
            commandFsp.add(null);
            for (int port = 0; port < board.getPortCount(); port++) {
                commands.add(profile.ontAutoFindQueryCommand(port));
                commandFsp.add(frame + "/" + board.getSlotNo() + "/" + port);
            }
            commands.add("quit");
            commandFsp.add(null);
        }

        List<String> outputs;
        try {
            outputs = telnetService.queryCommands(device, commands);
        } catch (Exception e) {
            String message = rootMessage(e);
            log.warn("查询 PON 口自动发现失败 id={}: {}", id, message);
            status.setMessage("查询失败：" + message);
            return status;
        }

        int total = 0;
        int enabled = 0;
        List<String> disabled = new ArrayList<>();
        for (int i = 0; i < commands.size() && i < outputs.size(); i++) {
            if (commandFsp.get(i) == null) {
                continue;
            }
            total++;
            Boolean state = profile.parseOntAutoFindState(outputs.get(i));
            if (Boolean.TRUE.equals(state)) {
                enabled++;
            } else if (Boolean.FALSE.equals(state)) {
                disabled.add(commandFsp.get(i));
            }
            // state 为 null（这个口认不出来）时两边都不算，避免把「查不到」说成「没开」
        }
        status.setSupported(true);
        status.setTotalPorts(total);
        status.setEnabledPorts(enabled);
        status.setDisabledPorts(disabled);
        status.setAllEnabled(total > 0 && enabled == total);
        if (total > 0 && enabled == 0 && disabled.isEmpty()) {
            status.setMessage("这个版本不认 display ont-auto-find，开关状态查不到，但仍可以直接开启");
        }
        return status;
    }

    /** 一键开启所有 GPON 业务板 PON 口的自动发现 */
    public OltDetail enableOntAutoFind(Long id, String operator) {
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsOntAutoFind()) {
            throw new IllegalArgumentException("这台设备还没接入自动发现的命令");
        }
        List<OltBoard> boards = gponBoards(id);
        if (boards.isEmpty()) {
            throw new IllegalArgumentException("没采到 GPON 业务板，先同步一次板卡信息再开");
        }
        List<String> fspList = new ArrayList<>();
        for (OltBoard board : boards) {
            for (int port = 0; port < board.getPortCount(); port++) {
                fspList.add("0/" + board.getSlotNo() + "/" + port);
            }
        }
        List<String> commands = profile.ontAutoFindEnableCommands(fspList);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("没生成要下发的命令，自动发现未开启");
        }
        return dispatchConfig(id, commands, operator, "开启 PON 口自动发现");
    }

    /** 建 OLT 本地用户：登设备跑 {@code terminal user name}，按设备的提问顺序 */
    public OltDetail createTerminalUser(Long id, OltTerminalUserRequest request, String operator) {
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsTerminalUser()) {
            throw new IllegalArgumentException("这台设备还没接入建用户的命令");
        }
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new IllegalArgumentException("用户名和密码都要填");
        }
        String username = request.getUsername().trim();
        if (username.length() < 6 || username.length() > 15) {
            throw new IllegalArgumentException("用户名要 6~15 位（设备限制）");
        }
        String password = request.getPassword();
        if (password.length() < 6 || password.length() > 15) {
            throw new IllegalArgumentException("密码要 6~15 位（设备限制）");
        }
        if (request.getLevel() != null && (request.getLevel() < 1 || request.getLevel() > 3)) {
            throw new IllegalArgumentException("权限级别只能是 1（Common User）、2（Operator）、3（Administrator）");
        }
        List<String> commands = profile.terminalUserAddCommands(request);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("参数不合设备要求，没生成要下发的命令");
        }
        return dispatchConfig(id, commands, operator, "建 OLT 用户 " + username);
    }

    /** 已装的 GPON 业务板；EPON 板命令不一样，这里先不碰 */
    private List<OltBoard> gponBoards(Long id) {
        return boardMapper.selectList(new LambdaQueryWrapper<OltBoard>()
                        .eq(OltBoard::getOltId, id)
                        .orderByAsc(OltBoard::getSlotNo))
                .stream()
                .filter(board -> "SERVICE".equalsIgnoreCase(board.getBoardKind()))
                .filter(board -> board.getBoardType() != null
                        && board.getBoardType().toUpperCase(Locale.ROOT).contains("GP"))
                .filter(board -> board.getPortCount() != null && board.getPortCount() > 0)
                .collect(Collectors.toList());
    }

    /** 删除业务板上的全部 ONT：只发现场那几条命令——{@code display board 0} 认业务板槽位 → */
    public OltDetail deleteAllOnts(Long id, OltOntDeleteAllRequest request, String operator) {
        OltDevice device = requireDevice(id);
        List<Integer> wanted = request == null || request.getSlots() == null
                ? List.of() : request.getSlots();
        long start = System.currentTimeMillis();
        List<OltOntDeleteResult> results;
        try {
            results = telnetService.deleteBoardOnts(device, wanted);
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, OltSyncRecord.ACTION_DELETE, false,
                    "删除业务板 ONT 失败：" + message, null, System.currentTimeMillis() - start, operator);
            throw new IllegalStateException(message, e);
        }
        int deleted = 0;
        List<String> labels = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (OltOntDeleteResult result : results) {
            int count = clearOnts(id, result.getSlot(), result.getDeletedPorts());
            deleted += count;
            result.setOntTotal(count);
            if (!result.getDeletedPorts().isEmpty()) {
                labels.add("0/" + result.getSlot() + " 端口 " + join(result.getDeletedPorts())
                        + "（" + count + " 个 ONT）");
            }
            if (!result.getFailedPorts().isEmpty()) {
                failures.add("0/" + result.getSlot() + " 端口 " + join(result.getFailedPorts()));
            }
        }
        String label = "删除业务板 ONT：" + (labels.isEmpty() ? "没有可删的 ONT" : String.join("、", labels));
        if (!failures.isEmpty()) {
            label += "；设备拒绝了 " + String.join("、", failures);
        }
        saveRecord(id, OltSyncRecord.ACTION_DELETE, failures.isEmpty(), label,
                results.stream().flatMap(result -> result.getOutputs().stream())
                        .collect(Collectors.joining("\n")),
                System.currentTimeMillis() - start, operator);
        if (deleted == 0) {
            throw new IllegalStateException(failures.isEmpty()
                    ? "这块业务板上没有 ONT 可删" : "设备拒绝删除：" + String.join("、", failures));
        }
        return detail(id);
    }

    /** 把删成功的口上的 ONT 从库里清掉（不再为这个多问设备一次命令） */
    private int clearOnts(Long id, Integer slot, List<Integer> ports) {
        if (slot == null || ports == null || ports.isEmpty()) {
            return 0;
        }
        OltBoard board = boardMapper.selectOne(new LambdaQueryWrapper<OltBoard>()
                .eq(OltBoard::getOltId, id)
                .eq(OltBoard::getSlotNo, slot));
        if (board == null) {
            return 0;
        }
        fillPorts(board);
        int cleared = 0;
        if (board.getPorts() != null) {
            for (OltPort port : board.getPorts()) {
                if (port.getPortNo() == null || !ports.contains(port.getPortNo())) {
                    continue;
                }
                cleared += port.getOnuTotal() == null ? 0 : port.getOnuTotal();
                port.setOnuTotal(0);
                port.setOnuOnline(0);
                port.setOnts(List.of());
            }
        }
        boardMapper.update(null, new LambdaUpdateWrapper<OltBoard>()
                .eq(OltBoard::getId, board.getId())
                .set(OltBoard::getPortsJson, writePorts(board.getPorts())));
        return cleared;
    }

    /** 新增 TR-069 模板：下发 {@code onu-tr069-profile add ...}，成功后立刻重查一次返回新列表 */
    public List<OltTr069Profile> createTr069Profile(Long id, OltTr069ProfileRequest request, String operator) {
        OltDevice device = requireDevice(id);
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsTr069Profile()) {
            throw new IllegalArgumentException("这台设备还没接入 TR-069 模板命令");
        }
        checkTr069Request(request);
        List<String> commands = profile.createTr069ProfileCommands(request);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("没生成要下发的命令，模板未创建");
        }
        long start = System.currentTimeMillis();
        try {
            List<String> outputs = telnetService.sendConfig(device, commands);
            saveRecord(id, OltSyncRecord.ACTION_CONFIG, true,
                    "新增TR069模板 " + request.getProfileId() + "：" + String.join("；", commands),
                    String.join("\n", outputs), System.currentTimeMillis() - start, operator);
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, OltSyncRecord.ACTION_CONFIG, false,
                    "新增TR069模板失败 " + request.getProfileId() + "："
                            + String.join("；", commands) + "：" + message,
                    null, System.currentTimeMillis() - start, operator);
            throw new IllegalStateException(message, e);
        }
        return tr069Profiles(id);
    }

    /** 一键开局：把页面的几个勾选项编成一批命令一次下发，成功后重采 */
    public OltDetail quickSetup(Long id, OltQuickSetupRequest request, String operator) {
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsQuickSetup()) {
            throw new IllegalArgumentException("这台设备还没接入一键开局，先到设备上手动配");
        }
        if (request == null) {
            throw new IllegalArgumentException("请把开局选项填完整");
        }
        // 华为开局另有一段参数：要建的是 DBA / 线路 / 业务 / TR-069 四套模板，
        // 没有「改端口 VLAN 模式」这一步，下面的端口与 VLAN 校验对它不适用
        if (request.getHuawei() != null) {
            List<String> commands = profile.quickSetupCommands(request);
            if (commands.isEmpty()) {
                throw new IllegalArgumentException("没生成要下发的命令，开局未执行");
            }
            // 开局是幂等的「声明一遍」，设备上已有的 VLAN / 模板跳过就好
            return dispatchConfig(id, commands, operator, describeHuaweiQuickSetup(request.getHuawei()), true);
        }
        String mode = normalizeMode(request.getMode());
        if (request.getVlanList() != null && !request.getVlanList().isBlank()) {
            VlanIdExpression.expand(request.getVlanList());
        }
        // 页面上这一步没勾就一个端口都不动，端口清单只是残留数据
        List<OltQuickSetupRequest.PortRef> ports = Boolean.FALSE.equals(request.getPortModeEnabled())
                ? List.of()
                : checkQuickPorts(request.getPorts());
        int vlanCount = checkQuickVlanIds(request.getCreateVlanIds(), request.getAddCreatedToPorts());
        if (request.getTr069() != null) {
            checkTr069Request(request.getTr069());
        }
        if (ports.isEmpty() && vlanCount == 0 && request.getTr069() == null) {
            throw new IllegalArgumentException("至少勾选一项开局内容");
        }
        request.setMode(mode);
        List<String> commands = profile.quickSetupCommands(request);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("没生成要下发的命令，开局未执行");
        }
        return dispatchConfig(id, commands, operator, describeQuickSetup(request), true);
    }

    /** 一键开局预演：不下发，只把这次会执行的命令和要注意的地方算出来给页面看 */
    public OltQuickSetupPreview previewQuickSetup(Long id, OltQuickSetupRequest request) {
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsQuickSetup()) {
            throw new IllegalArgumentException("这台设备还没接入一键开局，先到设备上手动配");
        }
        if (request == null) {
            return new OltQuickSetupPreview(List.of(), List.of("先勾选要做的开局项"));
        }
        // 同上：华为这四套模板与端口模式无关，命令直接问模板编排器；
        // 参数不全时编排器抛错，页面预览区会把原因显示出来
        if (request.getHuawei() != null) {
            return new OltQuickSetupPreview(profile.quickSetupCommands(request), List.of());
        }
        String mode = normalizeMode(request.getMode());
        if (request.getVlanList() != null && !request.getVlanList().isBlank()) {
            // 写法不对会在这一行抛出去，页面直接把原因显示出来
            VlanIdExpression.expand(request.getVlanList());
        }
        boolean portStep = !Boolean.FALSE.equals(request.getPortModeEnabled());
        List<OltQuickSetupRequest.PortRef> ports = portStep ? checkQuickPorts(request.getPorts()) : List.of();
        int vlanCount = checkQuickVlanIds(request.getCreateVlanIds(), request.getAddCreatedToPorts());
        if (request.getTr069() != null) {
            checkTr069Request(request.getTr069());
        }
        request.setMode(mode);

        if (ports.isEmpty() && vlanCount == 0 && request.getTr069() == null) {
            return new OltQuickSetupPreview(List.of(), List.of("先勾选要做的开局项"));
        }
        List<String> warnings = new ArrayList<>();
        if (portStep && ports.isEmpty()) {
            warnings.add("至少勾选一个端口");
        }
        if (vlanCount > 0 && ports.isEmpty()) {
            // 建 VLAN 却没端口：建出来也没人用，还得再跑一次会话
            warnings.add("要建 VLAN 就得先把端口勾上");
        } else if (portStep && !ports.isEmpty()
                && (request.getVlanList() == null || request.getVlanList().isBlank())) {
            warnings.add("没填 VLAN，只会改端口模式");
        }
        return new OltQuickSetupPreview(profile.quickSetupCommands(request), warnings);
    }

    /** 一键开局表单：可选模式、VLAN 取值范围、一次最多几个端口、TR-069 默认填什么 */
    public OltQuickSetupForm quickSetupForm(Long id) {
        OltVendorProfile profile = profileOf(id);
        return new OltQuickSetupForm(profile.supportsQuickSetup(), QUICK_MODE_LIST, DEFAULT_QUICK_MODE,
                QUICK_PORT_TYPES, DEFAULT_QUICK_PORT_TYPES, MAX_QUICK_PORTS,
                1, VlanIdExpression.MAX_VLAN_ID, defaultTr069Request());
    }

    /** TR-069 模板表单：默认值 + 取值约束 */
    public OltTr069Form tr069Form(Long id) {
        return new OltTr069Form(profileOf(id).supportsTr069Profile(), defaultTr069Request(),
                TR069_PROFILE_ID_MIN, TR069_PROFILE_ID_MAX, TR069_PORT_MIN, TR069_PORT_MAX,
                TR069_NAME_MAX, TR069_URL_MAX, TR069_FIELD_MAX, TR069_URL_PATTERN);
    }

    /** TR-069 的现场默认值：模板 10 + ACS 10.172.10.1，开局时不用每次手填 */
    private static OltTr069ProfileRequest defaultTr069Request() {
        OltTr069ProfileRequest request = new OltTr069ProfileRequest();
        request.setProfileName("ACS_TR069");
        request.setProfileId(10);
        request.setUrl("http://10.172.10.1");
        request.setAcsUsername("acs");
        request.setAcsPassword("acs");
        request.setUsername("acs");
        request.setPassword("acs");
        request.setPort(9090);
        return request;
    }

    /** 命令动作预演：把进视图、命令本身、退出算出来，不下发 */
    public OltExecResult previewAction(Long id, OltExecRequest request) {
        String code = request == null ? null : request.getCode();
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("命令动作编号不能为空");
        }
        OltAction action = profileOf(id).actions().stream()
                .filter(item -> code.equals(item.code()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这台设备没有这个命令动作：" + code));
        Map<String, String> params = resolveParams(action, request.getParams());
        OltExecResult result = new OltExecResult();
        result.setCode(action.code());
        result.setLabel(action.label());
        result.setQuery(action.query());
        result.setCommands(planCommands(action, params));
        return result;
    }

    /** 批量预演：页面把整页表单（每个动作的当前参数）一次送上来，拿回每条的命令文本 */
    public Map<String, String> previewActions(Long id, List<OltExecRequest> requests) {
        Map<String, OltAction> catalog = new LinkedHashMap<>();
        for (OltAction action : profileOf(id).actions()) {
            catalog.put(action.code(), action);
        }
        Map<String, String> previews = new LinkedHashMap<>();
        if (requests == null) {
            return previews;
        }
        for (OltExecRequest request : requests) {
            if (request == null || request.getCode() == null || request.getCode().isBlank()) {
                continue;
            }
            OltAction action = catalog.get(request.getCode());
            if (action == null) {
                continue;
            }
            Map<String, String> params = request.getParams() == null ? Map.of() : request.getParams();
            List<String> plan = new ArrayList<>();
            String enter = enterCommandTemplate(action.view());
            if (enter != null) {
                plan.add(renderHint(enter, action, params));
            }
            for (String template : action.commands()) {
                plan.add(renderHint(template, action, params));
            }
            if (action.view() != OltAction.View.CONFIG) {
                plan.add("exit");
            }
            previews.put(action.code(), String.join("  →  ", plan));
        }
        return previews;
    }

    /** 预演用渲染：可选段里的参数没填就整段去掉（与执行侧一致），单个占位符没值就显示 */
    private static String renderHint(String template, OltAction action, Map<String, String> params) {
        Matcher optional = OPTIONAL_PART.matcher(template);
        StringBuilder kept = new StringBuilder();
        while (optional.find()) {
            String part = optional.group(1);
            optional.appendReplacement(kept,
                    Matcher.quoteReplacement(hasAllParams(part, params) ? part : ""));
        }
        optional.appendTail(kept);
        Matcher matcher = PLACEHOLDER.matcher(kept.toString());
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = params.get(key);
            String shown = value == null || value.isBlank() ? "<" + paramLabel(action, key) + ">" : value;
            matcher.appendReplacement(text, Matcher.quoteReplacement(shown));
        }
        matcher.appendTail(text);
        return text.toString();
    }

    /** 占位符对应的中文参数名；动作里没定义的（如视图参数）就用键名本身 */
    private static String paramLabel(OltAction action, String key) {
        if (action.params() != null) {
            for (OltActionParam param : action.params()) {
                if (key.equals(param.key())) {
                    return param.label();
                }
            }
        }
        return key;
    }

    /** 设备台账表单的备选项：厂商码表、采集字符集、默认连接端口和默认账号 */
    public static OltDeviceOptions deviceOptions() {
        List<OltOption> vendors = new ArrayList<>();
        for (OltVendor vendor : OltVendor.values()) {
            vendors.add(new OltOption(vendor.name(), vendor.getLabel()));
        }
        return new OltDeviceOptions(vendors, List.of("AUTO", "UTF-8", "GBK", "GB18030"),
                DEFAULT_SSH_PORT, DEFAULT_TELNET_PORT, "root", "admin",
                OltBoardCatalog.profiles(), VIEW_LABELS, ONU_ACTIONS, VLAN_TYPE_LABELS,
                BOARD_CATEGORY_LABELS, DEFAULT_SLOT_COUNT, OltBoardCatalog.ENV_TEMP_ALARM_C,
                codeTables());
    }

    private static String describeQuickSetup(OltQuickSetupRequest request) {
        List<String> parts = new ArrayList<>();
        if (!request.getPorts().isEmpty()) {
            parts.add(request.getPorts().size() + " 个端口 → " + request.getMode());
        }
        if (request.getCreateVlanIds() != null && !request.getCreateVlanIds().isBlank()) {
            parts.add("建 VLAN " + request.getCreateVlanIds());
        }
        if (request.getTr069() != null) {
            parts.add("建 TR069 模板 " + request.getTr069().getProfileId());
        }
        return "一键开局（" + String.join("；", parts) + "）";
    }

    /** 华为开局的记录摘要：模板 ID 就是带宽，写清楚这次建的是哪一套 */
    private static String describeHuaweiQuickSetup(HuaweiQuickSetupRequest request) {
        Integer bandwidth = request.getDbaBandwidthMbps();
        if (bandwidth == null) {
            return "华为一键开局（模板 ID 未定）";
        }
        return "华为一键开局（DBA/线路/业务/TR069 模板 " + bandwidth + "，" + bandwidth + "M）";
    }

    private static String normalizeMode(String mode) {
        String text = mode == null || mode.isBlank() ? "trunk" : mode.trim().toLowerCase(Locale.ROOT);
        if (!QUICK_MODES.contains(text)) {
            throw new IllegalArgumentException("端口 VLAN 模式只能是 access / hybrid / trunk");
        }
        return text;
    }

    /** 一次性改多少个端口的上限：每个端口 4 条命令，再多一轮会话出结果会很慢 */
    private static final int MAX_QUICK_PORTS = 32;

    /** 端口 VLAN 模式，顺序即页面下拉里的顺序 */
    private static final List<String> QUICK_MODE_LIST = List.of("access", "hybrid", "trunk");

    /** 开局默认模式：上联口跑业务 VLAN 基本都是 trunk */
    private static final String DEFAULT_QUICK_MODE = "trunk";



    private static final Set<String> QUICK_MODES = Set.copyOf(QUICK_MODE_LIST);

    /** 开局能选的端口类型 */
    private static final List<String> QUICK_PORT_TYPES = List.of("epon", "gpon", "ge", "xge");

    /** 默认只勾 PON 口和 GE 上联口：10GE 通常跑堆叠/上联，不该跟着开局一起改 */
    private static final List<String> DEFAULT_QUICK_PORT_TYPES = List.of("epon", "gpon", "ge");

    // TR-069 模板的取值约束：校验和表单元数据共用同一份，改一处两边一起变
    private static final int TR069_PROFILE_ID_MIN = 1;

    private static final int TR069_PROFILE_ID_MAX = 32;

    private static final int TR069_NAME_MAX = 32;

    private static final int TR069_URL_MAX = 64;

    private static final int TR069_FIELD_MAX = 24;

    private static final int TR069_PORT_MIN = 1;

    private static final int TR069_PORT_MAX = 65535;

    /** ACS 地址的写法要求，和 checkTr069Request 里的校验是同一条规则 */
    private static final String TR069_URL_PATTERN = "(?i)^(https?|tr069)://\\S+$";

    // DBA 模板的取值约束：校验、命令、表单元数据共用同一份
    private static final int DBA_PROFILE_ID_MIN = 10;

    private static final int DBA_PROFILE_ID_MAX = 512;

    private static final int DBA_NAME_MAX = 32;

    private static final String DBA_NAME_PATTERN = "[A-Za-z0-9_]{1,32}";

    private static final int DBA_BANDWIDTH_MIN = 128;

    private static final int DBA_BANDWIDTH_MAX = 10000000;

    /** 现场基本都用「最大带宽」，默认选它 */
    private static final String DEFAULT_DBA_TYPE = "type4";

    /** 五类 DBA 模板各要填哪几个带宽字段（手册 5.1） */
    private static final List<OltDbaType> DBA_TYPES = List.of(
            new OltDbaType("type1", "type1 - 固定带宽", List.of("fix")),
            new OltDbaType("type2", "type2 - 保证带宽", List.of("assure")),
            new OltDbaType("type3", "type3 - 保证带宽+最大带宽", List.of("assure", "max")),
            new OltDbaType("type4", "type4 - 最大带宽（默认）", List.of("max")),
            new OltDbaType("type5", "type5 - 固定+保证+最大带宽", List.of("fix", "assure", "max")));

    private static final Pattern PORT_TYPE = Pattern.compile("(?i)^(epon|gpon|ge|xge)$");

    private static List<OltQuickSetupRequest.PortRef> checkQuickPorts(List<OltQuickSetupRequest.PortRef> ports) {
        if (ports == null || ports.isEmpty()) {
            return List.of();
        }
        if (ports.size() > MAX_QUICK_PORTS) {
            throw new IllegalArgumentException("一次最多改 " + MAX_QUICK_PORTS + " 个端口，请分几次开局");
        }
        for (OltQuickSetupRequest.PortRef port : ports) {
            String type = port == null || port.getType() == null ? "" : port.getType().trim().toLowerCase(Locale.ROOT);
            if (!PORT_TYPE.matcher(type).matches()) {
                throw new IllegalArgumentException("端口类型只认 epon / gpon / ge / xge：" + type);
            }
            Integer no = port == null ? null : port.getNo();
            if (no == null || no < 1 || no > 64) {
                throw new IllegalArgumentException("端口号必须在 1-64 之间：" + no);
            }
            port.setType(type);
        }
        return ports;
    }

    /** 校验要新建的 VLAN，返回一共要建多少个（带端口时逐个进视图，数量得收着点） */
    private static int checkQuickVlanIds(String ids, Boolean addPorts) {
        if (ids == null || ids.isBlank()) {
            return 0;
        }
        List<Integer> vlanIds = VlanIdExpression.expand(ids);
        int limit = Boolean.TRUE.equals(addPorts) ? MAX_VLANS_WITH_PORTS : MAX_VLANS_BATCH;
        if (vlanIds.size() > limit) {
            throw new IllegalArgumentException(Boolean.TRUE.equals(addPorts)
                    ? "带上成员端口时一次最多建 " + MAX_VLANS_WITH_PORTS + " 个 VLAN"
                    : "一次最多建 " + MAX_VLANS_BATCH + " 个 VLAN");
        }
        return vlanIds.size();
    }
    private void checkTr069Request(OltTr069ProfileRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请填写模板参数");
        }
        Integer profileId = request.getProfileId();
        if (profileId == null || profileId < TR069_PROFILE_ID_MIN || profileId > TR069_PROFILE_ID_MAX) {
            throw new IllegalArgumentException("模板 ID 必须在 "
                    + TR069_PROFILE_ID_MIN + "-" + TR069_PROFILE_ID_MAX + " 之间");
        }
        String name = trimToNull(request.getProfileName());
        if (name == null || name.length() > TR069_NAME_MAX) {
            throw new IllegalArgumentException("模板名不能为空，最长 " + TR069_NAME_MAX + " 个字符");
        }
        if (hasBlank(name)) {
            throw new IllegalArgumentException("模板名不能包含空格");
        }
        String url = trimToNull(request.getUrl());
        if (url == null || url.length() > TR069_URL_MAX) {
            throw new IllegalArgumentException("ACS 服务器 URL 不能为空，最长 " + TR069_URL_MAX + " 个字符");
        }
        if (!url.matches(TR069_URL_PATTERN)) {
            throw new IllegalArgumentException("ACS 服务器 URL 要以 http:// 或 https:// 开头，例如 http://192.168.1.10:9090");
        }
        checkOption(request.getAcsUsername(), TR069_FIELD_MAX, "ACS 服务器用户名");
        checkOption(request.getAcsPassword(), TR069_FIELD_MAX, "ACS 服务器密码");
        checkOption(request.getUsername(), TR069_FIELD_MAX, "连接用户名");
        checkOption(request.getPassword(), TR069_FIELD_MAX, "连接密码");
        Integer port = request.getPort();
        if (port == null || port < TR069_PORT_MIN || port > TR069_PORT_MAX) {
            throw new IllegalArgumentException("连接端口必须在 "
                    + TR069_PORT_MIN + "-" + TR069_PORT_MAX + " 之间");
        }
        if (Boolean.TRUE.equals(request.getInformEnable())) {
            Integer interval = request.getInformInterval();
            if (interval == null || interval < 1 || interval > 86400) {
                throw new IllegalArgumentException("定期通知间隔必须在 1-86400 秒之间");
            }
        }
    }

    /** 可空字段：要么不填，要么不超过长度上限、不带空格 */
    private void checkOption(String value, int maxLength, String label) {
        String text = trimToNull(value);
        if (text == null) {
            return;
        }
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(label + "最长 " + maxLength + " 个字符");
        }
        if (hasBlank(text)) {
            throw new IllegalArgumentException(label + "不能包含空格");
        }
    }

    private static boolean hasBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** 一次建多少个 VLAN：不带端口时一条命令建一段；带端口就得逐个 VLAN 进视图，得收着点 */
    private static final int MAX_VLANS_BATCH = 64;

    /** 带端口一次最多几个 VLAN（每个 3 条命令，再多一轮会话发不完） */
    private static final int MAX_VLANS_WITH_PORTS = 16;

    /** 命令动作视图的中文名（页面把 view 翻成这句） */
    private static final Map<String, String> VIEW_LABELS = Map.ofEntries(
            Map.entry("CONFIG", "全局配置视图"),
            Map.entry("VLAN", "VLAN 视图"),
            Map.entry("PORT", "端口视图"),
            Map.entry("PON", "EPON 口视图"),
            Map.entry("GPON", "GPON 口视图"),
            Map.entry("LINE_PROFILE", "线路模板视图"),
            Map.entry("SRV_PROFILE", "业务模板视图"),
            Map.entry("ONU", "ONU 视图"),
            Map.entry("MANAGE", "管理接口视图"),
            Map.entry("ACL", "ACL 视图"),
            Map.entry("MCAST", "组播 VLAN 视图"),
            Map.entry("VLANIF", "三层接口视图"));

    /** ONU / ONT 上能做的操作（「配置」下拉里的那几项） */
    private static final List<OltOption> ONU_ACTIONS = List.of(
            new OltOption("detail", "查看详情"),
            new OltOption("mac", "查看MAC地址信息"),
            new OltOption("port", "ONU端口配置"),
            new OltOption("delete", "删除ONU"),
            new OltOption("block", "添加到阻止列表"),
            new OltOption("reboot", "重启ONU"),
            new OltOption("optical", "关闭发光射机"),
            new OltOption("unregister", "解注册ONU"),
            new OltOption("config", "配置ONU信息"));

    /** VLAN 类型的中文名，和网管「单层智能 VLAN：41、45-46」的写法一致 */
    private static final Map<String, String> VLAN_TYPE_LABELS = Map.ofEntries(
            Map.entry("smart", "单层智能 VLAN (Smart VLAN)"),
            Map.entry("mux", "汇聚 VLAN (Mux VLAN)"),
            Map.entry("standard", "标准 VLAN (Standard VLAN)"),
            Map.entry("super", "超级 VLAN (Super VLAN)"),
            Map.entry("connect", "二层互通 VLAN"));

    /** 板卡类别的中文名 */
    private static final Map<String, String> BOARD_CATEGORY_LABELS = Map.ofEntries(
            Map.entry("SERVICE", "业务板"),
            Map.entry("CONTROL", "主控板"),
            Map.entry("UPLINK", "上联板"),
            Map.entry("POWER", "电源板"),
            Map.entry("INTERFACE", "通用接口板"),
            Map.entry("UNKNOWN", "未知"));

    /** ONT 回显英文键 → 中文（键统一小写：设备回显大小写不稳定，页面查表前先小写化 */
    private static final Map<String, String> ONT_KEY_LABELS = Map.ofEntries(
            Map.entry("index", "索引编号"),
            Map.entry("auth-type", "认证方式"),
            Map.entry("auth type", "认证方式"),
            Map.entry("sn", "序列号"),
            Map.entry("type", "ONT 类型"),
            Map.entry("uptime", "上线时间"),
            Map.entry("downtime", "下线时间"),
            Map.entry("downreason", "下线原因"),
            Map.entry("onu nni port id", "ONU网络侧端口编号"),
            Map.entry("module type", "光模块类型"),
            Map.entry("module sub-type", "光模块子类型"),
            Map.entry("used type", "使用场景"),
            Map.entry("encapsulation type", "封装类型"),
            Map.entry("optical power precision(dbm)", "光功率精度(dBm)"),
            Map.entry("vendor name", "生产厂商"),
            Map.entry("vendor rev", "厂商版本"),
            Map.entry("vendor pn", "厂商产品编号"),
            Map.entry("vendor sn", "厂商产品序列号"),
            Map.entry("vendor code", "厂商代码"),
            Map.entry("date code", "出厂日期"),
            Map.entry("rx optical power(dbm)", "接收功率(dBm)"),
            Map.entry("rx power current warning threshold(dbm)", "接收功率警示门限值(dBm)"),
            Map.entry("rx power current alarm threshold(dbm)", "接收功率告警门限值(dBm)"),
            Map.entry("tx optical power(dbm)", "发送功率(dBm)"),
            Map.entry("tx power warning threshold(dbm)", "发送功率警示门限值(dBm)"),
            Map.entry("tx power alarm threshold(dbm)", "发送功率告警门限值(dBm)"),
            Map.entry("laser bias current(ma)", "偏置电流(mA)"),
            Map.entry("laser bias current warning threshold(ma)", "偏置电流警示门限值(mA)"),
            Map.entry("laser bias current alarm threshold(ma)", "偏置电流告警门限值(mA)"),
            Map.entry("temperature(c)", "温度(℃)"),
            Map.entry("temperature warning threshold(c)", "温度警示门限值(℃)"),
            Map.entry("temperature alarm threshold(c)", "温度告警门限值(℃)"),
            Map.entry("voltage(v)", "电压(V)"),
            Map.entry("supply voltage warning threshold(v)", "电压警示门限值(V)"),
            Map.entry("supply voltage alarm threshold(v)", "电压告警门限值(V)"),
            Map.entry("olt rx ont optical power(dbm)", "OLT接收ONT光功率(dBm)"),
            Map.entry("catv rx optical power(dbm)", "CATV接收光功率(dBm)"),
            Map.entry("catv rx power alarm threshold(dbm)", "CATV接收功率告警门限值(dBm)"),
            Map.entry("f/s/p", "框/槽/端口"),
            Map.entry("ont-id", "ONT编号"),
            Map.entry("equipment id", "设备ID号"),
            Map.entry("main software version", "主用软件版本"),
            Map.entry("backup software version", "备用软件版本"),
            Map.entry("ont product id", "产品编号"),
            Map.entry("ont description", "ONT的描述信息"),
            Map.entry("ont id", "ONT编号"),
            Map.entry("number of uplink pon ports", "上行PON口数目"),
            Map.entry("number of pots ports", "POTS端口数目"),
            Map.entry("number of eth ports", "ETH端口数目"),
            Map.entry("number of vdsl ports", "VDSL端口数目"),
            Map.entry("number of tdm ports", "TDM端口数目"),
            Map.entry("number of moca ports", "MOCA端口数目"),
            Map.entry("number of catv ani ports", "CATV ANI端口数目"),
            Map.entry("number of catv uni ports", "CATV UNI端口数目"),
            Map.entry("number of gem ports", "GEM port数目"),
            Map.entry("ip configuration", "是否支持IP配置"),
            Map.entry("number of traffic schedulers", "流量控制器数目"),
            Map.entry("number of t-conts", "TCONT数目"),
            Map.entry("the type of flow control", "流控类型"),
            Map.entry("ont optical module power control capability", "ONT光模块电源控制能力"),
            Map.entry("ont type", "ONT类型"),
            Map.entry("extended omci message format", "是否支持可变长OMCI消息格式"),
            Map.entry("voip configuration method", "语音业务配置方式"),
            Map.entry("voip signalling protocol", "语音业务协议类型"),
            Map.entry("ethoam", "ETHOAM"),
            Map.entry("single ring check", "环网单次检测"),
            Map.entry("multicast encrypt", "组播加密"));

    /** display ont capability 的取值 → 中文 */
    private static final Map<String, String> ONT_CAP_VALUES = Map.ofEntries(
            Map.entry("support", "支持"),
            Map.entry("not support", "不支持"),
            Map.entry("gemport car and pq", "GEMPORT CAR和优先级队列"),
            Map.entry("the tx and rx power can be controlled separately", "支持TX和RX独立控制"));

    /** 认证方式取值 → 中文 */
    private static final Map<String, String> ONT_AUTH_VALUES = Map.ofEntries(
            Map.entry("sn-auth", "SN认证"),
            Map.entry("sn", "SN认证"),
            Map.entry("mac-auth", "MAC认证"),
            Map.entry("mac", "MAC认证"),
            Map.entry("password-auth", "密码认证"));

    /** 运行 / 配置 / 匹配状态取值 → 中文 */
    private static final Map<String, String> ONT_INFO_VALUES = Map.ofEntries(
            Map.entry("online", "在线"),
            Map.entry("offline", "离线"),
            Map.entry("normal", "正常"),
            Map.entry("initial", "初始状态"),
            Map.entry("failed", "失败"),
            Map.entry("match", "匹配"),
            Map.entry("mismatch", "不匹配"));

    /** 业务流 VLAN 属性 → 中文（display service-port 的 VLAN ATTR 列） */
    private static final Map<String, String> VLAN_ATTR_LABELS = Map.ofEntries(
            Map.entry("common", "通用VLAN"),
            Map.entry("translation", "转换VLAN"),
            Map.entry("qinq", "QinQ"),
            Map.entry("smart", "智能VLAN"));

    /** 最后下线原因 → 中文解释 */
    private static final Map<String, String> ONT_DOWN_REASONS = Map.ofEntries(
            Map.entry("LOSi/LOBi", "LOSi/LOBi【光质量差/光纤断】"),
            Map.entry("LOSi", "LOSi【光信号丢失】"),
            Map.entry("LOFi", "LOFi【OLT无法学到ONi序列号】"),
            Map.entry("SuFi", "SuFi【ONi上行流量异常】"),
            Map.entry("dying-gasp", "掉电（Dying-Gasp）"));

    /** 注册方式码 → 列表里显示的短名 */
    private static final Map<String, String> AUTH_MODE_LABELS = Map.ofEntries(
            Map.entry("SN", "SN"),
            Map.entry("MAC", "MAC"),
            Map.entry("LOID", "LOID"),
            Map.entry("PASSWORD", "Password"));

    /** 注册方式单选项（光猫上送什么就按什么认） */
    private static final List<OltOption> AUTH_MODES = List.of(
            new OltOption("SN", "SN（设备序列号）"),
            new OltOption("MAC", "MAC"),
            new OltOption("LOID", "LOID（逻辑ID）"),
            new OltOption("PASSWORD", "Password（密码）"));

    /** 光猫工作模式单选项 */
    private static final List<OltOption> WORK_MODES = List.of(
            new OltOption("HGU", "HGU/网关型"),
            new OltOption("SFU", "SFU/桥接型（不带路由功能）"),
            new OltOption("MIX", "HGU + SFU 兼容/混合型"),
            new OltOption("MDU", "ONU/外置型光猫（MDU）"));

    /** PON 类型单选项：两种体系的注册命令与模板 ID 空间不通用 */
    private static final List<OltOption> PON_TYPES = List.of(
            new OltOption("GPON", "GPON/XGPON"),
            new OltOption("EPON", "EPON/10G EPON"));

    /** 端口类型 → 中文名 */
    private static final Map<String, String> PORT_TITLES = Map.ofEntries(
            Map.entry("epon", "PON 口"),
            Map.entry("gpon", "PON 口"),
            Map.entry("ge", "GE 以太网口"),
            Map.entry("xge", "10GE 以太网口"));

    /** 字符集 → 显示名 */
    private static final Map<String, String> CHARSET_LABELS = Map.of("AUTO", "自动");

    /** 详情页页签 → 采集段：点页签时只采该页签的数据段 */
    private static final Map<String, String> TAB_SECTIONS = Map.ofEntries(
            Map.entry("spec", "boards"),
            Map.entry("config", "boards"),
            Map.entry("vlan", "vlan"),
            Map.entry("dba", "template"),
            Map.entry("service", "template"),
            Map.entry("ont", "ont"));

    /** 码表打包（随 /olt/options 下发，页面只查表不维护表） */
    private static OltCodeTables codeTables() {
        return new OltCodeTables(ONT_KEY_LABELS, ONT_CAP_VALUES, ONT_AUTH_VALUES, ONT_INFO_VALUES,
                VLAN_ATTR_LABELS, ONT_DOWN_REASONS, AUTH_MODE_LABELS, AUTH_MODES, WORK_MODES,
                PON_TYPES, PORT_TITLES, CHARSET_LABELS, TAB_SECTIONS);
    }

    /** 机框槽位数的兜底：设备没回机框槽位数时按这个画 */
    private static final int DEFAULT_SLOT_COUNT = 12;

    /** 页面分组展示端口的顺序（设备侧没规定顺序，只是页面看起来顺） */
    private static final List<String> VLAN_PORT_TYPE_ORDER = List.of("epon", "gpon", "ge", "xge");

    /** 同一个端口不能既 tagged 又 untagged：这台设备只认一种带标签方式 */
    private static final boolean VLAN_PORT_EXCLUSIVE_TAG_MODE = true;

    /** 页面传上来的端口写法：{@code epon1} / {@code ge3} / {@code xge2} */
    private static final Pattern VLAN_PORT_REF = Pattern.compile(
            "(?i)^(epon|gpon|ge|xge)\\s*(\\d{1,3})$");

    /** 上行口写法：{@code 0/7/0}（机框/板卡/端口），命令里要拆成「框/槽」+「端口」两段 */
    private static final Pattern PORT_IFACE = Pattern.compile("^\\d{1,2}/\\d{1,2}/\\d{1,3}$");

    /** 一次给一个上行口加/删的 VLAN 上限：再多就拼出一条几百字符的命令，设备也会顶回来 */
    private static final int MAX_PORT_VLAN_BATCH = 64;

    /** 校验页面勾的端口：写法要对、类型要是这台设备能加进 VLAN 的 */
    private List<String> normalizeVlanPorts(OltVendorProfile profile, List<String> ports) {
        if (ports == null || ports.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = profile.vlanMemberPortTypes();
        List<String> normalized = new ArrayList<>();
        for (String port : ports) {
            String text = port == null ? "" : port.trim().toLowerCase(Locale.ROOT);
            Matcher matcher = VLAN_PORT_REF.matcher(text);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("端口写法不对：" + port);
            }
            String type = matcher.group(1);
            int no = Integer.parseInt(matcher.group(2));
            if (!allowed.contains(type)) {
                throw new IllegalArgumentException(OltVlanPort.label(type, no)
                        + " 不能加进 VLAN：这台设备的 PON 口不做二层成员端口，业务走 service-port 绑 GEM Port");
            }
            normalized.add(type + no);
        }
        return normalized;
    }

    /** 页面上填的 VLAN：优先用表达式（100-200,300），老页面只给起止 ID 时也认 */
    private List<int[]> vlanSegments(OltVlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请填写 VLAN ID，例如 100 或 100-200,300");
        }
        if (request.getVlanIds() != null && !request.getVlanIds().isBlank()) {
            return VlanIdExpression.segments(request.getVlanIds());
        }
        Integer start = request.getVlanId();
        checkVlanId(start, "起始 VLAN ID");
        int last = request.getEndVlanId() == null ? start : request.getEndVlanId();
        checkVlanId(last, "结束 VLAN ID");
        if (last < start) {
            throw new IllegalArgumentException("结束 VLAN ID 不能小于起始 VLAN ID");
        }
        return List.of(new int[]{start, last});
    }

    /** 连续段 → 给人看的写法：[[101,110],[300,300]] → 101-110,300 */
    private static String describeVlans(List<int[]> segments) {
        return segments.stream()
                .map(segment -> segment[0] == segment[1]
                        ? String.valueOf(segment[0])
                        : segment[0] + "-" + segment[1])
                .collect(Collectors.joining(","));
    }

    /** 删除勾选的 VLAN：逐条（连续段合并）下发 {@code no vlan}，成功后自动重采 */
    public OltDetail deleteVlans(Long id, List<Integer> vlanIds, String operator) {
        if (vlanIds == null || vlanIds.isEmpty()) {
            throw new IllegalArgumentException("先勾选要删除的 VLAN");
        }
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsVlanConfig()) {
            throw new IllegalArgumentException("这台设备的厂商还没接入 VLAN 下发，先到设备上配好再同步");
        }
        List<Integer> ids = vlanIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("先勾选要删除的 VLAN");
        }
        ids.forEach(vlanId -> checkVlanId(vlanId, "VLAN ID"));
        List<String> commands = profile.deleteVlanCommands(ids);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("没生成要下发的命令，VLAN 未变更");
        }
        return dispatchConfig(id, commands, operator, "删除VLAN " + join(ids));
    }

    /** 上行口放通 VLAN：下发 {@code port vlan 41,45-46 0/7 0}（Native 时尾巴带 native） */
    public OltDetail addPortVlan(Long id, OltPortVlanRequest request, String operator) {
        String iface = portVlanIface(request);
        List<Integer> vlanIds = portVlanIds(request);
        boolean nativeVlan = Boolean.TRUE.equals(request.getNativeVlan());
        List<String> commands = profileOf(id).addPortVlanCommands(iface, vlanIds, nativeVlan);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("这台设备的厂商还没接入上行口 VLAN 下发，先到设备上配好再同步");
        }
        return dispatchPortVlan(id, commands, operator,
                "上行口 " + iface + (nativeVlan ? " 加 Native VLAN " : " 放通 VLAN ") + join(vlanIds));
    }

    /** 上行口解除 VLAN 绑定：下发 {@code no port vlan 41,45-46 0/7 0}，成功后同样立刻重采 VLAN */
    public OltDetail removePortVlan(Long id, OltPortVlanRequest request, String operator) {
        String iface = portVlanIface(request);
        List<Integer> vlanIds = portVlanIds(request);
        List<String> commands = profileOf(id).removePortVlanCommands(iface, vlanIds);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("这台设备的厂商还没接入上行口 VLAN 下发，先到设备上配好再同步");
        }
        return dispatchPortVlan(id, commands, operator,
                "上行口 " + iface + " 移除 VLAN " + join(vlanIds));
    }

    /** 上行口 VLAN 下发的公共流程：下发 → 写采集记录 → 只重采 VLAN → 返回最新详情 */
    private OltDetail dispatchPortVlan(Long id, List<String> commands, String operator, String label) {
        OltDevice device = requireDevice(id);
        long start = System.currentTimeMillis();
        String actionType = recordTypeOf(label);
        try {
            List<String> outputs = telnetService.sendConfig(device, commands, true);
            saveRecord(id, actionType, true,
                    label + "：" + String.join("；", commands), String.join("\n", outputs),
                    System.currentTimeMillis() - start, operator);
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, actionType, false,
                    label + "失败：" + String.join("；", commands) + "：" + message, null,
                    System.currentTimeMillis() - start, operator);
            throw new IllegalStateException(message, e);
        }
        return refreshVlansOnly(id, operator, label);
    }

    /** 只重采 VLAN 并覆盖库里的 VLAN 快照，返回最新详情 */
    private OltDetail refreshVlansOnly(Long id, String operator, String label) {
        OltDevice device = requireDevice(id);
        List<OltBoard> boards = boardMapper.selectList(new LambdaQueryWrapper<OltBoard>()
                .eq(OltBoard::getOltId, id)
                .orderByAsc(OltBoard::getSlotNo));
        boards.forEach(this::fillPorts);
        long start = System.currentTimeMillis();
        OltVlanInfo vlan;
        try {
            vlan = telnetService.collectVlans(device, boards);
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, false,
                    label + "已下发，但重读 VLAN 失败：" + message, null,
                    System.currentTimeMillis() - start, operator);
            // 命令已经生效，这里报错只是「没能立刻读到新数据」，别让人以为配置没成功
            throw new IllegalStateException("配置已下发，但重新读取 VLAN 失败：" + message + "，请点上面的「同步」再试一次", e);
        }
        if (vlan != null) {
            deviceMapper.update(null, new LambdaUpdateWrapper<OltDevice>()
                    .eq(OltDevice::getId, id)
                    .set(OltDevice::getVlanJson, writeVlan(vlan)));
        }
        saveRecord(id, OltSyncRecord.ACTION_SYNC, true,
                label + "后重读 VLAN：" + (vlan == null ? "未采到"
                        : vlan.getVlans().size() + " 个 VLAN / " + vlan.getPortVlans().size() + " 个上行口已采放通"),
                null, System.currentTimeMillis() - start, operator);
        return detail(id);
    }

    /** 上行口的写法校验：{@code 0/7/0}，一次可以给多个口（逗号分隔 */
    private static String portVlanIface(OltPortVlanRequest request) {
        String iface = request == null || request.getIface() == null ? "" : request.getIface().trim();
        List<String> ifaces = new ArrayList<>();
        for (String piece : iface.split("[,，;；\\s]+")) {
            if (piece.isBlank()) {
                continue;
            }
            if (!PORT_IFACE.matcher(piece).matches()) {
                throw new IllegalArgumentException("端口写法不对：" + piece
                        + "，应该像 0/7/0（机框/板卡/端口），多个口用逗号分隔");
            }
            if (!ifaces.contains(piece)) {
                ifaces.add(piece);
            }
        }
        if (ifaces.isEmpty()) {
            throw new IllegalArgumentException("端口写法不对：（空），应该像 0/7/0（机框/板卡/端口）");
        }
        return String.join(",", ifaces);
    }

    /** 表单里的 VLAN：{@code 41,45-46} → [41,45,46]；一次最多 64 个，免得拼出一条超长命令 */
    private static List<Integer> portVlanIds(OltPortVlanRequest request) {
        String text = request == null ? null : request.getVlanIds();
        List<Integer> ids = VlanIdExpression.expand(text);
        if (ids.size() > MAX_PORT_VLAN_BATCH) {
            throw new IllegalArgumentException("一次最多 " + MAX_PORT_VLAN_BATCH + " 个 VLAN，分几次加");
        }
        return ids;
    }

    /** 这台设备的厂商 profile（VLAN 这类命令写法按厂商走） */
    private OltVendorProfile profileOf(Long id) {
        return OltVendorProfiles.of(OltVendor.of(requireDevice(id).getVendor()));
    }

    /** 这台设备支持的命令动作目录（页面「设备配置」页签按章节渲染） */
    public List<OltAction> actions(Long id) {
        // 顺手把「进视图 / 退视图」也拼进展示用的命令序列：
        // 视图语义是设备知识，不该由页面在渲染时自己补 exit
        return profileOf(id).actions().stream().map(this::withPreviewCommands).toList();
    }

    private OltAction withPreviewCommands(OltAction action) {
        List<String> preview = new ArrayList<>();
        String enter = enterCommandTemplate(action.view());
        if (enter != null) {
            preview.add(enter);
        }
        preview.addAll(action.commands());
        if (action.view() != OltAction.View.CONFIG) {
            preview.add("exit");
        }
        return action.withPreviewCommands(preview);
    }

    /** 进视图的命令模板，占位符留着让页面按填的值替换 */
    static String enterCommandTemplate(OltAction.View view) {
        return switch (view) {
            case VLAN -> "vlan standard {vlanId}";
            case PORT -> "interface {portType} {portNo}";
            case PON -> "interface epon {ponId}";
            case GPON -> "interface gpon {ponId}";
            case LINE_PROFILE -> "ont-lineprofile gpon profile-id {profileId}";
            case SRV_PROFILE -> "ont-srvprofile gpon profile-id {profileId}";
            case ONU -> "interface onu {ponId}/{onuId}";
            case MANAGE -> "interface manage";
            case ACL -> "acl {aclId}";
            case MCAST -> "multicast-vlan {vlanId}";
            case VLANIF -> "interface vlanif {vlanId}";
            case CONFIG -> null;
        };
    }

    /** 执行一条命令动作：校验参数 → 按视图编排命令 → 下发 */
    public OltExecResult execAction(Long id, OltExecRequest request, String operator) {
        OltDevice device = requireDevice(id);
        String code = request == null ? null : request.getCode();
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("命令动作编号不能为空");
        }
        OltAction action = profileOf(id).actions().stream()
                .filter(item -> code.equals(item.code()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这台设备没有这个命令动作：" + code));
        Map<String, String> params = resolveParams(action, request.getParams());
        List<String> plan = planCommands(action, params);

        OltExecResult result = new OltExecResult();
        result.setCode(action.code());
        result.setLabel(action.label());
        result.setQuery(action.query());
        result.setCommands(plan);
        long start = System.currentTimeMillis();
        // 记录按动作性质分类：只读查询记「查询」，删除类记「删除」，其余记「配置」
        String actionType = recordTypeOf(action);
        try {
            List<String> outputs = telnetService.sendConfig(device, plan);
            result.setOutputs(outputs);
            saveRecord(id, actionType, true,
                    action.label() + "：" + String.join("；", plan),
                    String.join("\n", outputs), System.currentTimeMillis() - start, operator);
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, actionType, false,
                    action.label() + "失败：" + String.join("；", plan) + "：" + message, null,
                    System.currentTimeMillis() - start, operator);
            throw new IllegalStateException(message, e);
        }
        // 配置类才重采：查看 CPU 这种命令不该顺带把整机重采一遍
        if (!action.query()) {
            result.setDetail(collectAndPersist(id, operator, action.label() + "后重采"));
        }
        return result;
    }

    /** 按操作文案判断记录类型：删除 / 擦除 / 移除 都算「删除」，其余算「配置」 */
    private static String recordTypeOf(String label) {
        return label != null && (label.contains("删除") || label.contains("擦除") || label.contains("移除"))
                ? OltSyncRecord.ACTION_DELETE : OltSyncRecord.ACTION_CONFIG;
    }

    /** 一条命令动作该记成哪种记录：只读查询 → 查询，删除类 → 删除，其余 → 配置 */
    private static String recordTypeOf(OltAction action) {
        if (action.query()) {
            return OltSyncRecord.ACTION_QUERY;
        }
        String code = action.code() == null ? "" : action.code().toLowerCase(Locale.ROOT);
        String label = action.label() == null ? "" : action.label();
        boolean delete = code.contains("delete") || code.endsWith(".del") || code.contains(".del.")
                || label.contains("删除") || label.contains("擦除");
        return delete ? OltSyncRecord.ACTION_DELETE : OltSyncRecord.ACTION_CONFIG;
    }

    /** 端口 / VLAN 列表的写法：1,3,4-8 */
    private static final Pattern PORT_LIST = Pattern.compile("^\\d{1,4}([,-]\\d{1,4})*$");

    /** 文本参数：中英文、数字和常见符号，允许空格（端口描述、用户描述要能写中文） */
    private static final Pattern TEXT_VALUE = Pattern.compile("^[\\p{L}\\p{N}._:/@#()\\[\\] -]{1,64}$");

    /** 命令模板里的占位符 {key} */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /** 按参数定义校验表单，缺省值补上，返回渲染用的键值表（包内可见，便于单测） */
    static Map<String, String> resolveParams(OltAction action, Map<String, String> input) {
        Map<String, String> values = new LinkedHashMap<>();
        for (OltActionParam param : action.params()) {
            String raw = input == null ? null : input.get(param.key());
            if (raw == null || raw.isBlank()) {
                raw = param.defaultValue();
            }
            if (raw == null || raw.isBlank()) {
                if (param.required()) {
                    throw new IllegalArgumentException("请填写「" + param.label() + "」");
                }
                continue;
            }
            values.put(param.key(), checkParam(param, raw.trim()));
        }
        // 视图切换要用的参数（VLAN / 端口视图）必须齐，否则进不去视图
        switch (action.view()) {
            case VLAN, VLANIF -> requireParam(values, "vlanId", "VLAN ID");
            case PORT -> {
                requireParam(values, "portType", "端口类型");
                requireParam(values, "portNo", "端口号");
            }
            case PON, GPON -> requireParam(values, "ponId", "PON 口号");
            case LINE_PROFILE, SRV_PROFILE -> requireParam(values, "profileId", "模板 ID");
            case ONU -> {
                requireParam(values, "ponId", "PON 口号");
                requireParam(values, "onuId", "ONU ID");
            }
            case ACL -> requireParam(values, "aclId", "ACL ID");
            case MCAST -> requireParam(values, "vlanId", "VLAN ID");
            default -> {
            }
        }
        return values;
    }

    static String checkParam(OltActionParam param, String value) {
        switch (param.type()) {
            case "number" -> {
                int number;
                try {
                    number = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("「" + param.label() + "」必须是数字");
                }
                if ((param.min() != null && number < param.min())
                        || (param.max() != null && number > param.max())) {
                    throw new IllegalArgumentException("「" + param.label() + "」要在 "
                            + param.min() + "-" + param.max() + " 之间");
                }
                return String.valueOf(number);
            }
            case "ports" -> {
                if (!PORT_LIST.matcher(value).matches()) {
                    throw new IllegalArgumentException("「" + param.label()
                            + "」格式不对，用 1,3,4-8 这种写法");
                }
                return value;
            }
            case "enum" -> {
                if (param.options() == null || !param.options().contains(value)) {
                    throw new IllegalArgumentException("「" + param.label() + "」只能是 " + param.options());
                }
                return value;
            }
            default -> {
                if (!TEXT_VALUE.matcher(value).matches()) {
                    throw new IllegalArgumentException("「" + param.label()
                            + "」只能填字母、数字和 . : / _ -");
                }
                return value;
            }
        }
    }

    private static String requireParam(Map<String, String> values, String key, String label) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请填写「" + label + "」");
        }
        return value;
    }

    /** 把动作翻成真正下发的命令：先进视图，再逐条渲染，最后 exit 回全局配置视图（包内可见，便于单测） */
    static List<String> planCommands(OltAction action, Map<String, String> params) {
        List<String> plan = new ArrayList<>();
        String enter = enterCommandTemplate(action.view());
        if (enter != null) {
            // 进视图要用的参数缺了会在这里抛出去，提示和原来逐条 requireParam 一致
            requireViewParams(action.view(), params);
            plan.add(renderCommand(enter, params));
        }
        for (String template : action.commands()) {
            plan.add(renderCommand(template, params));
        }
        if (action.view() != OltAction.View.CONFIG) {
            plan.add("exit");
        }
        return plan;
    }

    /** 进视图要用到的参数必须先齐，否则设备根本进不去那个视图 */
    private static void requireViewParams(OltAction.View view, Map<String, String> params) {
        switch (view) {
            case VLAN, VLANIF, MCAST -> requireParam(params, "vlanId", "VLAN ID");
            case PORT -> {
                requireParam(params, "portType", "端口类型");
                requireParam(params, "portNo", "端口号");
            }
            case PON, GPON -> requireParam(params, "ponId", "PON 口号");
            case LINE_PROFILE, SRV_PROFILE -> requireParam(params, "profileId", "模板 ID");
            case ONU -> {
                requireParam(params, "ponId", "PON 口号");
                requireParam(params, "onuId", "ONU ID");
            }
            case ACL -> requireParam(params, "aclId", "ACL ID");
            default -> {
            }
        }
    }

    /** 命令模板里的可选段：[ backup-server-ip {backupIp}] —— 段内参数没填就整段去掉 */
    private static final Pattern OPTIONAL_PART = Pattern.compile("\\[([^]]*)]");

    private static String renderCommand(String template, Map<String, String> params) {
        // 先处理可选段：段里的占位符都有值就保留（去掉方括号），否则整段删掉
        Matcher optional = OPTIONAL_PART.matcher(template);
        StringBuilder kept = new StringBuilder();
        while (optional.find()) {
            String part = optional.group(1);
            optional.appendReplacement(kept,
                    Matcher.quoteReplacement(hasAllParams(part, params) ? part : ""));
        }
        optional.appendTail(kept);
        String text = kept.toString();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = params.get(key);
            if (value == null) {
                throw new IllegalArgumentException("命令缺少参数：" + key);
            }
            text = text.replace("{" + key + "}", value);
        }
        return text;
    }

    /** 可选段里的占位符是不是全都有值；段里压根没有占位符的按保留处理 */
    private static boolean hasAllParams(String part, Map<String, String> params) {
        Matcher matcher = PLACEHOLDER.matcher(part);
        while (matcher.find()) {
            if (params.get(matcher.group(1)) == null) {
                return false;
            }
        }
        return true;
    }

    private static void checkVlanId(Integer vlanId, String label) {
        if (vlanId == null || vlanId < 1 || vlanId > 4094) {
            throw new IllegalArgumentException(label + " 必须在 1-4094 之间");
        }
    }

    private static String join(List<Integer> ids) {
        StringBuilder text = new StringBuilder();
        for (Integer id : ids) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(id);
        }
        return text.toString();
    }

    /** 下发配置的公共流程：连设备执行 → 写采集记录（带回显）→ 自动重采刷新库和页面 */
    private OltDetail dispatchConfig(Long id, List<String> commands, String operator, String label) {
        return dispatchConfig(id, commands, operator, label, false);
    }

    /** 一键开局要打开：开局是把整套配置声明一遍，设备上只要已有 VLAN 46 */
    private OltDetail dispatchConfig(Long id, List<String> commands, String operator, String label,
                                     boolean tolerateExisting) {
        OltDevice device = requireDevice(id);
        long start = System.currentTimeMillis();
        // 删除类的下发别记成「配置」：记录表上要一眼分清是改配置还是在删东西
        String actionType = recordTypeOf(label);
        try {
            List<String> outputs = telnetService.sendConfig(device, commands, tolerateExisting);
            String transcript = String.join("\n", outputs);
            saveRecord(id, actionType, true,
                    label + "：" + String.join("；", commands), transcript,
                    System.currentTimeMillis() - start, operator);
            // 采集到的最新数据既写库又直接返回，页面不用再拉一次
            return collectAndPersist(id, operator, label + "后重采");
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, actionType, false,
                    label + "失败：" + String.join("；", commands) + "：" + message, null,
                    System.currentTimeMillis() - start, operator);
            throw new IllegalStateException(message, e);
        }
    }

    private Integer positive(Integer value, String label) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw new IllegalArgumentException(label + "必须是正整数（单位 kbps）");
        }
        return value;
    }

    private void requireNotNull(Integer value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private OltDevice requireDevice(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("OLT id 不能为空");
        }
        OltDevice device = deviceMapper.selectById(id);
        if (device == null) {
            throw new IllegalArgumentException("OLT 不存在或已删除");
        }
        return device;
    }

    private void ensureNameAvailable(String name, Long excludeId) {
        Long count = deviceMapper.selectCount(new LambdaQueryWrapper<OltDevice>()
                .eq(OltDevice::getName, name)
                .ne(excludeId != null, OltDevice::getId, excludeId));
        if (count != null && count > 0) {
            throw new IllegalArgumentException("名称已存在：" + name);
        }
    }

    /** 组装「试连」用的参数：请求里填了就用请求的，没填就用库里已保存的 */
    private OltDevice buildProbe(OltSaveRequest request, OltDevice stored) {
        OltDevice probe = new OltDevice();
        if (stored != null) {
            probe.setId(stored.getId());
            probe.setName(stored.getName());
            probe.setHost(stored.getHost());
            probe.setProtocol(stored.getProtocol());
            probe.setUsername(stored.getUsername());
            probe.setPassword(stored.getPassword());
            probe.setPort(stored.getPort());
            probe.setCharset(stored.getCharset());
            probe.setBindLocalIp(stored.getBindLocalIp());
        }
        if (request == null) {
            return probe;
        }
        if (trimToNull(request.getHost()) != null) {
            probe.setHost(trimToNull(request.getHost()));
        }
        if (trimToNull(request.getProtocol()) != null) {
            probe.setProtocol(PROTOCOL_SSH.equalsIgnoreCase(request.getProtocol()) ? PROTOCOL_SSH : PROTOCOL_TELNET);
        }
        if (trimToNull(request.getUsername()) != null) {
            probe.setUsername(trimToNull(request.getUsername()));
        }
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            probe.setPassword(request.getPassword());
        }
        if (request.getPort() != null) {
            probe.setPort(request.getPort());
        }
        if (trimToNull(request.getCharset()) != null) {
            probe.setCharset(normalizeCharset(request.getCharset()));
        }
        if (trimToNull(request.getBindLocalIp()) != null) {
            probe.setBindLocalIp(trimToNull(request.getBindLocalIp()));
        }
        if (probe.getCharset() == null) {
            probe.setCharset("AUTO");
        }
        return probe;
    }

    private void updateAfterConnect(Long id, OltSnapshot snapshot, boolean sync, String summary) {
        LambdaUpdateWrapper<OltDevice> update = new LambdaUpdateWrapper<OltDevice>()
                .eq(OltDevice::getId, id)
                .set(OltDevice::getStatus, STATUS_ONLINE)
                .set(OltDevice::getLastConnectAt, LocalDateTime.now())
                .set(OltDevice::getLastError, null);
        // 只有真认出厂商才改台账：一次没采到版本回显就把已认出的厂商打回「通用」，
        // 页面会当场从华为/鸿升光界面掉到第三方界面。认不出的那次保持原值等下次再认
        if (snapshot.getVendor() != null && snapshot.getVendor() != OltVendor.GENERIC) {
            update.set(OltDevice::getVendor, snapshot.getVendor().name());
        }
        if (snapshot.getModel() != null) {
            update.set(OltDevice::getModel, snapshot.getModel());
        }
        if (snapshot.getSoftwareVersion() != null) {
            update.set(OltDevice::getSoftwareVersion, snapshot.getSoftwareVersion());
        }
        if (snapshot.getPatchVersion() != null) {
            update.set(OltDevice::getPatchVersion, snapshot.getPatchVersion());
        }
        if (snapshot.getBackplane() != null) {
            update.set(OltDevice::getBackplane, snapshot.getBackplane());
        }
        // 机框槽位数（display board 0 槽位表）：采到就更新，没采到保留上次的
        if (snapshot.getSlotCount() != null) {
            update.set(OltDevice::getSlotCount, snapshot.getSlotCount());
        }
        if (snapshot.getMacAddress() != null) {
            update.set(OltDevice::getMacAddress, snapshot.getMacAddress());
        }
        if (snapshot.getUptime() != null) {
            update.set(OltDevice::getUptime, snapshot.getUptime());
        }
        if (sync) {
            update.set(OltDevice::getLastSyncAt, LocalDateTime.now());
            // sync_summary 是 VARCHAR(255)，诊断信息拼进去后必须截断，否则整条更新报错
            update.set(OltDevice::getSyncSummary, cut(summary, 250));
            // VLAN 快照整体覆盖；这次没采到（命令不支持）就保留上一次的，不要清空
            if (snapshot.getVlanInfo() != null) {
                update.set(OltDevice::getVlanJson, writeVlan(snapshot.getVlanInfo()));
            }
            // DBA / 线路模板快照同 VLAN 一个套路：采到了就整体覆盖，没采到保留上一次
            if (snapshot.getTemplateInfo() != null) {
                update.set(OltDevice::getTemplateJson, writeTemplate(snapshot.getTemplateInfo()));
            }
        }
        deviceMapper.update(null, update);
    }

    private void markOffline(Long id, String message) {
        deviceMapper.update(null, new LambdaUpdateWrapper<OltDevice>()
                .eq(OltDevice::getId, id)
                .set(OltDevice::getStatus, STATUS_OFFLINE)
                .set(OltDevice::getLastError, cut(message, 480)));
    }

    private void saveRecord(Long oltId, String action, boolean success, String message,
                            String rawOutput, long costMs, String operator) {
        try {
            OltSyncRecord record = new OltSyncRecord();
            record.setOltId(oltId);
            record.setAction(action);
            record.setSuccess(success);
            record.setMessage(cut(message, 1000));
            record.setRawOutput(telnetService.truncateRaw(rawOutput));
            record.setCostMs((int) Math.min(costMs, Integer.MAX_VALUE));
            record.setOperator(cut(operator, 64));
            recordMapper.insert(record);
        } catch (Exception e) {
            // 记录失败不影响主流程
            log.warn("OLT 采集记录写入失败: {}", e.getMessage());
        }
    }

    /** 板卡类别统计，拼在同步摘要里：业务板 1 / 主控板 1 / 上联板 1 */
    private String kindSummary(List<OltBoard> boards) {
        if (boards == null || boards.isEmpty()) {
            return "";
        }
        Map<String, Long> counts = boards.stream()
                .filter(board -> board.getBoardKind() != null)
                .collect(Collectors.groupingBy(OltBoard::getBoardKind, LinkedHashMap::new, Collectors.counting()));
        if (counts.isEmpty()) {
            return "";
        }
        String detail = counts.entrySet().stream()
                .map(entry -> boardKindLabel(entry.getKey()) + " " + entry.getValue())
                .collect(Collectors.joining(" / "));
        return "（" + detail + "）";
    }

    private String boardKindLabel(String code) {
        try {
            return OltInfoParser.BoardKind.valueOf(code).getLabel();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    /** 端口光模块状态按 JSON 存板卡行上：端口是随板卡整体刷新的快照，单独建表反而多一次联表 */
    private String writePorts(List<OltPort> ports) {
        if (ports == null || ports.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ports);
        } catch (JsonProcessingException e) {
            log.warn("端口状态序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private void fillPorts(OltBoard board) {
        String json = board.getPortsJson();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            List<OltPort> ports = objectMapper.readValue(json, new TypeReference<List<OltPort>>() {
            });
            ports.forEach(OltService::fillPortLampState);
            board.setPorts(ports);
        } catch (JsonProcessingException e) {
            log.warn("端口状态解析失败 slot={}: {}", board.getSlotNo(), e.getMessage());
        }
    }

    /** 端口指示灯三态（alarm / online / offline），判定口径原来在页面的 portLampState 里 */
    private static void fillPortLampState(OltPort port) {
        String optical = port.getOpticStatus() == null ? "" : port.getOpticStatus().toLowerCase(Locale.ROOT);
        if (optical.matches(".*(abnormal|mismatch|fault).*")) {
            port.setLampState("alarm");
            return;
        }
        int total = port.getOnuTotal() == null ? 0 : port.getOnuTotal();
        int online = port.getOnuOnline() == null ? 0 : port.getOnuOnline();
        if (total == 0 && port.getOnts() != null && !port.getOnts().isEmpty()) {
            total = port.getOnts().size();
            online = (int) port.getOnts().stream()
                    .filter(ont -> Boolean.TRUE.equals(ont.getOnline()))
                    .count();
        }
        if (total > 0 && online == 0) {
            port.setLampState("alarm");
            return;
        }
        String link = port.getLinkStatus() == null ? "" : port.getLinkStatus().toLowerCase(Locale.ROOT);
        if (online > 0 || "online".equals(link)) {
            port.setLampState("online");
            return;
        }
        port.setLampState("offline");
    }

    /** detail 组装的最后一步：把页面原来自己算的派生数据统一在后端算好—— */
    private void enrichDetail(OltDetail detail, List<OltBoard> boards, OltVlanInfo vlan) {
        Map<String, String> vlanTextByIface = new HashMap<>();
        if (vlan != null && vlan.getPortVlans() != null) {
            for (OltPortVlan portVlan : vlan.getPortVlans()) {
                if (portVlan == null || portVlan.getIface() == null) {
                    continue;
                }
                vlanTextByIface.put(portVlan.getIface(), compressVlans(portVlan.getVlans()));
            }
        }
        int ontTotal = 0;
        int ontOnline = 0;
        Map<String, Integer> kindCounts = new LinkedHashMap<>();
        String wholeSummary = null;
        for (OltBoard board : boards) {
            board.setLampState(boardLampState(board.getStatusLevel()));
            // ALM 灯：状态等级是 danger / warning 就点红，其余灭——判定只在后端做一次
            board.setAlarmLamp("danger".equals(board.getStatusLevel()) || "warning".equals(board.getStatusLevel())
                    ? "alarm" : "offline");
            kindCounts.merge(board.getBoardKind() == null ? "UNKNOWN" : board.getBoardKind(), 1, Integer::sum);
            if (wholeSummary == null && board.getFrontPanel() != null) {
                // 整机（鸿升光）没有板卡可言，汇总直接报型号规格
                String summary = board.getFrontPanel().getSummary();
                wholeSummary = summary != null && !summary.isBlank() ? summary : board.getDescription();
            }
            List<OltPort> ports = completePorts(board);
            int boardTotal = 0;
            int boardOnline = 0;
            int known = 0;
            for (OltPort port : ports) {
                enrichPort(port, board, vlanTextByIface);
                if (port.getOnuTotal() != null) {
                    boardTotal += port.getOnuTotal();
                    boardOnline += port.getOnuOnline() == null ? 0 : port.getOnuOnline();
                    known++;
                }
            }
            board.setOnuSummary(known > 0 ? boardTotal + " ONT / " + boardOnline + " 在线" : "");
            board.setOnuOnline(boardOnline);
            board.setSubtitle(boardSubtitle(board, ports.size()));
            // ACT 灯看板上有没有在线 ONT；ENV 灯看温度有没有到告警阈值（阈值在型号目录里）
            board.setActLamp(boardOnline > 0 ? "online" : "offline");
            board.setEnvLamp(board.getTemperature() != null
                    && board.getTemperature() >= OltBoardCatalog.ENV_TEMP_ALARM_C ? "alarm" : "offline");
            if (!ports.isEmpty()) {
                ontTotal += boardTotal;
                ontOnline += boardOnline;
            }
        }
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("total", ontTotal);
        summary.put("online", ontOnline);
        summary.put("offline", Math.max(0, ontTotal - ontOnline));
        detail.setOntSummary(summary);
        detail.setBoardSummary(boardSummary(boards, kindCounts, wholeSummary));
        detail.setVlanGroups(vlanGroups(vlan));
        enrichTemplate(detail.getTemplate());
        // 「删除业务板 ONU」用：哪几块板有 ONT、各多少条，后端算好页面直接用
        List<Map<String, Object>> deletable = new ArrayList<>();
        int deletableTotal = 0;
        for (OltBoard board : boards) {
            if (board.getSlotNo() == null || board.getPorts() == null || board.getPorts().isEmpty()) {
                continue;
            }
            int total = board.getPorts().stream()
                    .mapToInt(port -> port.getOnuTotal() == null ? 0 : port.getOnuTotal()).sum();
            if (total <= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slotNo", board.getSlotNo());
            row.put("total", total);
            // 「0/2（12 个）」：确认框里的板卡表示法由后端拼，页面不再拼接口号
            row.put("text", "0/" + board.getSlotNo() + "（" + total + " 个）");
            deletable.add(row);
            deletableTotal += total;
        }
        deletable.sort(Comparator.comparing(row -> (Integer) row.get("slotNo")));
        detail.setDeletableBoards(deletable);
        detail.setDeletableOntTotal(deletableTotal);
        fillOntRows(detail);
        // 机框槽位数：设备没回就按板卡最大槽位 + 1，再不行用码表兜底值
        if (detail.getDevice() != null
                && (detail.getDevice().getSlotCount() == null || detail.getDevice().getSlotCount() <= 0)) {
            int max = boards.stream().map(OltBoard::getSlotNo).filter(Objects::nonNull)
                    .max(Integer::compareTo).orElse(-1);
            detail.getDevice().setSlotCount(Math.max(DEFAULT_SLOT_COUNT, max + 1));
        }
        // 以上修正之后再取，页面拿到什么就画几个槽位
        detail.setChassisSlotCount(detail.getDevice() == null ? null : detail.getDevice().getSlotCount());
        // 停在 auto-find 的板卡：插上了但没确认，业务不加载，页面据此挂「确认所有业务板」
        detail.setBoardsNeedConfirm(boards.stream()
                .anyMatch(board -> board.getStatus() != null
                        && AUTO_FIND_STATUS.matcher(board.getStatus()).matches()));
        // ONT 页汇总句：业务板块数 + 端口数，只数有端口的板卡（主控、上联板没有 PON 口）
        List<OltBoard> ontBoards = boards.stream()
                .filter(board -> board.getPorts() != null && !board.getPorts().isEmpty())
                .toList();
        detail.setOntBoardCount(ontBoards.size());
        detail.setOntPortTotal(ontBoards.stream().mapToInt(board -> board.getPorts().size()).sum());
        markPortVlanText(detail.getVlan());
    }

    /** 板卡状态里的 auto-find（可能写成 auto find / auto_find），只有这一处判 */
    private static final Pattern AUTO_FIND_STATUS = Pattern.compile("(?i).*auto[_ ]?find.*");

    /** 端口 VLAN 压缩文本：{@code [1,41,45,46] → "1、41、45-46"} */
    private static void markPortVlanText(OltVlanInfo vlan) {
        if (vlan == null || vlan.getPortVlans() == null) {
            return;
        }
        for (OltPortVlan portVlan : vlan.getPortVlans()) {
            if (portVlan != null) {
                portVlan.setVlanText(compressVlans(portVlan.getVlans()));
            }
        }
    }

    /** ONT 摊平：板卡 × 端口 × ONT 一条一行，顺带给出筛选用的端口下拉 */
    private static void fillOntRows(OltDetail detail) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> portOptions = new ArrayList<>();
        // 「按板卡分表展开端口」视图：板卡 → 端口 → ONT，端口上的光功率/在线数都在这里算
        List<Map<String, Object>> cards = new ArrayList<>();
        String vendor = detail.getDevice() == null ? "" : String.valueOf(detail.getDevice().getVendor());
        // GPON 用 SN 认证、EPON 用 MAC 认证
        String authMethod = vendor.toUpperCase(Locale.ROOT).contains("GPON") ? "sn" : "mac";
        for (OltBoard board : detail.getBoards()) {
            if (board.getSlotNo() == null || board.getPorts() == null || board.getPorts().isEmpty()) {
                continue;
            }
            List<Map<String, Object>> cardPorts = new ArrayList<>();
            for (OltPort port : board.getPorts()) {
                List<OltOnt> onts = port.getOnts();
                cardPorts.add(ontCardPort(port, onts == null ? List.of() : onts));
                if (onts == null || onts.isEmpty()) {
                    continue;
                }
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("value", board.getSlotNo() + "/" + port.getPortNo());
                option.put("label", "槽位 " + board.getSlotNo() + " - " + port.getIface());
                portOptions.add(option);
                for (OltOnt ont : onts) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", board.getSlotNo() + "/" + port.getPortNo() + "/" + ont.getOntId());
                    row.put("slotNo", board.getSlotNo());
                    row.put("portNo", port.getPortNo());
                    row.put("portName", port.getPortName() == null ? "" : port.getPortName());
                    row.put("iface", port.getIface());
                    row.put("ontId", ont.getOntId());
                    // 平铺列表按「PON 口/ONU ID」显示，与状态面板共用同一份行
                    row.put("ponNo", port.getPortNo());
                    row.put("name", ont.getName() == null ? "" : ont.getName());
                    row.put("sn", ont.getSn());
                    row.put("desc", ont.getDescription());
                    row.put("online", ont.getOnline());
                    row.put("authorized", ont.getAuthorized());
                    row.put("registerTime", ont.getRegisterTime() == null ? "" : ont.getRegisterTime());
                    row.put("lastOfflineTime", ont.getLastOfflineTime() == null ? "" : ont.getLastOfflineTime());
                    row.put("offlineReason", ont.getOfflineReason() == null ? "" : ont.getOfflineReason());
                    row.put("deviceType", ont.getDeviceType() == null ? "" : ont.getDeviceType());
                    row.put("onuType", ont.getOnuType() == null ? "" : ont.getOnuType());
                    row.put("distance", ont.getDistance());
                    row.put("rxPower", ont.getRxPower() == null ? "" : ont.getRxPower());
                    row.put("stateText", ont.getOnline() == null ? "-"
                            : (ont.getOnline() ? "在线" : "离线"));
                    row.put("configText", ontConfigText(ont.getConfigState()));
                    // 同步时随单台详情采回的字段；老快照没有就是空，重新同步即可
                    row.put("mgmtIp", ont.getMgmtIp() == null ? "" : ont.getMgmtIp());
                    row.put("mac", ont.getMac() == null ? "" : ont.getMac());
                    row.put("lineProfileId", ont.getLineProfileId());
                    row.put("lineProfileName", ont.getLineProfileName() == null ? "" : ont.getLineProfileName());
                    row.put("srvProfileId", ont.getSrvProfileId());
                    row.put("srvProfileName", ont.getSrvProfileName() == null ? "" : ont.getSrvProfileName());
                    row.put("authMethod", authMethod);
                    rows.add(row);
                }
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("no", board.getSlotNo());
            card.put("type", board.getBoardType() == null ? "-" : board.getBoardType());
            card.put("onuSummary", board.getOnuSummary() == null ? "" : board.getOnuSummary());
            card.put("ports", cardPorts);
            cards.add(card);
        }
        detail.setOntRows(rows);
        detail.setOntPortOptions(portOptions);
        detail.setOntCards(cards);
    }

    /** 「按板卡分表」视图里的一行端口：在线数、离线数、光功率与温度由后端算好 */
    private static Map<String, Object> ontCardPort(OltPort port, List<OltOnt> onts) {
        int total = port.getOnuTotal() == null ? onts.size() : port.getOnuTotal();
        int online = port.getOnuOnline() == null
                ? (int) onts.stream().filter(ont -> Boolean.TRUE.equals(ont.getOnline())).count()
                : port.getOnuOnline();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("no", port.getPortNo());
        row.put("name", port.getPortName() == null ? "" : port.getPortName());
        row.put("iface", port.getIface());
        row.put("onuTotal", total);
        row.put("onuOnline", online);
        row.put("offline", Math.max(0, total - online));
        row.put("txPower", port.getTxPower() == null ? "" : port.getTxPower());
        row.put("rxPower", port.getRxPower() == null ? "" : port.getRxPower());
        row.put("hasOptic", Boolean.TRUE.equals(port.getOpticalPresent()));
        row.put("opticTemperature", port.getOpticTemperature() == null ? "" : port.getOpticTemperature());
        List<Map<String, Object>> ontRows = new ArrayList<>();
        for (OltOnt ont : onts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ont.getOntId());
            item.put("sn", ont.getSn());
            item.put("desc", ont.getDescription());
            item.put("online", ont.getOnline());
            item.put("stateText", ont.getOnline() == null ? "-" : (ont.getOnline() ? "在线" : "离线"));
            ontRows.add(item);
        }
        row.put("onts", ontRows);
        return row;
    }

    /** 配置状态中文：normal / initial / failed，认不出的原样显示 */
    private static String ontConfigText(String configState) {
        if (configState == null || configState.isBlank()) {
            return "";
        }
        return switch (configState.trim().toLowerCase(Locale.ROOT)) {
            case "normal" -> "正常";
            case "initial" -> "初始";
            case "failed" -> "失败";
            default -> configState;
        };
    }

    /** 单端口派生字段：接口号、放通 VLAN 文本、ONT 汇总文本、链路中文、光模块灯与提示 */
    private static void enrichPort(OltPort port, OltBoard board, Map<String, String> vlanTextByIface) {
        int frameNo = port.getFrameNo() == null ? 0 : port.getFrameNo();
        Integer slotNo = port.getSlotNo() == null ? board.getSlotNo() : port.getSlotNo();
        String iface = slotNo == null ? "" : frameNo + "/" + slotNo + "/" + port.getPortNo();
        port.setIface(iface);
        port.setVlanText(iface.isEmpty() ? "" : vlanTextByIface.getOrDefault(iface, ""));
        port.setLabelText(portLabelText(port, board));
        port.setSubText(portSubText(iface, port.getVlanText()));
        port.setOpticText(portOpticText(port));
        int total = port.getOnuTotal() == null ? 0 : port.getOnuTotal();
        int online = port.getOnuOnline() == null ? 0 : port.getOnuOnline();
        if (total == 0 && port.getOnts() != null && !port.getOnts().isEmpty()) {
            total = port.getOnts().size();
            online = (int) port.getOnts().stream()
                    .filter(ont -> Boolean.TRUE.equals(ont.getOnline())).count();
        }
        port.setOnuText(port.getOnuTotal() != null || total > 0 ? total + " ONT / " + online + " 在线" : "");
        // 面板标注：PON 口看 ONT 在线数，上行口看链路；悬停提示把这些串成一行
        boolean ponPanel = "pon".equalsIgnoreCase(board.getPanelKind());
        port.setPanelText(portPanelText(port, ponPanel, total, online));
        port.setTooltipText(portTooltipText(port));
        // 每台 ONT 的状态中文也在这里补齐：页面原来自己判 online 布尔、再翻 configState 码表
        if (port.getOnts() != null) {
            for (OltOnt ont : port.getOnts()) {
                ont.setStateText(ont.getOnline() == null ? "未知" : (ont.getOnline() ? "在线" : "离线"));
                ont.setConfigText(ontConfigText(ont.getConfigState()));
            }
        }
        String link = port.getLinkStatus() == null ? "" : port.getLinkStatus().toLowerCase(Locale.ROOT);
        port.setLinkText("online".equals(link) ? "在线" : "offline".equals(link) ? "离线" : "");
        String optical = port.getOpticStatus() == null ? "" : port.getOpticStatus().toLowerCase(Locale.ROOT);
        if (port.getOpticalPresent() == null) {
            port.setOpticLamp("unknown");
            port.setOpticLampText("光模块状态未采集");
        } else if (Boolean.TRUE.equals(port.getOpticalPresent())) {
            port.setOpticLamp("on");
            port.setOpticLampText("光模块在位");
        } else {
            port.setOpticLamp("off");
            port.setOpticLampText("光模块不在位");
        }
        if (optical.matches(".*(abnormal|mismatch|fault).*")) {
            port.setOpticLamp("warn");
            port.setOpticLampText("光模块异常（" + port.getOpticStatus() + "）");
        }
    }

    /** 板卡丝印副标题：「GPON 8口 主控」——口类型 + 口数 + 是否主控 */
    private static String boardSubtitle(OltBoard board, int portCount) {
        List<String> parts = new ArrayList<>();
        if (board.getPortMode() != null && !board.getPortMode().isBlank()) {
            parts.add(board.getPortMode().trim());
        }
        if (portCount > 0) {
            parts.add(portCount + "口");
        }
        if (Boolean.TRUE.equals(board.getControlBoard())) {
            parts.add("主控");
        }
        return String.join(" ", parts);
    }

    /** 面板口名：PON 板写「PON 2」，上行/以太口按速率前缀写「10GE 1」，认不出速率写「PORT 1」 */
    private static String portLabelText(OltPort port, OltBoard board) {
        String no = port.getPortNo() == null ? "-" : String.valueOf(port.getPortNo());
        if ("pon".equalsIgnoreCase(board.getPanelKind())) {
            return "PON " + no;
        }
        String mode = board.getPortMode() == null ? "" : board.getPortMode().trim().toUpperCase(Locale.ROOT);
        String prefix = mode.contains("10GE") || mode.contains("XGE") ? "10GE" : (mode.isEmpty() ? "PORT" : mode);
        return prefix + " " + no;
    }

    /** 面板标注第二行：PON 口报「22 / 23 ONT 在线」（或「无 ONT」「ONT 未采集」） */
    private static String portPanelText(OltPort port, boolean ponPanel, int total, int online) {
        if (!ponPanel) {
            String link = port.getLinkText();
            return "链路 " + (link == null || link.isBlank() ? "未采集" : link);
        }
        if (port.getOnuText() == null || port.getOnuText().isBlank()) {
            return "ONT 未采集";
        }
        if (total <= 0) {
            return "无 ONT";
        }
        return online + " / " + total + " ONT 在线";
    }

    /** 悬停提示：口名 · 接口号 · 灯态 · ONT 汇总 · VLAN · 光模块，空的段直接跳过 */
    private static String portTooltipText(OltPort port) {
        List<String> parts = new ArrayList<>();
        addText(parts, port.getLabelText());
        addText(parts, port.getIface());
        addText(parts, portLampText(port.getLampState()));
        addText(parts, port.getOnuText() == null || port.getOnuText().isBlank() ? "ONT 未采集" : port.getOnuText());
        if (port.getVlanText() != null && !port.getVlanText().isBlank()) {
            parts.add("VLAN " + port.getVlanText());
        }
        addText(parts, port.getOpticText());
        return String.join(" · ", parts);
    }

    /** 端口灯态中文：alarm 告警、online 在线、其余离线 */
    private static String portLampText(String lampState) {
        if ("online".equals(lampState)) {
            return "在线";
        }
        if ("alarm".equals(lampState)) {
            return "告警";
        }
        return lampState == null ? "" : "离线";
    }

    private static void addText(List<String> parts, String text) {
        if (text != null && !text.isBlank()) {
            parts.add(text);
        }
    }

    /** 面板标注第三行：接口号与放通 VLAN，两样都没有时给一个「—」占位 */
    private static String portSubText(String iface, String vlanText) {
        List<String> parts = new ArrayList<>();
        if (iface != null && !iface.isBlank()) {
            parts.add(iface);
        }
        if (vlanText != null && !vlanText.isBlank()) {
            parts.add("VLAN " + vlanText);
        }
        return parts.isEmpty() ? "—" : String.join(" · ", parts);
    }

    /** 光模块数值一行：发光 / 收光 / 温度 / 偏置 / 电压，按现场读数的顺序拼 */
    private static String portOpticText(OltPort port) {
        List<String> parts = new ArrayList<>();
        addPart(parts, "发光 ", port.getTxPower());
        addPart(parts, "收光 ", port.getRxPower());
        addPart(parts, "温度 ", port.getOpticTemperature());
        addPart(parts, "偏置 ", port.getBiasCurrent());
        addPart(parts, "电压 ", port.getSupplyVoltage());
        if (!parts.isEmpty()) {
            return String.join(" · ", parts);
        }
        return Boolean.TRUE.equals(port.getOpticalPresent()) ? "光模块数值未采集" : "光模块未采集";
    }

    private static void addPart(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(label + value);
        }
    }

    /** 端口补齐到板卡的 portCount：回显只列配了 ONT 的口，钉行模式要求每个物理口都有一行 */
    private static List<OltPort> completePorts(OltBoard board) {
        List<OltPort> ports = board.getPorts() == null ? new ArrayList<>() : board.getPorts();
        Integer total = board.getPortCount();
        if (total == null || total <= 0 || ports.size() >= total) {
            board.setPorts(ports);
            return ports;
        }
        Set<Integer> have = ports.stream().map(OltPort::getPortNo).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Integer frameNo = ports.isEmpty() || ports.get(0).getFrameNo() == null ? 0 : ports.get(0).getFrameNo();
        List<OltPort> padded = new ArrayList<>(ports);
        for (int no = 0; no < total; no++) {
            if (have.contains(no)) {
                continue;
            }
            OltPort port = new OltPort();
            port.setFrameNo(frameNo);
            port.setSlotNo(board.getSlotNo());
            port.setPortNo(no);
            port.setPortName("P" + no);
            port.setLampState("offline");
            padded.add(port);
        }
        padded.sort(Comparator.comparing(port -> port.getPortNo() == null ? 0 : port.getPortNo()));
        board.setPorts(padded);
        return padded;
    }

    /** 板卡状态灯：成功亮绿，异常/告警亮红，其余灰（页面不再拿 statusLevel 自己换算） */
    private static String boardLampState(String statusLevel) {
        if ("success".equals(statusLevel)) {
            return "online";
        }
        if ("danger".equals(statusLevel) || "warning".equals(statusLevel)) {
            return "alarm";
        }
        return "offline";
    }

    /** 模板快照的派生数据：T-CONT 引用的 DBA 模板名、TR-069 线路模板标记、 */
    private static void enrichTemplate(OltTemplateInfo template) {
        if (template == null || template.getLineProfiles() == null) {
            return;
        }
        Map<Integer, OltDbaProfile> dbaMap = new HashMap<>();
        if (template.getDbaProfiles() != null) {
            for (OltDbaProfile dba : template.getDbaProfiles()) {
                if (dba.getProfileId() != null) {
                    dbaMap.put(dba.getProfileId(), dba);
                }
            }
        }
        for (OltLineProfile line : template.getLineProfiles()) {
            if (line.getTconts() != null) {
                for (OltTcont tcont : line.getTconts()) {
                    OltDbaProfile dba = dbaMap.get(tcont.getDbaProfileId());
                    tcont.setDbaName(dba == null || dba.getName() == null ? "" : dba.getName());
                }
            }
            // 设备侧没有 TR-069 标记位，按现场命名约定（模板名带 tr069）判定，判定放后端
            String name = line.getName() == null ? "" : line.getName().toLowerCase(Locale.ROOT);
            line.setTr069Bound(name.contains("tr069"));
            line.setMappingRows(lineMappingRows(line));
        }
    }

    /** 线路模板的映射关系表：一个 GEM port 一行，同 GEM 的多条映射合并成逗号串 */
    private static List<OltLineMappingRow> lineMappingRows(OltLineProfile line) {
        List<OltLineMappingRow> rows = new ArrayList<>();
        Map<Integer, OltTcont> tcontMap = new HashMap<>();
        if (line.getTconts() != null) {
            for (OltTcont tcont : line.getTconts()) {
                tcontMap.put(tcont.getTcontId(), tcont);
            }
        }
        List<OltGemport> gemports = line.getGemports() == null ? List.of() : line.getGemports();
        if (gemports.isEmpty()) {
            // 模板还没配 GEM：退回只列 T-CONT，DBA 模板列照常显示
            for (OltTcont tcont : tcontMap.values()) {
                OltLineMappingRow row = new OltLineMappingRow();
                row.setTcontId(tcont.getTcontId());
                row.setDbaProfileId(tcont.getDbaProfileId());
                row.setDbaName(tcont.getDbaName());
                rows.add(row);
            }
            return rows;
        }
        for (OltGemport gem : gemports) {
            OltTcont tcont = tcontMap.get(gem.getTcontId());
            OltLineMappingRow row = new OltLineMappingRow();
            row.setTcontId(gem.getTcontId());
            row.setDbaProfileId(tcont == null ? null : tcont.getDbaProfileId());
            row.setDbaName(tcont == null || tcont.getDbaName() == null ? "" : tcont.getDbaName());
            row.setGemportId(gem.getGemportId());
            row.setMappingMode(gem.getMappingMode());
            List<String> ids = new ArrayList<>();
            List<String> vlans = new ArrayList<>();
            if (gem.getMappings() != null && !gem.getMappings().isEmpty()) {
                for (OltGemMapping mapping : gem.getMappings()) {
                    if (mapping.getIndex() != null) {
                        ids.add(String.valueOf(mapping.getIndex()));
                    }
                    if (mapping.getVlan() != null && !mapping.getVlan().isBlank()) {
                        vlans.add(mapping.getVlan());
                    }
                }
            } else {
                if (gem.getMappingId() != null) {
                    ids.add(String.valueOf(gem.getMappingId()));
                }
                if (gem.getVlan() != null && !gem.getVlan().isBlank()) {
                    vlans.add(gem.getVlan());
                }
            }
            row.setMappingId(ids.isEmpty() ? null : String.join(",", ids));
            row.setVlan(vlans.isEmpty() ? null : String.join(",", vlans));
            rows.add(row);
        }
        return rows;
    }

    /** 板卡类别统计文本；整机设备直接报型号规格 */
    private static String boardSummary(List<OltBoard> boards, Map<String, Integer> kindCounts, String wholeSummary) {
        if (boards == null || boards.isEmpty()) {
            return "";
        }
        if (wholeSummary != null) {
            return wholeSummary;
        }
        String detail = kindCounts.entrySet().stream()
                .map(entry -> BOARD_CATEGORY_LABELS.getOrDefault(entry.getKey(), entry.getKey())
                        + " " + entry.getValue())
                .collect(Collectors.joining(" / "));
        return "共 " + boards.size() + " 块板卡（" + detail + "）";
    }

    /** VLAN 按类型分组（末行是二层互通 VLAN），分组内的 ID 压成「1、41、45-46」 */
    private static List<Map<String, Object>> vlanGroups(OltVlanInfo vlan) {
        List<Map<String, Object>> groups = new ArrayList<>();
        if (vlan == null || vlan.getVlans() == null || vlan.getVlans().isEmpty()) {
            return groups;
        }
        Map<String, List<Integer>> ids = new LinkedHashMap<>();
        Map<String, int[]> sums = new LinkedHashMap<>();
        for (OltVlan item : vlan.getVlans()) {
            if (item.getVlanId() == null) {
                continue;
            }
            String type = item.getType() == null ? "other" : item.getType().toLowerCase(Locale.ROOT);
            ids.computeIfAbsent(type, key -> new ArrayList<>()).add(item.getVlanId());
            int[] sum = sums.computeIfAbsent(type, key -> new int[2]);
            sum[0] += item.getStdPortNum() == null ? 0 : item.getStdPortNum();
            sum[1] += item.getServPortNum() == null ? 0 : item.getServPortNum();
        }
        ids.forEach((type, list) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("label", VLAN_TYPE_LABELS.getOrDefault(type, type));
            row.put("text", compressVlans(list));
            row.put("std", sums.get(type)[0]);
            row.put("serv", sums.get(type)[1]);
            groups.add(row);
        });
        List<Integer> connectIds = vlan.getVlans().stream()
                .filter(item -> item.getVlanConNum() != null && item.getVlanId() != null)
                .map(OltVlan::getVlanId).collect(Collectors.toList());
        Map<String, Object> connect = new LinkedHashMap<>();
        connect.put("type", "connect");
        connect.put("label", VLAN_TYPE_LABELS.getOrDefault("connect", "二层互通 VLAN"));
        connect.put("text", connectIds.isEmpty() ? "无" : compressVlans(connectIds));
        connect.put("std", null);
        connect.put("serv", null);
        groups.add(connect);
        return groups;
    }

    /** VLAN ID 列表压成连续区间：1,41,45,46 → 1、41、45-46（网管上就是这么写的） */
    private static String compressVlans(List<Integer> vlans) {
        if (vlans == null || vlans.isEmpty()) {
            return "";
        }
        List<Integer> sorted = new ArrayList<>(vlans);
        sorted.sort(Comparator.naturalOrder());
        List<String> parts = new ArrayList<>();
        int start = sorted.get(0);
        int prev = start;
        for (int i = 1; i < sorted.size(); i++) {
            int current = sorted.get(i);
            if (current == prev + 1) {
                prev = current;
                continue;
            }
            parts.add(start == prev ? String.valueOf(start) : start + "-" + prev);
            start = current;
            prev = current;
        }
        parts.add(start == prev ? String.valueOf(start) : start + "-" + prev);
        return String.join("、", parts);
    }

    /** VLAN 成员端口里缺 PON 口时按板卡补齐（口径原来在页面的 memberPorts computed 里） */
    private static void completeVlanMemberPorts(List<OltBoard> boards, OltVlanInfo vlan) {
        if (vlan == null || boards == null || boards.isEmpty()) {
            return;
        }
        boolean hasPon = vlan.getMemberPorts().stream().anyMatch(port ->
                "epon".equalsIgnoreCase(port.getType()) || "gpon".equalsIgnoreCase(port.getType()));
        if (hasPon) {
            return;
        }
        List<OltVlanPort> ponPorts = new ArrayList<>();
        for (OltBoard board : boards) {
            String portMode = board.getPortMode() == null ? "" : board.getPortMode().trim().toUpperCase(Locale.ROOT);
            if (!"EPON".equals(portMode) || board.getPorts() == null) {
                continue;
            }
            for (OltPort port : board.getPorts()) {
                if (port.getPortNo() == null) {
                    continue;
                }
                OltVlanPort member = new OltVlanPort("epon", port.getPortNo());
                // 名字优先用设备回的端口名（P0 / P1），和页面原来的取法一致
                if (port.getPortName() != null && !port.getPortName().isBlank()) {
                    member.setLabel(port.getPortName());
                }
                ponPorts.add(member);
            }
        }
        if (!ponPorts.isEmpty()) {
            ponPorts.addAll(vlan.getMemberPorts());
            vlan.setMemberPorts(ponPorts);
        }
    }

    private String describe(OltSnapshot snapshot) {
        OltVendor vendor = snapshot.getVendor() == null ? OltVendor.GENERIC : snapshot.getVendor();
        StringBuilder message = new StringBuilder("登录成功：").append(vendor.getLabel());
        if (snapshot.getModel() != null) {
            message.append(' ').append(snapshot.getModel());
        }
        if (snapshot.getSoftwareVersion() != null) {
            message.append(' ').append(snapshot.getSoftwareVersion());
            if (snapshot.getPatchVersion() != null) {
                message.append(" / ").append(snapshot.getPatchVersion());
            }
        }
        if (snapshot.getCliStyle() != null) {
            message.append("，命令风格 ").append(snapshot.getCliStyle().getKeyword());
        }
        if (snapshot.getPrompt() != null && !snapshot.getPrompt().isBlank()) {
            message.append("，提示符 ").append(snapshot.getPrompt());
        }
        return message.toString();
    }

    private String normalizeCharset(String value) {
        String charset = trim(value).toUpperCase(Locale.ROOT);
        if (charset.isEmpty()) {
            return "AUTO";
        }
        if ("UTF8".equals(charset)) {
            charset = "UTF-8";
        }
        if (!CHARSETS.contains(charset)) {
            throw new IllegalArgumentException("字符集编码仅支持 AUTO / UTF-8 / GBK / GB18030");
        }
        return charset;
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
