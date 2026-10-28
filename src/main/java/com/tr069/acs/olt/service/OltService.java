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
import com.tr069.acs.olt.model.OltServicePort;
import com.tr069.acs.olt.model.OltQuickSetupRequest;
import com.tr069.acs.olt.model.OltBoardProfile;
import com.tr069.acs.olt.model.OltBoardStatusRule;
import com.tr069.acs.olt.model.OltDeviceOptions;
import com.tr069.acs.olt.model.OltDeviceView;
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
import com.tr069.acs.olt.model.OltVlanInfo;
import com.tr069.acs.olt.model.OltVlanForm;
import com.tr069.acs.olt.model.OltVlanPort;
import com.tr069.acs.olt.model.OltVlanRequest;
import com.tr069.acs.olt.model.VlanIdExpression;
import com.tr069.acs.olt.telnet.OltInfoParser;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;
import com.tr069.acs.olt.vendor.HsgqModelCatalog;
import com.tr069.acs.olt.vendor.OltBoardCatalog;
import com.tr069.acs.olt.vendor.OltVendorProfile;
import com.tr069.acs.olt.vendor.OltVendorProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OLT 台账管理 + 连接测试 + 同步入库。
 *
 * <p>「同步」才允许写板卡表：诊断测试只验证能不能登录，不动已有数据，
 * 避免一次手滑把好好的板卡信息覆盖成空。</p>
 */
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

    /**
     * 后台补采线程池：OLT 的 Telnet 并发会话数很小（华为一般只放几个），两台并行足够。
     * 线程是守护线程，不影响进程退出。
     */
    private static final ExecutorService DEEP_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "olt-deep-sync");
        thread.setDaemon(true);
        return thread;
    });

    /** 正在后台补采的 OLT：同一台不重复排队 */
    private final Set<Long> deepSyncing = ConcurrentHashMap.newKeySet();

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
        detail.setVlan(readVlan(device.getVlanJson()));
        detail.setTemplate(readTemplate(device.getTemplateJson()));
        // 后台正在补采慢数据（端口 / ONT / VLAN / 模板）时告诉页面，页面据此提示并轮询
        detail.setDeepSyncing(deepSyncing.contains(id));
        return detail;
    }

    /**
     * 按型号档案把板卡补齐：面板样式、是否主控板，端口类型/数量在设备没给时按档案兜底。
     *
     * <p>华为 {@code display board 0} 只回板卡名和状态，端口数得靠型号档案补；
     * 状态也在这里翻成中文和等级，页面不用再拿正则去匹配设备回的英文。</p>
     */
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

    /**
     * 可勾选的 Web 登录账号，取自管理端用户表。
     */
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

    /**
     * 厂商码归一：留空或不在枚举内都当没传，避免把脏值写进台账后页面分发出错。
     */
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

    /**
     * 诊断测试：用请求里的参数（或已保存的参数）连一次设备，不写板卡。
     */
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

    /**
     * 同步：登录采集版本与板卡，覆盖写板卡表并刷新台账。
     */
    public OltDetail sync(Long id, String operator) {
        return collectAndPersist(id, operator, "同步");
    }

    /**
     * 实时刷新：直连 OLT 采最新数据，写库后把最新那份返回给页面。
     *
     * <p>板卡、端口 ONT、VLAN、DBA 这些的唯一权威就是 OLT 自己，库里那份只是快照——
     * 页面打开时、对 OLT 做过操作之后都走这里拿最新的，顺手把库里的快照刷新成同一份，
     * 这样设备离线或连不上时页面还能拿最后一次的实时数据兜底。与 sync 的差别只是动作名。</p>
     */
    public OltDetail refresh(Long id, String operator) {
        return collectAndPersist(id, operator, "实时刷新");
    }

    /**
     * 快速同步：一次短连接把板卡（型号 / 板卡类型 / 槽位 / 机框槽位数）拿回来写库，
     * 立即返回；端口状态、ONT、VLAN、模板交给后台异步补采。
     *
     * <p>页面第一次打开一台 OLT 等的是机框：华为一条 {@code display board 0} 一两秒就回来了，
     * 板卡插在哪个槽位、什么型号一眼看全。温度、逐板端口状态、ONT 明细、VLAN、模板
     * 要进配置视图逐板逐口问，几十条命令十几秒，跟首屏没关系——放后台跑完写库，
     * 页面照常轮询就能看到补齐的数据。</p>
     */
    public OltDetail quickSync(Long id, String operator) {
        OltDevice device = requireDevice(id);
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
        submitDeepSync(id, operator);
        return detail(id);
    }

    /**
     * 后台补采慢数据：温度、端口状态与光模块、ONT 明细、VLAN、模板。
     * 跑完写库，页面轮询详情即可看到；同一台已经在补就不重复排队。
     */
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

    /**
     * 覆盖写板卡表。
     *
     * @param mergeOld true 时本次没采到的端口 / 温度沿用上次留下的（快速同步用）
     */
    private int writeBoards(Long id, List<OltBoard> boards, LocalDateTime now, boolean mergeOld) {
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

    /**
     * 单条 ONT 按需查询：配置摘录 / 详情 / 光信息 / 版本 / 注册历史。
     *
     * <p>这类明细几百条 ONT 逐个采不现实，只在页面上点某条的图标时按需登录查一次，
     * 回显块状解析后连原始回显一起返回；查询动作写一条采集记录留档。</p>
     */
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
            saveRecord(id, OltSyncRecord.ACTION_TEST, true,
                    "ONT查询：" + result.getCommand(), result.getRaw(),
                    System.currentTimeMillis() - start, operator);
            return result;
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, OltSyncRecord.ACTION_TEST, false,
                    "ONT查询失败：" + action + "：" + message, null,
                    System.currentTimeMillis() - start, operator);
            throw new IllegalStateException("ONT 查询失败：" + message, e);
        }
    }

    /**
     * 新增 DBA 模板：构造 {@code dba-profile add ...} 下发，成功后自动重采写库。
     *
     * <p>校验规则与网管一致：ID 10-512 且不重复、模板名数字字母下划线、
     * 五类模板各取所需（type1 只要固定、type4 只要最大、type5 三个都要）。</p>
     */
    /**
     * 新增 DBA 模板的命令：{@code dba-profile add profile-id 10 profile-name "dba-100M" type4 max 102400}。
     *
     * <p>校验和命令只有这一处。五类模板各取所需（type1 只要固定、type4 只要最大、
     * type5 三个都要），少填一个就在这里抛出去——页面的命令预览和实际下发都走它，
     * 不会再出现「预览看着能发、下发被设备拒」。</p>
     */
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

    /**
     * 「新增 DBA 模板」表单的元数据：有哪五类、每类要填哪几个带宽、取值上下限。
     *
     * <p>页面不再自己保存这些约束——以前服务端校验一份、页面提示一份，
     * 改了这边忘那边就会「页面说能填、下发被拒」。</p>
     */
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

    /**
     * 新建 VLAN：鸿升光下发 {@code vlan standard <id>}（一段连续 ID 写成
     * {@code vlan standard 101 110}），成功后自动重采写库。
     */
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

    /**
     * 新建 VLAN 的命令（含校验）：{@code vlan standard 41} → {@code port epon 1-4 tagged} → {@code exit}。
     *
     * <p>表达式怎么解析（{@code 41,45-46}）、VLAN ID 允许到多少、一次最多几个、
     * 哪些端口能做成员，以前页面上也存了一份；现在页面预览和实际下发都走这个方法，
     * 「页面说能建、下发被设备拒」这种事不会再发生。</p>
     */
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

    /**
     * TR-069 模板列表：不读库，每次实时登设备查。
     *
     * <p>页面上「刷新」就是调这个；设备上改了模板这里立刻能看见，
     * 也不会因为库里存着旧快照而显示成已经删掉/已经新建。</p>
     */
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

    /**
     * 新增 TR-069 模板：下发 {@code onu-tr069-profile add ...}，成功后立刻重查一次返回新列表。
     *
     * <p>不重采整台设备——模板跟板卡/ONU 数据无关，没必要为了一个模板等十几秒。</p>
     */
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

    /**
     * 一键开局：把页面的几个勾选项编成一批命令一次下发，成功后重采。
     *
     * <p>三步各自独立，哪个字段是空就跳过哪一步；命令由厂商 profile 编，
     * 这里只管校验和下发结果。</p>
     */
    public OltDetail quickSetup(Long id, OltQuickSetupRequest request, String operator) {
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsQuickSetup()) {
            throw new IllegalArgumentException("这台设备还没接入一键开局，先到设备上手动配");
        }
        if (request == null) {
            throw new IllegalArgumentException("请把开局选项填完整");
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
        return dispatchConfig(id, commands, operator, describeQuickSetup(request));
    }

    /**
     * 一键开局预演：不下发，只把这次会执行的命令和要注意的地方算出来给页面看。
     *
     * <p>和 {@link #quickSetup} 走同一套校验、同一个 {@code quickSetupCommands}，
     * 所以页面看到的必然就是即将下发的那条序列。以前前端自己拼一份预览，
     * 命令一改两边就对不上（先建 VLAN 还是先改端口这种顺序问题尤其容易漏）。</p>
     *
     * <p>填了一半也能预演：写法不对（VLAN 表达式、URL 格式）直接抛出去让页面显示，
     * 只是没勾满则给提醒、命令为空。</p>
     */
    public OltQuickSetupPreview previewQuickSetup(Long id, OltQuickSetupRequest request) {
        OltVendorProfile profile = profileOf(id);
        if (!profile.supportsQuickSetup()) {
            throw new IllegalArgumentException("这台设备还没接入一键开局，先到设备上手动配");
        }
        if (request == null) {
            return new OltQuickSetupPreview(List.of(), List.of("先勾选要做的开局项"));
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

    /**
     * 一键开局表单：可选模式、VLAN 取值范围、一次最多几个端口、TR-069 默认填什么。
     *
     * <p>这些都是设备侧约定，以前散在页面里（还和另一处面板对不上），现在后端一处给出。</p>
     */
    public OltQuickSetupForm quickSetupForm(Long id) {
        OltVendorProfile profile = profileOf(id);
        return new OltQuickSetupForm(profile.supportsQuickSetup(), QUICK_MODE_LIST, DEFAULT_QUICK_MODE,
                QUICK_PORT_TYPES, DEFAULT_QUICK_PORT_TYPES, MAX_QUICK_PORTS,
                1, VlanIdExpression.MAX_VLAN_ID, defaultTr069Request());
    }

    /**
     * TR-069 模板表单：默认值 + 取值约束。
     *
     * <p>「一键开局」和「TR069模板」页签的「增加」两个入口共用这一份，
     * 不会出现两处默认值或长度限制不一致。</p>
     */
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

    /**
     * 命令动作预演：把进视图、命令本身、退出算出来，不下发。
     *
     * <p>视图怎么进怎么出是设备知识，以前页面自己加 {@code exit}，
     * 现在和真正执行走同一个 {@code planCommands}。</p>
     */
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

    /**
     * 设备台账表单的备选项：厂商码表、采集字符集、默认连接端口和默认账号。
     *
     * <p>厂商列表直接由 {@link OltVendor} 枚举给出，加一个厂商不用再改页面。</p>
     */
    public static OltDeviceOptions deviceOptions() {
        List<OltOption> vendors = new ArrayList<>();
        for (OltVendor vendor : OltVendor.values()) {
            vendors.add(new OltOption(vendor.name(), vendor.getLabel()));
        }
        return new OltDeviceOptions(vendors, List.of("AUTO", "UTF-8", "GBK", "GB18030"),
                DEFAULT_SSH_PORT, DEFAULT_TELNET_PORT, "root", "admin",
                OltBoardCatalog.profiles(), VIEW_LABELS, ONU_ACTIONS, VLAN_TYPE_LABELS,
                BOARD_CATEGORY_LABELS, DEFAULT_SLOT_COUNT, OltBoardCatalog.ENV_TEMP_ALARM_C);
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

    /** 机框槽位数的兜底：设备没回机框槽位数时按这个画 */
    private static final int DEFAULT_SLOT_COUNT = 12;

    /** 页面分组展示端口的顺序（设备侧没规定顺序，只是页面看起来顺） */
    private static final List<String> VLAN_PORT_TYPE_ORDER = List.of("epon", "gpon", "ge", "xge");

    /** 同一个端口不能既 tagged 又 untagged：这台设备只认一种带标签方式 */
    private static final boolean VLAN_PORT_EXCLUSIVE_TAG_MODE = true;

    /** 页面传上来的端口写法：{@code epon1} / {@code ge3} / {@code xge2} */
    private static final Pattern VLAN_PORT_REF = Pattern.compile(
            "(?i)^(epon|gpon|ge|xge)\\s*(\\d{1,3})$");

    /**
     * 校验页面勾的端口：写法要对、类型要是这台设备能加进 VLAN 的。
     *
     * <p>GPON 那台的 PON 口不做二层成员，页面本来也不会给勾，这里再拦一次——
     * 命令发过去设备只会回一句报错，不如当面说清楚。</p>
     */
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

    /** 这台设备的厂商 profile（VLAN 这类命令写法按厂商走） */
    private OltVendorProfile profileOf(Long id) {
        return OltVendorProfiles.of(OltVendor.of(requireDevice(id).getVendor()));
    }

    /**
     * 这台设备支持的命令动作目录（页面「设备配置」页签按章节渲染）。
     *
     * <p>动作定义在厂商 profile 里（鸿升光是 {@code HsgqActionCatalog}），
     * 页面不写死任何一条命令。</p>
     */
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

    /**
     * 进视图的命令模板，占位符留着让页面按填的值替换。
     *
     * <p>和 {@link #planCommands} 里真正下发的那条是同一句（那边换成具体值），
     * 测试里会比对两边，改了一处忘了另一处会直接失败。</p>
     */
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

    /**
     * 执行一条命令动作：校验参数 → 按视图编排命令 → 下发。
     *
     * <p>查询类（如 {@code show cpu-usage}）把回显返回页面，不触发重采；
     * 配置类执行完重采一次，返回最新详情，页面直接换上去。</p>
     */
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
        try {
            List<String> outputs = telnetService.sendConfig(device, plan);
            result.setOutputs(outputs);
            saveRecord(id, OltSyncRecord.ACTION_CONFIG, true,
                    action.label() + "：" + String.join("；", plan),
                    String.join("\n", outputs), System.currentTimeMillis() - start, operator);
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, OltSyncRecord.ACTION_CONFIG, false,
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

    /** 端口 / VLAN 列表的写法：1,3,4-8 */
    private static final Pattern PORT_LIST = Pattern.compile("^\\d{1,4}([,-]\\d{1,4})*$");

    /**
     * 文本参数：中英文、数字和常见符号，允许空格（端口描述、用户描述要能写中文），
     * 但不许换行和分号——命令是一条条发下去的，混进换行就等于多发一条命令。
     */
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
        OltDevice device = requireDevice(id);
        long start = System.currentTimeMillis();
        try {
            List<String> outputs = telnetService.sendConfig(device, commands);
            String transcript = String.join("\n", outputs);
            saveRecord(id, OltSyncRecord.ACTION_CONFIG, true,
                    label + "：" + String.join("；", commands), transcript,
                    System.currentTimeMillis() - start, operator);
            // 采集到的最新数据既写库又直接返回，页面不用再拉一次
            return collectAndPersist(id, operator, label + "后重采");
        } catch (Exception e) {
            String message = rootMessage(e);
            saveRecord(id, OltSyncRecord.ACTION_CONFIG, false,
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

    /**
     * 组装「试连」用的参数：请求里填了就用请求的，没填就用库里已保存的。
     */
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

    /**
     * 板卡类别统计，拼在同步摘要里：业务板 1 / 主控板 1 / 上联板 1。
     */
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

    /**
     * 端口光模块状态按 JSON 存板卡行上：端口是随板卡整体刷新的快照，单独建表反而多一次联表。
     */
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
            board.setPorts(objectMapper.readValue(json, new TypeReference<List<OltPort>>() {
            }));
        } catch (JsonProcessingException e) {
            log.warn("端口状态解析失败 slot={}: {}", board.getSlotNo(), e.getMessage());
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
