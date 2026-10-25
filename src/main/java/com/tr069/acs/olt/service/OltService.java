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
import com.tr069.acs.olt.model.OltDetail;
import com.tr069.acs.olt.model.OltDbaProfileRequest;
import com.tr069.acs.olt.model.OltOntQueryRequest;
import com.tr069.acs.olt.model.OltOntInfo;
import com.tr069.acs.olt.model.OltOntQueryResult;
import com.tr069.acs.olt.model.OltServicePort;
import com.tr069.acs.olt.model.OltDeviceView;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltSaveRequest;
import com.tr069.acs.olt.model.OltSyncRecordView;
import com.tr069.acs.olt.model.OltTemplateInfo;
import com.tr069.acs.olt.model.OltTestResult;
import com.tr069.acs.olt.model.OltVlanInfo;
import com.tr069.acs.olt.telnet.OltInfoParser;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        boards.forEach(this::fillPorts);
        detail.setBoards(boards);
        detail.setRecords(records(id, 10));
        detail.setVlan(readVlan(device.getVlanJson()));
        detail.setTemplate(readTemplate(device.getTemplateJson()));
        return detail;
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

    /** 采集并落库的公共实现：返回落库后的最新详情 */
    private OltDetail collectAndPersist(Long id, String operator, String actionLabel) {        OltDevice device = requireDevice(id);
        long start = System.currentTimeMillis();
        try {
            OltSnapshot snapshot = telnetService.collect(device, true);
            long cost = System.currentTimeMillis() - start;
            LocalDateTime now = LocalDateTime.now();

            boardMapper.delete(new LambdaQueryWrapper<OltBoard>().eq(OltBoard::getOltId, id));
            List<OltBoard> boards = snapshot.getBoards() == null ? List.of() : snapshot.getBoards();
            for (OltBoard board : boards) {
                board.setOltId(id);
                board.setSyncedAt(now);
                board.setPortsJson(writePorts(board.getPorts()));
                boardMapper.insert(board);
            }

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
    public OltDetail createDbaProfile(Long id, OltDbaProfileRequest request, String operator) {
        requireDevice(id);
        Integer profileId = request.getProfileId();
        if (profileId == null || profileId < 10 || profileId > 512) {
            throw new IllegalArgumentException("模板 ID 必须在 10-512 之间");
        }
        String name = request.getName() == null ? "" : request.getName().trim();
        if (!name.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("模板名只能由数字、字母及下划线组成，最长 32 位");
        }
        String type = request.getType() == null ? "" : request.getType().trim().toLowerCase();
        if (!type.matches("type[1-5]")) {
            throw new IllegalArgumentException("DBA 类型必须是 type1~type5");
        }
        Integer fix = positive(request.getFixKbps(), "固定带宽");
        Integer assure = positive(request.getAssureKbps(), "保证带宽");
        Integer max = positive(request.getMaxKbps(), "最大带宽");
        String command = switch (type) {
            case "type1" -> {
                requireNotNull(fix, "type1（固定带宽）需要填写固定带宽");
                yield "dba-profile add profile-id " + profileId + " profile-name \"" + name
                        + "\" type1 fix " + fix;
            }
            case "type2" -> {
                requireNotNull(assure, "type2（保证带宽）需要填写保证带宽");
                yield "dba-profile add profile-id " + profileId + " profile-name " + name
                        + "\" type2 assure " + assure;
            }
            case "type3" -> {
                requireNotNull(assure, "type3（保证带宽+最大带宽）需要填写保证带宽");
                requireNotNull(max, "type3（保证带宽+最大带宽）需要填写最大带宽");
                yield "dba-profile add profile-id " + profileId + " profile-name " + name
                        + "\" type3 assure " + assure + " max " + max;
            }
            case "type4" -> {
                requireNotNull(max, "type4（最大带宽）需要填写最大带宽");
                yield "dba-profile add profile-id " + profileId + " profile-name " + name
                        + "\" type4 max " + max;
            }
            default -> {
                requireNotNull(fix, "type5（固定+保证+最大带宽）需要填写固定带宽");
                requireNotNull(assure, "type5（固定+保证+最大带宽）需要填写保证带宽");
                requireNotNull(max, "type5（固定+保证+最大带宽）需要填写最大带宽");
                yield "dba-profile add profile-id " + profileId + " profile-name " + name
                        + "\" type5 fix " + fix + " assure " + assure + " max " + max;
            }
        };
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
        if (snapshot.getVendor() != null) {
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
