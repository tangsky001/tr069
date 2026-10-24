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
import com.tr069.acs.olt.model.OltDeviceView;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltSaveRequest;
import com.tr069.acs.olt.model.OltSyncRecordView;
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
        return detail;
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
        deviceMapper.update(null, update);
        return OltDeviceView.from(deviceMapper.selectById(id));
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
        OltDevice device = requireDevice(id);
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
            // 命令没回显 / 回显解析不了时把原因带出来，页面不用去翻回显才知道为什么是 0 块
            if (snapshot.getNotes() != null && !snapshot.getNotes().isEmpty()) {
                summary = summary + "；" + String.join("；", snapshot.getNotes());
            }
            updateAfterConnect(id, snapshot, true, summary);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, true,
                    describe(snapshot) + "；" + summary, snapshot.getTranscript(), cost, operator);
            return detail(id);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String message = rootMessage(e);
            log.warn("OLT 同步失败 id={}: {}", id, message);
            saveRecord(id, OltSyncRecord.ACTION_SYNC, false, message, null, cost, operator);
            markOffline(id, message);
            throw new IllegalStateException("同步失败：" + message, e);
        }
    }

    // ---------------------------------------------------------------- 内部

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
