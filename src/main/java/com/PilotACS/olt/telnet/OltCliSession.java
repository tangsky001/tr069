/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.601
 *
 */

package com.PilotACS.olt.telnet;
import com.PilotACS.common.log.Logs;

import com.PilotACS.olt.entity.OltBoard;
import com.PilotACS.olt.entity.OltDevice;
import com.PilotACS.olt.model.OltDbaProfile;
import com.PilotACS.olt.model.OltGemport;
import com.PilotACS.olt.model.OltLineProfile;
import com.PilotACS.olt.model.OltServicePort;
import com.PilotACS.olt.model.OltSrvProfile;
import com.PilotACS.olt.model.OltTrafficTable;
import com.PilotACS.olt.model.OltTr069Profile;
import com.PilotACS.olt.model.OltOnt;
import com.PilotACS.olt.model.OltOntDeleteResult;
import com.PilotACS.olt.model.OltOntInfo;
import com.PilotACS.olt.model.OltTemplateInfo;
import com.PilotACS.olt.model.OltPort;
import com.PilotACS.olt.model.OltPortVlan;
import com.PilotACS.olt.model.OltVlan;
import com.PilotACS.olt.model.OltVlanInfo;
import com.PilotACS.olt.vendor.OltVendorProfile;
import com.PilotACS.olt.vendor.OltVendorProfiles;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 一台 OLT 的命令行会话：登录 → 识别厂商 → 采集版本/板卡 → 关闭 */
public class OltCliSession implements Closeable {

    public static final String DEFAULT_USERNAME = "root";

    public static final String DEFAULT_PASSWORD = "admin";

    private static final Pattern USERNAME_PROMPT = Pattern.compile(
            "(?i)(?:user\\s*name|username|login)\\s*[:：]\\s*$");

    private static final Pattern PASSWORD_PROMPT = Pattern.compile(
            "(?i)(?:password|passwd)\\s*[:：]\\s*$");

    /** 命令行提示符 */
    private static final Pattern SHELL_PROMPT = Pattern.compile("[^\\r\\n]{0,80}[>#\\]]\\s*\\z");

    /** 参数提示 */
    static final Pattern PARAM_PROMPT = Pattern.compile(
            "(?m)(?:[^\\r\\n]*\\{\\s*<cr>[^\\r\\n]*\\}"
                    + "|[^\\r\\n]*\\{[^{}]*\\}\\s*[:：]\\s*$"
                    + "|[^\\r\\n]*param(?:eter)?s?\\s*[:：][^\\r\\n]*)\\s*[:：]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 追问应答行前缀 */
    public static final String ANSWER_PREFIX = ">>";

    /** 交互式命令的追问特征 */
    private static final Pattern INTERACTIVE_ASK = Pattern.compile("(?m)[^\\r\\n]*[:：?？]\\s*$");

    private static final List<Pattern> INTERACTIVE_STOPS = List.of(INTERACTIVE_ASK, SHELL_PROMPT);

    /** save 的参数停点 */
    private static final Pattern SAVE_BRACE_PROMPT = Pattern.compile(
            "(?m)\\{[^{}]*<cr>[^{}]*\\}\\s*[:：]?\\s*\\z");

    /** 参数提示最多补回车次数 */
    private static final int MAX_PARAM_ROUNDS = 3;

    private static final Pattern LOGIN_FAILED = Pattern.compile(
            "(?i)(login\\s+(incorrect|invalid)|authentication\\s+(fail|failed|denied)"
                    + "|password\\s+(incorrect|invalid)|access\\s+denied|permission\\s+denied"
                    + "|too\\s+many\\s+(users|failures)|invalid\\s+user"
                    + "|密码错误|用户名或密码|登录失败|认证失败)");

    /** 板卡探测命令 */
    private static final List<String> BOARD_COMMANDS = List.of("display board 0", "show card", "show slot");

    /** 设备拒绝命令的回显特征 */
    private static final Pattern COMMAND_REJECTED = Pattern.compile(
            "(?i)(unknown\\s+command|unrecognized\\s+command|invalid\\s+(command|input)"
                    + "|incorrect\\s+command|command\\s+not\\s+found"
                    + "|错误的命令|命令不存在|不支持的命令)");

    /** 版本探测超时上限 */
    private static final int VERSION_PROBE_TIMEOUT_MS = 8000;

    /** 板卡探测超时上限 */
    private static final int BOARD_PROBE_TIMEOUT_MS = 8000;

    /** 存盘超时上限 */
    private static final int SAVE_TIMEOUT_MS = 90_000;

    /** save 后的缓冲期 */
    private static final long SAVE_GRACE_MS = 5_000L;

    private static final List<Pattern> COMMAND_STOPS = List.of(SHELL_PROMPT, PARAM_PROMPT);

    private static final int MAX_LOGIN_ENTER = 5;

    private static final long LOGIN_IDLE_MS = 400L;
    private static final long LOGIN_IDLE_MAX_MS = 2000L;

    private final TelnetClient client;

    private final int commandTimeoutMs;

    private final boolean logEcho;

    private final OltVendor vendorHint;

    private final StringBuilder transcript = new StringBuilder();

    private final StringBuilder banner = new StringBuilder();

    private volatile String prompt;

    /** 会话当前停留的视图 */
    private volatile CliView view = CliView.ROOT;

    /** 视图层级 */
    public enum CliView {
        ROOT, CONFIG, SUB, UNKNOWN
    }

    private OltVendor pagingPrepared;

    private volatile boolean saving;

    private volatile long saveFinishedAt;

    private volatile boolean sessionLost;

    private IOException lastError;

    private OltCliSession(TelnetClient client, int commandTimeoutMs, OltVendor vendorHint, boolean logEcho) {
        this.client = client;
        this.commandTimeoutMs = commandTimeoutMs;
        this.vendorHint = vendorHint == null ? OltVendor.GENERIC : vendorHint;
        this.logEcho = logEcho;
    }

    /** 建立连接并完成登录 */
    public static OltCliSession open(OltDevice device, Charset charset, int connectTimeoutMs,
                                     int loginTimeoutMs, int commandTimeoutMs) throws IOException {
        return open(device, charset, connectTimeoutMs, loginTimeoutMs, commandTimeoutMs, false);
    }

    /** 建立连接并完成登录 */
    public static OltCliSession open(OltDevice device, Charset charset, int connectTimeoutMs,
                                     int loginTimeoutMs, int commandTimeoutMs, boolean logEcho) throws IOException {
        String protocol = device.getProtocol() == null ? "TELNET" : device.getProtocol().trim().toUpperCase(Locale.ROOT);
        if (!"TELNET".equals(protocol)) {
            throw new OltTelnetException("当前仅实现 Telnet 登录，SSH 请先选 Telnet 或等待后续支持");
        }
        int port = device.getPort() == null || device.getPort() <= 0 ? 23 : device.getPort();
        TelnetClient client = TelnetClient.connect(device.getHost(), port, device.getBindLocalIp(),
                charset, connectTimeoutMs, Math.min(commandTimeoutMs, 3000));
        OltCliSession session = new OltCliSession(client, commandTimeoutMs, OltVendor.of(device.getVendor()), logEcho);
        try {
            session.login(usernameOf(device), passwordOf(device), loginTimeoutMs);
            return session;
        } catch (IOException e) {
            client.close();
            throw e;
        }
    }

    public static String usernameOf(OltDevice device) {
        String username = device.getUsername();
        return sanitize(username == null || username.isBlank() ? DEFAULT_USERNAME : username.trim());
    }

    public static String passwordOf(OltDevice device) {
        String password = device.getPassword();
        if (password == null || password.isBlank()) {
            return DEFAULT_PASSWORD;
        }
        return sanitize(com.PilotACS.olt.util.OltCrypto.decrypt(password));
    }

    /** 过滤凭据中的控制字符 */
    private static String sanitize(String cred) {
        StringBuilder sb = new StringBuilder(cred.length());
        for (char c : cred.toCharArray()) {
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 按页签同步的采集段 */
    public enum Section {
        /** 板卡温度（OLT 状态页签的板卡卡片） */
        TEMPERATURES,
        /** 逐板端口状态（光模块在位/链路） */
        PORT_STATES,
        /** ONT 明细（含逐条单台详情补采：管理 IP / MAC / 模板） */
        ONTS,
        /** 端口光模块数值（发光/收光/温度/偏置/电压） */
        OPTICS,
        /** VLAN 快照 */
        VLANS,
        /** DBA / 线路 / 业务 / TR069 / 流量模板快照 */
        TEMPLATES
    }

    public OltSnapshot collect(boolean withBoards) {
        return collect(withBoards, EnumSet.allOf(Section.class));
    }

    public OltSnapshot collect(boolean withBoards, Set<Section> sections) {
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();

        ensureRootView(Math.min(commandTimeoutMs, 5000));
        prepareTerminal(vendorHint);

        VersionProbe probe = probeVersion();
        OltVendor vendor = detectVendor(probe.text());
        if (vendor == OltVendor.GENERIC && vendorHint != OltVendor.GENERIC) {
            vendor = vendorHint;
        }
        String versionText = probe.text();
        if (versionText.isBlank()) {
            notes.add("display version / show version 均无回显（命令超时或连接被断开）");
        }

        String boardText = "";
        List<OltBoard> boards = List.of();
        List<String> boardCommands = boardCommands(vendor, probe.style());
        if (withBoards && !OltVendorProfiles.of(vendor).wholeDevice()) {
            for (String command : boardCommands) {
                String out = runCommand(command);
                if (out != null && !out.isBlank()) {
                    boardText = out;
                }
                boards = OltInfoParser.parseBoards(boardText);
                if (!boards.isEmpty()) {
                    break;
                }
            }
            if (boards.isEmpty()) {
                notes.add(boardText.isBlank()
                        ? boardCommands.get(0) + " 无回显（命令超时或连接被断开）"
                        : boardCommands.get(0) + " 回显无法解析：" + headLine(boardText));
            }
        }

        vendor = detectVendor(versionText, boardText);
        String model = OltInfoParser.extractModel(vendor, versionText);
        if (model == null) {
            model = OltInfoParser.extractModel(vendor, banner.toString());
        }
        if (withBoards && OltVendorProfiles.of(vendor).wholeDevice()) {
            boards = List.of(OltVendorProfiles.of(vendor).buildWholeDevice(model));
            boardText = "";
        }

        snapshot.setVendor(vendor);
        snapshot.setCliStyle(cliStyleOf(vendor, probe.style()));
        snapshot.setModel(model);
        snapshot.setVersionText(versionText);
        snapshot.setBoardText(boardText);
        snapshot.setSoftwareVersion(OltInfoParser.extractSoftwareVersion(versionText));
        snapshot.setPatchVersion(OltInfoParser.extractPatchVersion(versionText));
        snapshot.setBackplane(OltInfoParser.extractBackplane(versionText));
        snapshot.setMacAddress(OltInfoParser.extractMacAddress(versionText));
        snapshot.setUptime(OltInfoParser.extractUptime(versionText));
        snapshot.setPrompt(prompt);

        if (withBoards) {
            if (!sections.isEmpty()) {
                if (vendor == OltVendor.HUAWEI && !boards.isEmpty()) {
                    collectFromConfigView(boards, notes, snapshot, sections, true);
                } else if (!boards.isEmpty()) {
                    OltVendorProfiles.of(vendor)
                            .collectDetails(this::runCommand, snapshot, boards, notes, Math.min(commandTimeoutMs, 8000));
                }
            }
            if (snapshot.getBackplane() == null && vendor == OltVendor.HUAWEI) {
                String backplaneText = runCommand("display version backplane 0");
                if (backplaneText != null && !backplaneText.isBlank()) {
                    snapshot.setBackplane(OltInfoParser.extractBackplane(backplaneText));
                }
            }
            if (snapshot.getBackplane() == null) {
                snapshot.setBackplane(OltInfoParser.extractBackplaneBoard(boardText));
            }
            snapshot.setBoards(boards);
            snapshot.setSlotCount(OltVendorProfiles.of(vendor).slotCount(boardText, boards));
        }

        if (lastError != null) {
            notes.add("命令异常：" + lastError.getMessage());
        }
        snapshot.setNotes(notes);
        snapshot.setTranscript(transcript.toString());
        return snapshot;
    }

    /** 快速采集：只要板卡 */
    public OltSnapshot collectBoardsOnly() {
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();
        OltVendor vendor = vendorHint != OltVendor.GENERIC ? vendorHint : detectVendor(banner.toString());
        int timeout = Math.min(commandTimeoutMs, BOARD_PROBE_TIMEOUT_MS);
        ensureRootView(Math.min(commandTimeoutMs, 5000));
        prepareTerminal(vendor, true);

        String boardText = "";
        List<OltBoard> boards = List.of();
        List<String> commands = boardCommands(vendor, cliStyleOf(vendor, null));
        if (!OltVendorProfiles.of(vendor).wholeDevice()) {
            for (String command : commands) {
                String out = runCommand(command, timeout);
                if (out == null || out.isBlank()) {
                    continue;
                }
                boardText = out;
                boards = OltInfoParser.parseBoards(out);
                break;
            }
        }
        if (vendor == OltVendor.GENERIC) {
            vendor = detectVendor(boardText);
        }
        if (boards.isEmpty() && boardText.isBlank()
                && vendor != OltVendor.GENERIC && !OltVendorProfiles.of(vendor).wholeDevice()) {
            for (String command : BOARD_COMMANDS) {
                if (commands.contains(command)) {
                    continue;
                }
                String out = runCommand(command, timeout);
                if (out != null && !out.isBlank()) {
                    boardText = out;
                    boards = OltInfoParser.parseBoards(out);
                    OltVendor detected = OltVendor.detect(out);
                    if (detected != OltVendor.GENERIC) {
                        vendor = detected;
                    }
                }
                break;
            }
        }

        String model = OltInfoParser.extractModel(vendor, banner.toString());
        if (model == null) {
            model = OltInfoParser.extractModel(vendor, boardText);
        }
        String versionText = "";
        if (model == null || OltVendorProfiles.of(vendor).wholeDevice()) {
            for (String command : versionCommands(vendor)) {
                String out = runCommand(command, Math.min(commandTimeoutMs, VERSION_PROBE_TIMEOUT_MS));
                if (out != null && !out.isBlank() && !isRejected(out)) {
                    versionText = out;
                    break;
                }
            }
            if (model == null) {
                model = OltInfoParser.extractModel(vendor, versionText);
            }
            if (vendor == OltVendor.GENERIC) {
                vendor = detectVendor(versionText);
            }
        }
        if (OltVendorProfiles.of(vendor).wholeDevice()) {
            boards = List.of(OltVendorProfiles.of(vendor).buildWholeDevice(model));
            boardText = "";
        }
        if (boards.isEmpty()) {
            notes.add(boardText.isBlank()
                    ? commands.get(0) + " 无回显（命令超时或连接被断开）"
                    : commands.get(0) + " 回显无法解析：" + headLine(boardText));
        }

        snapshot.setVendor(vendor);
        snapshot.setCliStyle(cliStyleOf(vendor, null));
        snapshot.setModel(model);
        snapshot.setVersionText(versionText);
        snapshot.setBoardText(boardText);
        snapshot.setPrompt(prompt);
        if (!versionText.isBlank()) {
            snapshot.setSoftwareVersion(OltInfoParser.extractSoftwareVersion(versionText));
            snapshot.setPatchVersion(OltInfoParser.extractPatchVersion(versionText));
            snapshot.setUptime(OltInfoParser.extractUptime(versionText));
            snapshot.setMacAddress(OltInfoParser.extractMacAddress(versionText));
        }
        snapshot.setBoards(boards);
        snapshot.setSlotCount(OltVendorProfiles.of(vendor).slotCount(boardText, boards));
        if (lastError != null) {
            notes.add("命令异常：" + lastError.getMessage());
        }
        snapshot.setNotes(notes);
        snapshot.setTranscript(transcript.toString());
        return snapshot;
    }

    /** 配置视图内的一次性采集 */
    private void collectFromConfigView(List<OltBoard> boards, List<String> notes, OltSnapshot snapshot,
                                       Set<Section> sections, boolean enrichOntDetails) {
        int timeout = Math.min(commandTimeoutMs, 8000);
        if (!enterConfigView(timeout)) {
            notes.add("进不了配置视图（enable → config），温度/ONT/VLAN 未采到");
            return;
        }
        if (sections.contains(Section.TEMPERATURES)) {
            applyTemperatures(boards, timeout);
        }
        if (sections.contains(Section.PORT_STATES)) {
            collectBoardPortStates(boards, timeout);
        }
        boolean ontOk = !sections.contains(Section.ONTS) || collectOnts(boards, timeout, notes, enrichOntDetails);
        if (ontOk && sections.contains(Section.ONTS)) {
            fillOntProfilesFromConfig(boards, notes);
        }
        if (sections.contains(Section.OPTICS)) {
            collectPortOptics(boards, timeout);
        }
        if (sections.contains(Section.VLANS)) {
            collectVlans(boards, snapshot, timeout);
        }
        if (sections.contains(Section.TEMPLATES)) {
            collectTemplates(snapshot, timeout);
        }
        if (!ontOk) {
            notes.add("ONT 数量未采到：display ont info 0 <槽位> all 无有效回显");
        }
    }

    /** 按页签同步采集指定段 */
    public OltSnapshot collectSections(List<OltBoard> boards, Set<Section> sections) throws OltTelnetException {
        return collectSections(boards, sections, true);
    }

    /** 按页签同步采集指定段（不逐台补详情） */
    public OltSnapshot collectSections(List<OltBoard> boards, Set<Section> sections,
                                       boolean enrichOntDetails) throws OltTelnetException {
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();
        List<OltBoard> workBoards = boards == null ? List.of() : boards;
        prepareTerminal(vendorHint);
        collectFromConfigView(workBoards, notes, snapshot, sections, enrichOntDetails);
        snapshot.setBoards(workBoards);
        snapshot.setNotes(notes);
        snapshot.setTranscript(transcript.toString());
        return snapshot;
    }

    /** 补采 ONT 单台详情 */
    public OltSnapshot collectOntDetails(List<OltBoard> boards) throws OltTelnetException {
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();
        List<OltBoard> workBoards = boards == null ? List.of() : boards;
        prepareTerminal(vendorHint);
        int timeout = Math.min(commandTimeoutMs, 8000);
        if (!enterConfigView(timeout)) {
            notes.add("进不了配置视图（enable → config），ONT 详情未补采");
        } else {
            for (OltBoard board : workBoards) {
                if (board.getSlotNo() == null || board.getPorts() == null
                        || !OltInfoParser.BoardKind.SERVICE.name().equals(board.getBoardKind())) {
                    continue;
                }
                for (OltPort port : board.getPorts()) {
                    if (port.getPortNo() == null || port.getOnts() == null || port.getOnts().isEmpty()) {
                        continue;
                    }
                    enrichOntProfiles(board.getSlotNo(), port.getPortNo(), port.getOnts(), timeout, notes);
                }
            }
        }
        snapshot.setBoards(workBoards);
        snapshot.setNotes(notes);
        snapshot.setTranscript(transcript.toString());
        return snapshot;
    }

    /** 逐板采集端口状态 */
    private void collectBoardPortStates(List<OltBoard> boards, int timeout) {
        for (OltBoard board : boards) {
            if (board.getSlotNo() == null) {
                continue;
            }
            String text = runCommand("display board 0/" + board.getSlotNo(), timeout);
            Map<Integer, String[]> states = OltInfoParser.parseBoardPortStates(text);
            if (states.isEmpty()) {
                continue;
            }
            for (Map.Entry<Integer, String[]> entry : states.entrySet()) {
                String optic = entry.getValue()[0];
                applyPortState(board, entry.getKey(), optic,
                        !"absence".equalsIgnoreCase(optic), entry.getValue()[1]);
            }
        }
    }

    /** 端口状态挂到端口上 */
    private static void applyPortState(OltBoard board, int portNo, String optic, boolean present, String link) {
        List<OltPort> ports = board.getPorts();
        if (ports == null) {
            ports = new ArrayList<>();
            board.setPorts(ports);
        }
        OltPort port = findPort(ports, portNo);
        if (port == null) {
            port = new OltPort();
            port.setFrameNo(0);
            port.setSlotNo(board.getSlotNo());
            port.setPortNo(portNo);
            port.setPortName("P" + portNo);
            ports.add(port);
        }
        port.setOpticStatus(optic);
        port.setOpticalPresent(present);
        port.setLinkStatus(link);
        ports.sort(Comparator.comparing(item -> item.getPortNo() == null ? 0 : item.getPortNo()));
    }

    /** 逐口采光模块数值 */
    private void collectPortOptics(List<OltBoard> boards, int timeout) {
        for (OltBoard board : boards) {
            if (board.getSlotNo() == null
                    || !OltInfoParser.BoardKind.SERVICE.name().equals(board.getBoardKind())) {
                continue;
            }
            int count = portCount(board);
            if (count <= 0) {
                continue;
            }
            String ponType = "EPON".equalsIgnoreCase(board.getPortMode()) ? "epon" : "gpon";
            String enter = runQuietly("interface " + ponType + " 0/" + board.getSlotNo(), timeout);
            if (enter == null || !enter.contains("-if-")) {
                continue;
            }
            try {
                int portTimeout = Math.min(timeout, 3000);
                String allText = runCommand("display port state all", portTimeout);
                Map<Integer, OltPort> allOptics = OltInfoParser.parsePortStateOpticsAll(allText);
                if (!allOptics.isEmpty()) {
                    allOptics.forEach((portNo, optics) -> applyPortOptics(board, portNo, optics));
                    continue;
                }
                int missed = 0;
                List<OltPort> existingPorts = board.getPorts() != null ? board.getPorts() : List.of();
                for (int portNo = 0; portNo < count; portNo++) {
                    OltPort existing = findPort(existingPorts, portNo);
                    if (existing != null && existing.getOpticalPresent() != null
                            && !existing.getOpticalPresent()) {
                        continue;
                    }
                    OltPort optics = OltInfoParser.parsePortStateOptics(
                            runCommand("display port state " + portNo, portTimeout));
                    if (optics == null) {
                        if (++missed >= 2) {
                            break;
                        }
                        continue;
                    }
                    missed = 0;
                    applyPortOptics(board, portNo, optics);
                }
            } finally {
                runQuietly("quit", timeout);
            }
        }
    }

    /** 查询单端口光模块原始回显 */
    public String queryPortState(int slot, int portNo) {
        prepareTerminal(vendorHint);
        int timeout = Math.min(commandTimeoutMs, 8000);
        if (!enterConfigView(timeout)) {
            runQuietly("interface gpon 0/" + slot, timeout);
            String out = runCommand("display port state " + portNo, timeout);
            runQuietly("quit", timeout);
            return out;
        }
        String enter = runQuietly("interface gpon 0/" + slot, timeout);
        if (enter == null || !enter.contains("-if-")) {
            runQuietly("interface epon 0/" + slot, timeout);
        }
        try {
            return runCommand("display port state " + portNo, timeout);
        } finally {
            runQuietly("quit", timeout);
        }
    }

    /** 光模块数值挂到端口上 */
    private static void applyPortOptics(OltBoard board, int portNo, OltPort optics) {        List<OltPort> ports = board.getPorts();
        if (ports == null) {
            ports = new ArrayList<>();
            board.setPorts(ports);
        }
        OltPort port = findPort(ports, portNo);
        if (port == null) {
            port = new OltPort();
            port.setFrameNo(0);
            port.setSlotNo(board.getSlotNo());
            port.setPortNo(portNo);
            port.setPortName("P" + portNo);
            ports.add(port);
            ports.sort(Comparator.comparing(item -> item.getPortNo() == null ? 0 : item.getPortNo()));
        }
        port.setTxPower(optics.getTxPower());
        port.setOpticTemperature(optics.getOpticTemperature());
        port.setBiasCurrent(optics.getBiasCurrent());
        port.setSupplyVoltage(optics.getSupplyVoltage());
        port.setModuleStatus(optics.getModuleStatus());
        port.setLaserState(optics.getLaserState());
        port.setTxFault(optics.getTxFault());
        if (port.getRxPower() == null) {
            port.setRxPower(optics.getRxPower());
        }
        String moduleStatus = optics.getModuleStatus();
        if (port.getOpticalPresent() == null && moduleStatus != null) {
            String state = moduleStatus.toLowerCase(Locale.ROOT);
            port.setOpticStatus(state);
            port.setOpticalPresent(!(state.contains("offline") || state.contains("absent")));
        }
    }

    /** 采集 VLAN 信息 */
    private void collectVlans(List<OltBoard> boards, OltSnapshot snapshot, int timeout) {
        OltVlanInfo info = new OltVlanInfo();
        String configText = runCommand("display current-configuration section vlan-config", timeout);
        if (configText == null || OltInfoParser.looksUnsupported(configText) || !configText.contains("vlan")) {
            configText = runCommand("display current-configuration", timeout);
        }
        List<OltVlan> vlans;
        List<OltPortVlan> portVlans;
        if (configText != null && !configText.isBlank() && configText.contains("vlan")) {
            vlans = OltInfoParser.parseVlansFromConfig(configText);
            portVlans = OltInfoParser.parsePortVlansFromConfig(configText);
        } else {
            vlans = List.of();
            portVlans = List.of();
        }
        if (vlans.isEmpty()) {
            String vlanText = runCommand("display vlan all", timeout);
            vlans = OltInfoParser.parseVlans(vlanText);
            if (!vlans.isEmpty()) {
                int total = OltInfoParser.parseVlanTotal(vlanText);
                info.setTotal(total > 0 ? total : vlans.size());
            }
        } else {
            info.setTotal(vlans.size());
        }
        if (!vlans.isEmpty()) {
            info.setVlans(vlans);
        }
        if (portVlans.isEmpty()) {
            for (OltBoard board : boards) {
                if (board.getSlotNo() == null || !isUplinkKind(board.getBoardKind())) {
                    continue;
                }
                int count = portCount(board);
                int failed = 0;
                int portTimeout = Math.min(timeout, 3000);
                for (int portNo = 0; portNo < count; portNo++) {
                    String text = runCommand("display port vlan 0/" + board.getSlotNo() + "/" + portNo, portTimeout);
                    OltPortVlan portVlan = OltInfoParser.parsePortVlan(text);
                    if (portVlan == null) {
                        if (++failed >= 2) {
                            break;
                        }
                        continue;
                    }
                    failed = 0;
                    portVlan.setFrameNo(0);
                    portVlan.setSlotNo(board.getSlotNo());
                    portVlan.setPortNo(portNo);
                    portVlan.setIface("0/" + board.getSlotNo() + "/" + portNo);
                    info.getPortVlans().add(portVlan);
                }
            }
        } else {
            info.getPortVlans().addAll(portVlans);
        }
        info.setIpInterfaces(OltInfoParser.parseIpInterfaces(runCommand("display ip interface brief", timeout)));
        info.setStaticRoutes(OltInfoParser.parseStaticRoutes(runCommand("display ip routing-table", timeout)));
        if (!info.getVlans().isEmpty() || !info.getPortVlans().isEmpty()) {
            info.setCollectedAt(LocalDateTime.now().withNano(0).toString().replace('T', ' '));
            snapshot.setVlanInfo(info);
        }
    }

    /** 重读一条线路模板 */
    public OltLineProfile collectLineProfile(int profileId, String ponType) throws OltTelnetException {
        prepareTerminal(vendorHint);
        int timeout = commandTimeoutMs;
        String command = profile().lineProfileDetailCommand(ponType, profileId);
        if (command == null) {
            throw new OltTelnetException("这台设备的厂商还没接入线路模板查询命令");
        }
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了配置视图（enable → config），线路模板未重读");
        }
        try {
            String text = runCommand(command, timeout);
            return OltInfoParser.parseLineProfiles(text).stream()
                    .filter(profile -> Integer.valueOf(profileId).equals(profile.getProfileId()))
                    .findFirst()
                    .orElse(null);
        } finally {
            runQuietly("quit", timeout);
        }
    }

    /** 重读线路模板总表：GPON / EPON 两张都采（新建 / 删除模板后的定向刷新用，不整台重采） */
    public List<OltLineProfile> collectLineProfileList() throws OltTelnetException {
        prepareTerminal(vendorHint);
        int timeout = commandTimeoutMs;
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了配置视图（enable → config），线路模板列表未重读");
        }
        try {
            List<OltLineProfile> profiles = new ArrayList<>();
            for (String pon : List.of("gpon", "epon")) {
                for (OltLineProfile profile : OltInfoParser.parseLineProfiles(
                        runCommand("display ont-lineprofile " + pon + " all", timeout))) {
                    profile.setPonType(pon);
                    profiles.add(profile);
                }
            }
            profiles.removeIf(profile -> Integer.valueOf(0).equals(profile.getProfileId()));
            return profiles;
        } finally {
            runQuietly("quit", timeout);
        }
    }

    /** 清空业务板上的全部 ONT */
    public List<OltOntDeleteResult> deleteBoardOnts(List<Integer> wantedSlots) throws OltTelnetException {
        ensureRootView(Math.min(commandTimeoutMs, 5000));
        prepareTerminal(vendorHint, true);
        int timeout = commandTimeoutMs;
        List<OltBoard> boards = serviceBoards(wantedSlots, Math.min(timeout, BOARD_PROBE_TIMEOUT_MS));
        if (boards.isEmpty()) {
            throw new OltTelnetException("display board 0 里没有 PON 业务板，没地方可删");
        }
        OltVendorProfile profile = profile();
        if (profile.boardDetailCommand(0, 0) == null || profile.ontDeleteAllCommand(0) == null) {
            throw new OltTelnetException("这台设备的厂商还没接入 ONT 删除命令");
        }
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了配置视图（enable → config），ONT 未删除");
        }
        List<OltOntDeleteResult> results = new ArrayList<>();
        for (OltBoard board : boards) {
            results.add(deleteBoardOnts(board, profile, timeout));
        }
        return results;
    }

    /** 解析业务板列表 */
    private List<OltBoard> serviceBoards(List<Integer> wantedSlots, int timeout) {
        String text = "";
        for (String command : profile().boardCommands()) {
            String out = runCommand(command, timeout);
            if (out != null && !out.isBlank()) {
                text = out;
                break;
            }
        }
        List<OltBoard> service = new ArrayList<>();
        for (OltBoard board : OltInfoParser.parseBoards(text)) {
            if (board.getSlotNo() == null
                    || !OltInfoParser.BoardKind.SERVICE.name().equals(board.getBoardKind())) {
                continue;
            }
            if (wantedSlots != null && !wantedSlots.isEmpty() && !wantedSlots.contains(board.getSlotNo())) {
                continue;
            }
            service.add(board);
        }
        return service;
    }

    /** 删除一块板上的全部 ONT */
    private OltOntDeleteResult deleteBoardOnts(OltBoard board, OltVendorProfile profile, int timeout) {
        int slot = board.getSlotNo();
        String ponType = "EPON".equalsIgnoreCase(board.getPortMode()) ? "epon" : "gpon";
        OltOntDeleteResult result = new OltOntDeleteResult();
        result.setSlot(slot);
        result.setPonType(ponType);
        List<Integer> ports = portsWithOnts(runCommand(profile.boardDetailCommand(0, slot), timeout));
        if (ports.isEmpty()) {
            return result;
        }
        List<String> undoCommands = new ArrayList<>();
        for (int port : ports) {
            if (profile.servicePortListCommand(0, slot, port, null) == null) {
                continue;
            }
            String table = runCommand(profile.servicePortListCommand(0, slot, port, null), timeout);
            for (OltServicePort sp : OltInfoParser.parseServicePorts(table)) {
                if (sp.getIndex() != null) {
                    undoCommands.add(profile.servicePortDeleteCommand(sp.getIndex()));
                }
            }
        }
        for (String undo : undoCommands) {
            result.getOutputs().add(runCommand(undo, timeout));
        }
        if (!undoCommands.isEmpty()) {
            Logs.info(Logs.Module.OLT_COLLECT, "清板 {} 前先拆掉 {} 条业务虚口", slot, undoCommands.size());
        }
        runCommand(profile.ponViewCommand(0, slot, ponType), timeout);
        for (int port : ports) {
            String out = runInteractive(profile.ontDeleteAllCommand(port), List.of("y"));
            result.getOutputs().add(out == null ? "" : out);
            if (commandRejected(out) != null) {
                result.getFailedPorts().add(port);
            } else {
                result.getDeletedPorts().add(port);
            }
        }
        runQuietly("quit", timeout);
        return result;
    }

    /** 解析挂有 ONT 的端口 */
    private static List<Integer> portsWithOnts(String text) {
        Map<Integer, int[]> counts = OltInfoParser.parseOntCounts(text);
        List<Integer> ports = new ArrayList<>();
        counts.forEach((port, value) -> {
            if (value != null && value.length > 0 && value[0] > 0) {
                ports.add(port);
            }
        });
        ports.sort(Integer::compareTo);
        return ports;
    }

    /** 只重采 VLAN */
    public OltVlanInfo collectVlansOnly(List<OltBoard> boards) throws OltTelnetException {
        prepareTerminal(vendorHint);
        int timeout = Math.min(commandTimeoutMs, 8000);
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了配置视图（enable → config），VLAN 未重采");
        }
        OltSnapshot snapshot = new OltSnapshot();
        try {
            collectVlans(boards == null ? List.of() : boards, snapshot, timeout);
        } finally {
            runQuietly("quit", timeout);
        }
        return snapshot.getVlanInfo();
    }

    /** 采集模板信息 */
    private void collectTemplates(OltSnapshot snapshot, int timeout) {
        List<OltDbaProfile> dbaDetails = OltInfoParser.parseDbaDetails(
                runCommand("display dba-profile all detail", timeout));
        List<OltDbaProfile> dbaProfiles = OltInfoParser.parseDbaProfiles(
                runCommand("display dba-profile all", timeout));
        for (OltDbaProfile profile : dbaProfiles) {
            if (profile.getName() == null || profile.getName().isBlank()) {
                dbaDetails.stream()
                        .filter(detail -> detail.getProfileId() != null
                                && detail.getProfileId().equals(profile.getProfileId()))
                        .findFirst()
                        .ifPresent(detail -> profile.setName(detail.getName()));
            }
        }
        if (dbaProfiles.isEmpty() && !dbaDetails.isEmpty()) {
            dbaProfiles = dbaDetails;
        }
        // GPON / EPON 两种线路模板都采：设备可能两类板卡混插，只查一种会漏
        List<OltLineProfile> lineProfiles = new ArrayList<>();
        for (String pon : List.of("gpon", "epon")) {
            for (OltLineProfile profile : OltInfoParser.parseLineProfiles(
                    runCommand("display ont-lineprofile " + pon + " all", timeout))) {
                profile.setPonType(pon);
                lineProfiles.add(profile);
            }
        }
        lineProfiles.removeIf(profile -> Integer.valueOf(0).equals(profile.getProfileId()));
        for (OltLineProfile profile : lineProfiles) {
            if (profile.getProfileId() == null) {
                continue;
            }
            String detail = runCommand("display ont-lineprofile " + profile.getPonType()
                    + " profile-id " + profile.getProfileId(), timeout);
            OltInfoParser.parseLineProfiles(detail).stream()
                    .filter(d -> profile.getProfileId().equals(d.getProfileId()))
                    .findFirst()
                    .ifPresent(d -> mergeLineProfile(profile, d));
        }
        String configScript = runCommand("display current-configuration section global-config", timeout);
        if (OltInfoParser.looksUnsupported(configScript)) {
            configScript = runCommand("display current-configuration", timeout);
        }
        List<OltLineProfile> configLineProfiles = configScript.isBlank()
                ? new ArrayList<>() : OltInfoParser.parseLineProfiles(configScript);
        for (OltLineProfile fromConfig : configLineProfiles) {
            if (fromConfig.getPonType() == null || fromConfig.getPonType().isBlank()) {
                fromConfig.setPonType(linePonTypeOf(configScript, fromConfig.getProfileId()));
            }
        }
        for (OltLineProfile profile : lineProfiles) {
            if (profile.getProfileId() == null) {
                continue;
            }
            configLineProfiles.stream()
                    .filter(c -> profile.getProfileId().equals(c.getProfileId())
                            && Objects.equals(profile.getPonType(), c.getPonType()))
                    .findFirst()
                    .ifPresent(c -> mergeLineProfileFromConfig(profile, c));
        }
        for (OltLineProfile fromConfig : configLineProfiles) {
            Integer id = fromConfig.getProfileId();
            if (id == null || id == 0 || fromConfig.getName() == null) {
                continue;
            }
            boolean exists = lineProfiles.stream().anyMatch(p -> id.equals(p.getProfileId())
                    && Objects.equals(fromConfig.getPonType(), p.getPonType()));
            if (!exists) {
                lineProfiles.add(fromConfig);
            }
        }
        List<OltServicePort> servicePorts = OltInfoParser.parseServicePortsFromConfig(
                runCommand("display current-configuration section service-port", timeout));
        if (servicePorts.isEmpty()) {
            servicePorts = OltInfoParser.parseServicePorts(
                    runCommand("display service-port all", timeout));
        }
        // 业务模板同样 GPON / EPON 都采
        List<OltSrvProfile> srvProfiles = new ArrayList<>();
        for (String pon : List.of("gpon", "epon")) {
            for (OltSrvProfile profile : OltInfoParser.parseSrvProfiles(
                    runCommand("display ont-srvprofile " + pon + " all", timeout))) {
                profile.setPonType(pon);
                srvProfiles.add(profile);
            }
        }
        for (OltSrvProfile profile : srvProfiles) {
            if (profile.getProfileId() == null) {
                continue;
            }
            String detail = runCommand("display ont-srvprofile " + profile.getPonType()
                    + " profile-id " + profile.getProfileId(), timeout);
            OltInfoParser.parseSrvProfiles(detail).stream()
                    .filter(d -> profile.getProfileId().equals(d.getProfileId()))
                    .findFirst()
                    .ifPresent(d -> mergeSrvProfile(profile, d));
        }
        for (OltSrvProfile from : configScript.isBlank()
                ? new ArrayList<OltSrvProfile>() : OltInfoParser.parseSrvProfiles(configScript)) {
            if (from.getProfileId() == null) {
                continue;
            }
            if (from.getPonType() == null || from.getPonType().isBlank()) {
                from.setPonType(srvPonTypeOf(configScript, from.getProfileId()));
            }
            OltSrvProfile target = null;
            for (OltSrvProfile p : srvProfiles) {
                if (from.getProfileId().equals(p.getProfileId())
                        && Objects.equals(from.getPonType(), p.getPonType())) {
                    target = p;
                    break;
                }
            }
            if (target == null) {
                target = from;
                srvProfiles.add(from);
            }
            if (target.getPotsPorts() == null) {
                target.setPotsPorts(from.getPotsPorts());
            }
            if (target.getEthPorts() == null) {
                target.setEthPorts(from.getEthPorts());
            }
            if ((target.getName() == null || target.getName().isBlank()) && from.getName() != null) {
                target.setName(from.getName());
            }
        }
        List<OltTr069Profile> tr069Profiles = OltInfoParser.parseTr069Profiles(
                runCommand("display ont tr069-server-profile all", timeout));
        if (tr069Profiles.isEmpty()) {
            tr069Profiles = OltInfoParser.parseTr069Profiles(
                    runCommand("display ont tr069-server-profile", timeout));
        }
        // display all 只有 ID/名称/绑定数（华为），ACS 地址与账号要逐条查详情补齐
        fillTr069ProfileDetails(tr069Profiles, timeout);
        if (tr069Profiles.isEmpty() && !configScript.isBlank()) {
            tr069Profiles = OltInfoParser.parseTr069Profiles(configScript);
        }
        List<OltTrafficTable> trafficTables = OltInfoParser.parseTrafficTables(
                runCommand("display traffic table ip all", timeout));
        if (trafficTables.isEmpty()) {
            trafficTables = OltInfoParser.parseTrafficTables(
                    runCommand("display traffic table all", timeout));
        }
        if (dbaProfiles.isEmpty() && lineProfiles.isEmpty()
                && servicePorts.isEmpty() && srvProfiles.isEmpty()
                && tr069Profiles.isEmpty() && trafficTables.isEmpty()) {
            return;
        }
        OltTemplateInfo info = new OltTemplateInfo();
        info.setDbaProfiles(dbaProfiles);
        info.setLineProfiles(lineProfiles);
        info.setServicePorts(servicePorts);
        info.setSrvProfiles(srvProfiles);
        info.setTr069Profiles(tr069Profiles);
        info.setTrafficTables(trafficTables);
        info.stamp();
        snapshot.setTemplateInfo(info);
    }

    /** 从配置脚本认线路模板的 PON 类型（ont-lineprofile gpon/epon profile-id X），认不出按 gpon */
    private static String linePonTypeOf(String configScript, Integer profileId) {
        if (configScript != null && !configScript.isBlank() && profileId != null) {
            Matcher matcher = Pattern.compile(
                    "(?i)ont-lineprofile\\s+(gpon|epon)\\s+profile-id\\s+" + profileId + "\\b")
                    .matcher(configScript);
            if (matcher.find()) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return "gpon";
    }

    /** 从配置脚本认业务模板的 PON 类型（ont-srvprofile gpon/epon profile-id X），认不出按 gpon */
    private static String srvPonTypeOf(String configScript, Integer profileId) {
        if (configScript != null && !configScript.isBlank() && profileId != null) {
            Matcher matcher = Pattern.compile(
                    "(?i)ont-srvprofile\\s+(gpon|epon)\\s+profile-id\\s+" + profileId + "\\b")
                    .matcher(configScript);
            if (matcher.find()) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return "gpon";
    }

    /**
     * TR-069 模板列表只给 ID/名称/绑定数时（display all 无 ACS 地址列），
     * 逐条查 {@code display ont tr069-server-profile profile-id X} 补地址与账号。
     * 详情命令设备不认时回显是报错，解析不出就维持原样，不影响列表。
     */
    private void fillTr069ProfileDetails(List<OltTr069Profile> profiles, int timeout) {
        if (profiles == null || profiles.isEmpty()) {
            return;
        }
        int limit = Math.min(profiles.size(), 32);
        for (int i = 0; i < limit; i++) {
            OltTr069Profile profile = profiles.get(i);
            if (profile.getProfileId() == null
                    || (profile.getUrl() != null && !profile.getUrl().isBlank())) {
                continue;
            }
            OltInfoParser.mergeTr069ProfileDetail(
                    runCommand("display ont tr069-server-profile profile-id "
                            + profile.getProfileId(), timeout),
                    profiles);
        }
    }

    /** 合并线路模板明细 */
    private void mergeLineProfile(OltLineProfile target, OltLineProfile detail) {
        if (detail.getTcontCount() != null) {
            target.setTcontCount(detail.getTcontCount());
        }
        if (detail.getGemportCount() != null) {
            target.setGemportCount(detail.getGemportCount());
        }
        if (detail.getTconts() != null && !detail.getTconts().isEmpty()) {
            target.setTconts(detail.getTconts());
        }
        if (detail.getGemports() != null && !detail.getGemports().isEmpty()) {
            target.setGemports(detail.getGemports());
        }
    }

    /** 从配置脚本补漏线路模板 */
    private void mergeLineProfileFromConfig(OltLineProfile target, OltLineProfile from) {
        if ((target.getTconts() == null || target.getTconts().isEmpty())
                && from.getTconts() != null && !from.getTconts().isEmpty()) {
            target.setTconts(from.getTconts());
            if (target.getTcontCount() == null) {
                target.setTcontCount(from.getTconts().size());
            }
        }
        if (from.getGemports() != null && !from.getGemports().isEmpty()) {
            if (target.getGemports() == null || target.getGemports().isEmpty()) {
                target.setGemports(from.getGemports());
            } else {
                for (OltGemport configGem : from.getGemports()) {
                    if (configGem.getGemportId() == null) {
                        continue;
                    }
                    target.getGemports().stream()
                            .filter(g -> configGem.getGemportId().equals(g.getGemportId()))
                            .findFirst()
                            .ifPresent(existing -> {
                                if (existing.getTcontId() == null) {
                                    existing.setTcontId(configGem.getTcontId());
                                }
                                if (existing.getMappingId() == null) {
                                    existing.setMappingId(configGem.getMappingId());
                                }
                                if (existing.getVlan() == null || existing.getVlan().isBlank()) {
                                    existing.setVlan(configGem.getVlan());
                                }
                            });
                }
            }
            if (target.getGemportCount() == null && !target.getGemports().isEmpty()) {
                target.setGemportCount(target.getGemports().size());
            }
        }
    }

    /** 合并能力模板明细 */
    private void mergeSrvProfile(OltSrvProfile target, OltSrvProfile detail) {
        if (detail.getEthPorts() != null) {
            target.setEthPorts(detail.getEthPorts());
        }
        if (detail.getPotsPorts() != null) {
            target.setPotsPorts(detail.getPotsPorts());
        }
        if (detail.getItems() != null && !detail.getItems().isEmpty()) {
            target.setItems(detail.getItems());
        }
        if (detail.getTr069() != null && !detail.getTr069().isEmpty()) {
            target.setTr069(detail.getTr069());
        }
    }

    /** 判断是否上行口 */
    private static boolean isUplinkKind(String boardKind) {
        return OltInfoParser.BoardKind.UPLINK.name().equals(boardKind)
                || OltInfoParser.BoardKind.CONTROL.name().equals(boardKind);
    }

    /** 进配置视图 */
    private boolean enterConfigView(int timeout) {
        if (view == CliView.CONFIG) {
            return true;
        }
        if (view == CliView.SUB && !ensureRootView(timeout)) {
            return false;
        }
        if (view == CliView.UNKNOWN && !probeView(timeout)) {
            return false;
        }
        if (view == CliView.CONFIG) {
            return true;
        }
        if (view != CliView.ROOT) {
            return false;
        }
        boolean ok = profile().enterConfigView(this::runQuietly, timeout);
        if (ok) {
            view = CliView.CONFIG;
        }
        return ok;
    }

    /** 获取当前厂商 profile */
    private OltVendorProfile profile() {
        return OltVendorProfiles.of(vendorHint);
    }

    /** 查询单条 ONT 详情原始回显 */
    public String queryOntDetail(int slot, int port, int ontId, String subcommand) {
        prepareTerminal(vendorHint);
        int timeout = commandTimeoutMs;
        if (!enterConfigView(timeout)) {
            runCommand("interface gpon 0/" + slot, timeout);
            String out = runCommand("display ont " + subcommand + " " + port + " " + ontId, timeout);
            runQuietly("quit", timeout);
            return out;
        }
        String fullCoordinates = "display ont " + subcommand + " 0 " + slot + " " + port + " " + ontId;
        String portView = "display ont " + subcommand + " " + port + " " + ontId;
        String out;
        if ("capability".equals(subcommand)) {
            out = runCommand("display ont " + subcommand + " 0/" + slot + " " + port + " " + ontId, timeout);
            if (OltInfoParser.looksUnsupported(out)) {
                runCommand("interface gpon 0/" + slot, timeout);
                out = runCommand(portView, timeout);
                runQuietly("quit", timeout);
            }
        } else if ("info".equals(subcommand)) {
            out = runCommand(fullCoordinates, timeout);
            if (OltInfoParser.looksUnsupported(out)) {
                runCommand("interface gpon 0/" + slot, timeout);
                out = runCommand(portView, timeout);
                runQuietly("quit", timeout);
            }
        } else {
            runCommand("interface gpon 0/" + slot, timeout);
            out = runCommand(portView, timeout);
            if (OltInfoParser.looksUnsupported(out)) {
                runQuietly("quit", timeout);
                out = runCommand(fullCoordinates, timeout);
            } else {
                runQuietly("quit", timeout);
            }
        }
        runQuietly("quit", timeout);
        return out;
    }

    /** 查询单条 ONT 的配置摘录、业务流表格与详情 */
    public String[] queryOntConfigAndServicePorts(int slot, int port, int ontId) {
        prepareTerminal(vendorHint);
        int timeout = commandTimeoutMs;
        if (!enterConfigView(timeout)) {
            throw new IllegalStateException("进不了 config 视图，查不到 ONT 配置");
        }
        String config = runCommand("display current-configuration ont 0/" + slot + "/" + port + " " + ontId, timeout);
        String ports = runServicePortQuery(slot, port, ontId, timeout);
        String info = runCommand("display ont info 0 " + slot + " " + port + " " + ontId, timeout);
        return new String[]{config, ports, info};
    }

    /** 查询单条 ONT 的业务流表格 */
    public String queryServicePorts(int slot, int port, int ontId) {
        prepareTerminal(vendorHint);
        int timeout = commandTimeoutMs;
        if (!enterConfigView(timeout)) {
            throw new IllegalStateException("进不了 config 视图，查不到业务流");
        }
        return runServicePortQuery(slot, port, ontId, timeout);
    }

    /** 查询链路聚合组 */
    public String queryLinkAggregations() throws OltTelnetException {
        prepareTerminal(vendorHint);
        int timeout = commandTimeoutMs;
        OltVendorProfile profile = profile();
        String command = profile.linkAggregationQueryCommand("all");
        if (command == null) {
            throw new OltTelnetException("该厂商还没接入链路聚合查询");
        }
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了 config 视图，查不到链路聚合");
        }
        return runCommand(command, timeout);
    }

    /** 删除链路聚合组 */
    public String deleteLinkAggregation(String masterFs) throws OltTelnetException {
        prepareTerminal(vendorHint, true);
        int timeout = commandTimeoutMs;
        OltVendorProfile profile = profile();
        String command = profile.linkAggregationDeleteCommand(masterFs);
        if (command == null) {
            throw new OltTelnetException("该厂商还没接入链路聚合删除命令");
        }
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了 config 视图，聚合组未删除");
        }
        String out = runInteractive(command, List.of("y"));
        String text = out == null ? "" : out;
        if (text.contains("Parameter error") || text.contains("参数错误")
                || text.contains("Failure:")) {
            throw new OltTelnetException("设备拒绝删除聚合组 " + masterFs + "，回显详见操作记录");
        }
        return text;
    }

    /** 整机重启 */
    public String reboot() throws OltTelnetException {
        ensureRootView(Math.min(commandTimeoutMs, 5000));
        prepareTerminal(vendorHint, true);
        int timeout = commandTimeoutMs;
        OltVendorProfile profile = profile();
        if (profile.rebootCommand() == null) {
            throw new OltTelnetException("该厂商还没接入重启命令");
        }
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了配置视图（enable → config），未下发重启");
        }
        String out = runInteractive(profile.rebootCommand(), List.of("y"));
        String text = out == null ? "" : out;
        if (text.contains("Parameter error") || text.contains("参数错误")) {
            throw new OltTelnetException("设备拒绝重启命令 " + profile.rebootCommand()
                    + "，回显详见操作记录");
        }
        Logs.info(Logs.Module.OLT_COLLECT, "已下发整机重启命令", "命令", profile.rebootCommand());
        return text;
    }

    /** 复位单个 ONT */
    public String resetOnt(int slot, int port, int ontId) throws OltTelnetException {
        prepareTerminal(vendorHint, true);
        int timeout = commandTimeoutMs;
        OltVendorProfile profile = profile();
        if (profile.ontResetCommand(0, 0) == null) {
            throw new OltTelnetException("该厂商还没接入 ONU 复位命令");
        }
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了配置视图（enable → config），未复位");
        }
        runCommand(profile.ponViewCommand(0, slot), timeout);
        String out = runInteractive(profile.ontResetCommand(port, ontId), List.of("y"));
        String text = out == null ? "" : out;
        runQuietly("quit", timeout);
        if (text.contains("Parameter error") || text.contains("参数错误")) {
            throw new OltTelnetException("设备拒绝复位 ONU 0/" + slot + "/" + port
                    + " ont " + ontId + "，回显详见操作记录");
        }
        Logs.info(Logs.Module.OLT_COLLECT, "已复位单个 ONU", "位置", "0/" + slot + "/" + port, "ontId", ontId);
        return text;
    }

    /** 复位整条 PON 口的全部 ONU：{@code ont reset <port> all}，追问 y/n 答 y */
    public String resetPortOnts(int slot, int port) throws OltTelnetException {
        prepareTerminal(vendorHint, true);
        int timeout = commandTimeoutMs;
        OltVendorProfile profile = profile();
        if (profile.ontResetPortCommand(0) == null) {
            throw new OltTelnetException("该厂商还没接入 ONU 复位命令");
        }
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了配置视图（enable → config），未复位");
        }
        runCommand(profile.ponViewCommand(0, slot), timeout);
        String out = runInteractive(profile.ontResetPortCommand(port), List.of("y"));
        String text = out == null ? "" : out;
        runQuietly("quit", timeout);
        if (text.contains("Parameter error") || text.contains("参数错误")) {
            throw new OltTelnetException("设备拒绝复位 PON 口 0/" + slot + "/" + port + "，回显详见操作记录");
        }
        Logs.info(Logs.Module.OLT_COLLECT, "已复位整条 PON 口的 ONU", "位置", "0/" + slot + "/" + port);
        return text;
    }

    /** 复位整块板卡的全部 ONU */
    public List<String> resetBoardOnts(int slot, List<Integer> portNos) throws OltTelnetException {
        prepareTerminal(vendorHint, true);
        int timeout = commandTimeoutMs;
        OltVendorProfile profile = profile();
        if (profile.ontResetPortCommand(0) == null) {
            throw new OltTelnetException("该厂商还没接入 ONU 复位命令");
        }
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了配置视图（enable → config），未复位");
        }
        runCommand(profile.ponViewCommand(0, slot), timeout);
        List<String> outputs = new ArrayList<>();
        for (Integer portNo : portNos) {
            if (portNo == null) {
                continue;
            }
            String out = runInteractive(profile.ontResetPortCommand(portNo), List.of("y"));
            outputs.add(out == null ? "" : out);
        }
        runQuietly("quit", timeout);
        Logs.info(Logs.Module.OLT_COLLECT, "已复位整块板卡的 ONU", "位置", "0/" + slot,
                "PON 口数", portNos.size());
        return outputs;
    }

    /** 删除单个 ONT */
    public OltOntDeleteResult deleteOnt(int slot, int port, int ontId) throws OltTelnetException {
        ensureRootView(Math.min(commandTimeoutMs, 5000));
        prepareTerminal(vendorHint, true);
        int timeout = commandTimeoutMs;
        OltVendorProfile profile = profile();
        if (profile.ontDeleteCommand(0, 0) == null) {
            throw new OltTelnetException("这台设备的厂商还没接入单条 ONT 删除命令");
        }
        if (!enterConfigView(timeout)) {
            throw new OltTelnetException("进不了配置视图（enable → config），ONT 未删除");
        }
        OltOntDeleteResult result = new OltOntDeleteResult();
        result.setSlot(slot);
        if (profile.servicePortListCommand(0, slot, port, ontId) != null) {
            String table = runCommand(profile.servicePortListCommand(0, slot, port, ontId), timeout);
            result.getOutputs().add(table == null ? "" : table);
            for (OltServicePort sp : OltInfoParser.parseServicePorts(table)) {
                if (!Objects.equals(sp.getOntId(), ontId)) {
                    continue;
                }
                String command = null;
                if (sp.getGemport() != null && sp.getUserVlan() != null) {
                    command = profile.servicePortDeleteByPortCommand(
                            0, slot, port, ontId, sp.getGemport(), sp.getUserVlan());
                }
                if (command == null && sp.getIndex() != null) {
                    command = profile.servicePortDeleteCommand(sp.getIndex());
                }
                if (command != null) {
                    String out = runCommand(command, timeout);
                    result.getOutputs().add(out == null ? "" : out);
                }
            }
        }
        runCommand(profile.ponViewCommand(0, slot), timeout);
        String out = runCommand(profile.ontDeleteCommand(port, ontId), timeout);
        result.getOutputs().add(out == null ? "" : out);
        if (commandRejected(out) != null) {
            result.getFailedPorts().add(port);
        } else {
            result.getDeletedPorts().add(port);
        }
        runQuietly("quit", timeout);
        Logs.info(Logs.Module.OLT_REG, "删除单个 ONT", "位置", "0/" + slot + "/" + port,
                "ontId", ontId, "结果", result.getFailedPorts().isEmpty() ? "成功" : "被设备拒绝");
        return result;
    }

    private String runServicePortQuery(int slot, int port, int ontId, int timeout) {
        String ports = runCommand("display service-port port 0/" + slot + "/" + port + " ont " + ontId, timeout);
        if (OltInfoParser.looksUnsupported(ports) || OltInfoParser.parseServicePorts(ports).isEmpty()) {
            ports = runCommand("display service-port port 0/" + slot + "/" + port, timeout);
        }
        return ports;
    }

    /** 在 config 视图下逐条下发配置命令（DBA 模板增删这类写操作） */
    public List<String> sendConfigCommands(List<String> commands) {
        return sendConfigCommands(commands, false);
    }

    /** 批量下发配置 */
    public List<String> sendConfigCommands(List<String> commands, boolean tolerateExisting) {
        prepareTerminal(vendorHint);
        if (!enterConfigView(commandTimeoutMs)) {
            throw new IllegalStateException("进不了 config 视图，无法下发配置");
        }
        List<String> outputs = new ArrayList<>();
        for (List<String> group : groupWithAnswers(commands)) {
            String command = group.get(0);
            List<String> answers = group.subList(1, group.size());
            String out = isSaveCommand(command) ? runSave()
                    : answers.isEmpty() ? runCommand(command, commandTimeoutMs) : runInteractive(command, answers);
            outputs.add(out);
            String reason = commandRejected(out);
            if (reason == null) {
                continue;
            }
            if (tolerateExisting && ALREADY_EXISTS.matcher(reason).find()) {
                Logs.info(Logs.Module.OLT_COLLECT, "设备上已有该配置，跳过「{}」：{}", command, reason);
                continue;
            }
            throw new IllegalStateException("OLT 拒绝命令「" + command + "」：" + reason);
        }
        return outputs;
    }

    /**
     * 宽松批量下发：某条被设备拒绝不中断，剩下的（含 commit/quit 这类收尾）照发，
     * 全部发完再把首个拒绝抛出来。
     *
     * <p>给「建模板视图 + tcont/gem + commit」这类前后依赖的命令组用——
     * 严格模式中间一条被拒就抛异常，commit 执行不到，华为模板视图里
     * 没 commit 的配置全部不生效，最后只剩一个模板壳。</p>
     */
    public List<String> sendConfigCommandsRelaxed(List<String> commands) {
        prepareTerminal(vendorHint);
        if (!enterConfigView(commandTimeoutMs)) {
            throw new IllegalStateException("进不了 config 视图，无法下发配置");
        }
        List<String> outputs = new ArrayList<>();
        String firstReason = null;
        String firstRejected = null;
        int rejected = 0;
        for (List<String> group : groupWithAnswers(commands)) {
            String command = group.get(0);
            List<String> answers = group.subList(1, group.size());
            String out = isSaveCommand(command) ? runSave()
                    : answers.isEmpty() ? runCommand(command, commandTimeoutMs) : runInteractive(command, answers);
            outputs.add(out);
            String reason = commandRejected(out);
            if (reason == null) {
                continue;
            }
            rejected++;
            Logs.info(Logs.Module.OLT_COLLECT, "设备拒绝命令「{}」：{}", command, reason);
            if (firstReason == null) {
                firstReason = reason;
                firstRejected = command;
            }
        }
        if (firstReason != null) {
            throw new IllegalStateException("OLT 拒绝命令「" + firstRejected + "」：" + firstReason
                    + "（共 " + rejected + " 条被拒，其余命令与 commit 已继续执行，请同步后核对模板数据）");
        }
        return outputs;
    }

    /** 存盘 */
    private String runSave() {
        long start = System.currentTimeMillis();
        saving = true;
        StringBuilder whole = new StringBuilder();
        try {
            client.resetBuffer();
            client.sendLine("save");
            String out = client.readUntilAny(INTERACTIVE_STOPS, Math.min(commandTimeoutMs, 8000));
            whole.append(out);
            if (isAsking(out) && SAVE_BRACE_PROMPT.matcher(tailOf(out)).find()) {
                for (int round = 0; round < 2 && isAsking(out)
                        && SAVE_BRACE_PROMPT.matcher(tailOf(out)).find(); round++) {
                    transcript.append("[应答] <回车>\n");
                    client.resetBuffer();
                    client.sendLine("");
                    out = client.readUntilAny(INTERACTIVE_STOPS, Math.min(commandTimeoutMs, 8000));
                    whole.append(out);
                }
                if (!SHELL_PROMPT.matcher(tailOf(out)).find()) {
                    out = client.readUntilAny(List.of(SHELL_PROMPT), SAVE_TIMEOUT_MS);
                    whole.append(out);
                }
            } else if (isAsking(out)) {
                transcript.append("[应答] y\n");
                client.resetBuffer();
                client.sendLine("y");
                out = client.readUntilAny(List.of(SHELL_PROMPT), SAVE_TIMEOUT_MS);
                whole.append(out);
            } else if (!SHELL_PROMPT.matcher(tailOf(out)).find()) {
                out = client.readUntilAny(List.of(SHELL_PROMPT), SAVE_TIMEOUT_MS);
                whole.append(out);
            }
            view = viewOf(out, view);
            if (USERNAME_PROMPT.matcher(out).find() || PASSWORD_PROMPT.matcher(out).find()) {
                sessionLost = true;
                view = CliView.UNKNOWN;
            }
            transcript.append("\n$ save ").append(cost(start)).append('\n').append(whole).append('\n');
            return whole.toString();
        } catch (IOException e) {
            String partial = client.getText();
            lastError = e;
            Logs.info(Logs.Module.OLT_COLLECT, "OLT 存盘执行失败: {}", e.getMessage());
            transcript.append("\n$ save ").append(cost(start))
                    .append("\n[失败] ").append(e.getMessage()).append('\n').append(partial).append('\n');
            drain();
            return whole.length() > 0 ? whole.toString() : partial;
        } finally {
            saving = false;
            saveFinishedAt = System.currentTimeMillis();
        }
    }

    /** 回显末尾一小段（提示符判断只看尾部） */
    private static String tailOf(String text) {
        if (text == null || text.length() <= 200) {
            return text == null ? "" : text;
        }
        return text.substring(text.length() - 200);
    }

    /** 是否是存盘命令（各厂商都是 save） */
    private static boolean isSaveCommand(String command) {
        return command != null && command.trim().equalsIgnoreCase("save");
    }

    /** 这台设备此刻是否正在写 flash（保存配置） */
    public boolean isSaving() {
        return saving;
    }

    /** 是否处于保存缓冲期 */
    public boolean inSaveGrace() {
        return saveFinishedAt > 0 && System.currentTimeMillis() - saveFinishedAt < SAVE_GRACE_MS;
    }

    /** 下发交互式命令并逐个应答 */
    public String runInteractive(String command, List<String> answers) {
        StringBuilder whole = new StringBuilder();
        try {
            client.resetBuffer();
            client.sendLine(command);
            String out = client.readUntilAny(INTERACTIVE_STOPS, commandTimeoutMs);
            whole.append(out);
            for (String answer : answers) {
                if (!isAsking(out)) {
                    break;
                }
                transcript.append("[应答] ").append(answer).append('\n');
                client.resetBuffer();
                client.sendLine(answer);
                out = client.readUntilAny(INTERACTIVE_STOPS, commandTimeoutMs);
                whole.append(out);
            }
            view = viewOf(out, view);
            if (USERNAME_PROMPT.matcher(out).find() || PASSWORD_PROMPT.matcher(out).find()) {
                sessionLost = true;
                view = CliView.UNKNOWN;
            }
            transcript.append("\n$ ").append(command).append('\n').append(whole).append('\n');
            return whole.toString();
        } catch (IOException e) {
            String partial = client.getText();
            lastError = e;
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT 交互命令执行失败: {} - {}", command, e.getMessage());
            transcript.append("\n$ ").append(command)
                    .append("\n[失败] ").append(e.getMessage()).append('\n')
                    .append(partial).append('\n');
            drain();
            return whole.length() > 0 ? whole.toString() : partial;
        }
    }

    /** 把命令与应答切成组 */
    private static List<List<String>> groupWithAnswers(List<String> commands) {
        List<List<String>> groups = new ArrayList<>();
        if (commands == null) {
            return groups;
        }
        for (String raw : commands) {
            if (raw != null && raw.startsWith(ANSWER_PREFIX)) {
                if (!groups.isEmpty()) {
                    groups.get(groups.size() - 1).add(raw.substring(ANSWER_PREFIX.length()));
                }
                continue;
            }
            List<String> group = new ArrayList<>();
            group.add(raw);
            groups.add(group);
        }
        return groups;
    }

    /** 设备现在是不是停在某个追问上等输入 */
    private static boolean isAsking(String out) {
        if (out == null || out.isBlank()) {
            return false;
        }
        String tail = out.length() > 120 ? out.substring(out.length() - 120) : out;
        if (SHELL_PROMPT.matcher(tail).find()) {
            return false;
        }
        return INTERACTIVE_ASK.matcher(tail).find();
    }

    /** 只读查询：进 config 视图后逐条执行 show 类命令，返回每条的回显 */
    public List<String> queryCommands(List<String> commands) {
        prepareTerminal(vendorHint);
        if (!enterConfigView(commandTimeoutMs)) {
            throw new IllegalStateException("进不了 config 视图，无法查询");
        }
        List<String> outputs = new ArrayList<>();
        for (String command : commands) {
            outputs.add(runCommand(command, commandTimeoutMs));
        }
        return outputs;
    }

    /** 拒绝命令回显特征 */
    private static final Pattern REJECT_ROW = Pattern.compile(
            "(?im)^.*(\\berror\\b|\\bfail(?:ed|ure)?\\b|错误[:：]?|无效|非法|not\\s+exist|exsits|不存在).*$");

    /** 已存在配置的回显特征 */
    private static final Pattern ALREADY_EXISTS = Pattern.compile(
            "(?i)(has\\s+existed|has\\s+been\\s+\\w+|already\\s+\\w+|repeat\\w*|exsits|已存在|已经存在)");

    private static final Pattern ZERO_FAILURES = Pattern.compile("(?i)\\bfailed\\s*[:：]?\\s*0\\b");

    private String commandRejected(String out) {
        if (out == null || out.isBlank()) {
            return null;
        }
        Matcher matcher = REJECT_ROW.matcher(ZERO_FAILURES.matcher(out).replaceAll(" "));
        while (matcher.find()) {
            String row = matcher.group().trim();
            if (!row.startsWith("$") && !row.contains("(config")) {
                return row;
            }
        }
        return null;
    }


    /** 采集 ONT 数量与明细 */
    private boolean collectOnts(List<OltBoard> boards, int timeout, List<String> notes,
                                boolean enrichOntDetails) {
        boolean any = false;
        for (OltBoard board : boards) {
            if (board.getSlotNo() == null
                    || !OltInfoParser.BoardKind.SERVICE.name().equals(board.getBoardKind())) {
                continue;
            }
            String text = runCommand(ontCommand(board.getSlotNo()), timeout);
            Map<Integer, int[]> counts = OltInfoParser.parseOntCounts(text);
            Map<Integer, List<OltOnt>> details = OltInfoParser.parseOntDetails(text);
            if (counts.isEmpty() && details.isEmpty()) {
                continue;
            }
            if (details.isEmpty() && !counts.isEmpty()) {
                notes.add("0/" + board.getSlotNo() + " ONT 明细未解析出（汇总行认到 "
                        + counts.size() + " 个口，数量正常）：明细表回显格式可能变了，明细列沿用上次数据");
            }
            int total = portCount(board);
            for (Integer portNo : counts.keySet()) {
                if (portNo != null && portNo >= total) {
                    total = portNo + 1;
                }
            }
            for (Integer portNo : details.keySet()) {
                if (portNo != null && portNo >= total) {
                    total = portNo + 1;
                }
            }
            for (int portNo = 0; portNo < total; portNo++) {
                List<OltOnt> list = details.get(portNo);
                int[] stat = counts.get(portNo);
                if (stat == null && list != null) {
                    int online = 0;
                    for (OltOnt ont : list) {
                        if (Boolean.TRUE.equals(ont.getOnline())) {
                            online++;
                        }
                    }
                    stat = new int[]{list.size(), online};
                }
                applyOntCount(board, portNo, stat == null ? new int[]{0, 0} : stat, list);
                if (enrichOntDetails) {
                    enrichOntProfiles(board.getSlotNo(), portNo, list, timeout, notes);
                }
                any = true;
            }
        }
        return any;
    }

    /** 补采 ONT 的 MAC / 管理 IP */
    private void enrichOntProfiles(Integer slotNo, int portNo, List<OltOnt> onts, int timeout,
                                   List<String> notes) {
        if (slotNo == null || onts == null || onts.isEmpty()) {
            return;
        }
        if (onts.stream().anyMatch(ont -> !Boolean.FALSE.equals(ont.getOnline())
                && ((ont.getMac() == null && !Boolean.TRUE.equals(ont.getMacUnavailable()))
                        || ont.getMgmtIp() == null))) {
            enrichOntIpconfig(slotNo, portNo, onts, timeout, notes);
        }
    }

    /** 逐台补 MAC / 管理地址 */
    private void enrichOntIpconfig(Integer slotNo, int portNo, List<OltOnt> onts, int timeout,
                                   List<String> notes) {
        runCommand("interface gpon 0/" + slotNo, timeout);
        try {
            boolean noted = false;
            for (OltOnt ont : onts) {
                if (ont.getOntId() == null || Boolean.FALSE.equals(ont.getOnline())
                        || (ont.getMac() != null || Boolean.TRUE.equals(ont.getMacUnavailable()))
                                && ont.getMgmtIp() != null) {
                    continue;
                }
                String text = runCommand("display ont ipconfig " + portNo + " " + ont.getOntId(), timeout);
                if (text != null && OltInfoParser.looksUnsupported(text)) {
                    runQuietly("quit", timeout);
                    text = runCommand("display ont ipconfig 0 " + slotNo + " " + portNo + " " + ont.getOntId(),
                            timeout);
                    runCommand("interface gpon 0/" + slotNo, timeout);
                }
                if (text == null || text.isBlank()) {
                    continue;
                }
                OltOntInfo info = OltInfoParser.parseOntIpconfig(text);
                if (ont.getMac() == null && info.getMac() != null) {
                    ont.setMac(info.getMac());
                }
                if (ont.getMgmtIp() == null) {
                    if (info.getIp() != null) {
                        ont.setMgmtIp(info.getIp());
                    } else if (Boolean.TRUE.equals(info.getIpEmpty())) {
                        ont.setMgmtIp("");
                    }
                }
                if (ont.getMac() == null && ZERO_MAC.matcher(text).find()) {
                    ont.setMacUnavailable(Boolean.TRUE);
                }
                if (ont.getMac() == null && !noted) {
                    noted = true;
                    String snippet = text.trim().replaceAll("\\s+", " ");
                    if (snippet.length() > 160) {
                        snippet = snippet.substring(snippet.length() - 160);
                    }
                    String note = "ONT MAC 未取到 0/" + slotNo + "/" + portNo + "（ONT " + ont.getOntId()
                            + "），display ont ipconfig 回显结尾：" + snippet;
                    notes.add(note);
                    transcript.append("\n[MAC未取到] ").append(note).append('\n');
                }
            }
        } finally {
            runQuietly("quit", timeout);
        }
    }

    private static final Pattern PON_INTERFACE = Pattern.compile(
            "(?i)^\\s*interface\\s+(gpon|epon)\\s+0/(\\d+)");

    private static final Pattern ONT_ADD_ROW = Pattern.compile(
            "(?i)^\\s*ont\\s+add\\s+(\\d+)\\s+(\\d+)\\s+\\S+");

    private static final Pattern LINE_PROFILE_ID = Pattern.compile(
            "(?i)ont-lineprofile-id\\s+(\\d{1,6})");

    private static final Pattern SRV_PROFILE_ID = Pattern.compile(
            "(?i)ont-srvprofile-id\\s+(\\d{1,6})");

    /** 批量补 ONT 模板号，返回补上台数 */
    public int fillOntProfilesFromConfig(List<OltBoard> boards, List<String> notes) {
        if (boards == null || boards.isEmpty()) {
            return 0;
        }
        int timeout = Math.min(commandTimeoutMs, 60000);
        if (!enterConfigView(timeout)) {
            notes.add("进不了配置视图（enable → config），ONT 模板号未补采");
            return 0;
        }
        String config = runCommand("display current-configuration configuration ont", timeout);
        if (config == null || OltInfoParser.looksUnsupported(config) || !config.contains("ont add")) {
            config = runCommand("display current-configuration section ont", timeout);
        }
        if (config == null || OltInfoParser.looksUnsupported(config) || !config.contains("ont add")) {
            config = runCommand("display current-configuration", timeout);
        }
        if (config == null || config.isBlank()) {
            notes.add("display current-configuration 没回显，ONT 模板号未补采");
            return 0;
        }
        return applyOntBindings(boards, config);
    }

    /** 把 ont add 行里的模板号按位置填回 ONT */
    private int applyOntBindings(List<OltBoard> boards, String config) {
        Map<String, OltOnt> bySeat = new java.util.HashMap<>();
        for (OltBoard board : boards) {
            if (board.getSlotNo() == null || board.getPorts() == null) {
                continue;
            }
            for (OltPort port : board.getPorts()) {
                if (port.getPortNo() == null || port.getOnts() == null) {
                    continue;
                }
                for (OltOnt ont : port.getOnts()) {
                    if (ont.getOntId() != null) {
                        bySeat.put(board.getSlotNo() + "/" + port.getPortNo() + "/" + ont.getOntId(), ont);
                    }
                }
            }
        }
        if (bySeat.isEmpty()) {
            return 0;
        }
        int filled = 0;
        Integer slot = null;
        Set<OltOnt> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        String[] lines = config.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher iface = PON_INTERFACE.matcher(lines[i]);
            if (iface.find()) {
                slot = Integer.parseInt(iface.group(2));
                continue;
            }
            Matcher add = ONT_ADD_ROW.matcher(lines[i]);
            if (!add.find() || slot == null) {
                continue;
            }
            int portNo = Integer.parseInt(add.group(1));
            int ontId = Integer.parseInt(add.group(2));
            OltOnt ont = bySeat.get(slot + "/" + portNo + "/" + ontId);
            if (ont == null) {
                continue;
            }
            seen.add(ont);
            StringBuilder block = new StringBuilder(lines[i]);
            for (int j = 1; j <= 2 && i + j < lines.length; j++) {
                block.append(' ').append(lines[i + j].trim());
            }
            Integer lineId = firstInt(LINE_PROFILE_ID, block);
            Integer srvId = firstInt(SRV_PROFILE_ID, block);
            boolean changed = false;
            if (lineId != null && !lineId.equals(ont.getLineProfileId())) {
                ont.setLineProfileId(lineId);
                changed = true;
            }
            if (srvId != null && !srvId.equals(ont.getSrvProfileId())) {
                ont.setSrvProfileId(srvId);
                changed = true;
            }
            if (lineId == null || srvId == null) {
                ont.setProfileUnavailable(Boolean.TRUE);
            }
            if (changed) {
                filled++;
            }
        }
        if (!seen.isEmpty()) {
            for (OltOnt ont : bySeat.values()) {
                if (!seen.contains(ont) && (ont.getLineProfileId() == null || ont.getSrvProfileId() == null)) {
                    ont.setProfileUnavailable(Boolean.TRUE);
                }
            }
        }
        return filled;
    }

    private static Integer firstInt(Pattern pattern, CharSequence text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static final java.util.regex.Pattern ZERO_MAC = java.util.regex.Pattern.compile(
            "(?im)^\\s*ONT\\s+MAC[^:：\\r\\n]*[:：]\\s*0{4}[-:]?0{4}[-:]?0{4}\\s*$");

    private static String validIp(String ip) {
        if (ip == null || ip.isBlank() || "-".equals(ip.trim())) {
            return null;
        }
        return ip.trim();
    }

    /** 解析 ONT 的 MAC */
    private static String resolveMac(String deviceMac, String authCode) {
        if (deviceMac != null && !deviceMac.isBlank()) {
            return OltInfoParser.normalizeMac(deviceMac);
        }
        if (authCode == null) {
            return null;
        }
        return OltInfoParser.normalizeMac(authCode.split(" ")[0]);
    }

    private static int portCount(OltBoard board) {
        if (board.getPorts() != null && !board.getPorts().isEmpty()) {
            return board.getPorts().size();
        }
        return board.getPortCount() == null ? 0 : board.getPortCount();
    }

    /** ONT 数量和明细挂到端口上 */
    private static void applyOntCount(OltBoard board, int portNo, int[] stat, List<OltOnt> onts) {
        List<OltPort> ports = board.getPorts();
        if (ports == null) {
            ports = new ArrayList<>();
            board.setPorts(ports);
        }
        OltPort port = findPort(ports, portNo);
        if (port == null) {
            port = new OltPort();
            port.setFrameNo(0);
            port.setSlotNo(board.getSlotNo());
            port.setPortNo(portNo);
            port.setPortName("P" + portNo);
            ports.add(port);
        }
        port.setOnuTotal(stat[0]);
        port.setOnuOnline(stat[1]);
        port.setOnts(onts);
        ports.sort(Comparator.comparing(item -> item.getPortNo() == null ? 0 : item.getPortNo()));
    }

    private static OltPort findPort(List<OltPort> ports, Integer portNo) {
        for (OltPort port : ports) {
            if (port.getPortNo() != null && port.getPortNo().equals(portNo)) {
                return port;
            }
        }
        return null;
    }

    private static String ontCommand(int slotNo) {
        return "display ont info 0 " + slotNo + " all";
    }

    /** 判定设备厂商 */
    private OltVendor detectVendor(String... texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            OltVendor vendor = OltVendor.detect(text);
            if (vendor != OltVendor.GENERIC) {
                return vendor;
            }
        }
        OltVendor fromBanner = OltVendor.detect(banner.toString());
        if (fromBanner != OltVendor.GENERIC) {
            return fromBanner;
        }
        return OltVendor.detect(prompt == null ? "" : prompt);
    }

    /** 确定命令风格 */
    private static OltVendor.CliStyle cliStyleOf(OltVendor vendor, OltVendor.CliStyle probed) {
        if (vendor != null && vendor.getCliStyle() != null) {
            return vendor.getCliStyle();
        }
        return probed;
    }

    /** 挑选板卡命令 */
    private static List<String> boardCommands(OltVendor vendor, OltVendor.CliStyle style) {
        List<String> own = OltVendorProfiles.of(vendor).boardCommands();
        if (vendor != OltVendor.GENERIC) {
            return own;
        }
        if (style == OltVendor.CliStyle.DISPLAY) {
            return List.of("display board 0");
        }
        if (style == OltVendor.CliStyle.SHOW) {
            return List.of("show card", "show slot");
        }
        return own;
    }

    /** 版本探测 */
    private VersionProbe probeVersion() {
        int timeout = Math.min(commandTimeoutMs, VERSION_PROBE_TIMEOUT_MS);
        String versionText = "";
        OltVendor.CliStyle style = null;
        for (String command : versionCommands(vendorHint)) {
            String out = runCommand(command, timeout);
            if (out == null || out.isBlank() || isRejected(out)) {
                continue;
            }
            versionText = out;
            style = OltVendor.CliStyle.ofCommand(command);
            if (OltVendor.detect(out) != OltVendor.GENERIC) {
                return new VersionProbe(out, style);
            }
        }
        return new VersionProbe(versionText, style);
    }

    private static List<String> versionCommands(OltVendor vendor) {
        if (vendor != null && vendor != OltVendor.GENERIC) {
            return OltVendorProfiles.of(vendor).versionCommands();
        }
        return OltVendorProfiles.of(OltVendor.GENERIC).versionCommands();
    }

    /** 设备不认这条命令（风格发错了） */
    private static boolean isRejected(String text) {
        return COMMAND_REJECTED.matcher(text).find();
    }

    /** 版本探测结果：回显 + 它对应的命令风格 */
    private record VersionProbe(String text, OltVendor.CliStyle style) {
    }

    /** 报错时给页面看的第一行有效回显 */
    private static String headLine(String text) {
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
            }
        }
        return "";
    }

    /** 采集过程中最后一次异常 */
    public IOException getLastError() {
        return lastError;
    }

    @Override
    public void close() {
        client.close();
    }

    /** 连接是否还活着 */
    public boolean isAlive() {
        return !sessionLost && client.isAlive();
    }

    /** 开始一条新任务 */
    public void beginTask() {
        transcript.setLength(0);
        lastError = null;
    }

    /** 当前停留的视图（页面/日志排障用） */
    public CliView currentView() {
        return view;
    }

    /** 探活 */
    public boolean ping(int timeoutMs) {
        return probeView(timeoutMs);
    }

    /** 校准当前视图 */
    private boolean probeView(int timeoutMs) {
        try {
            client.resetBuffer();
            client.sendLine("");
            String out = client.readUntilAny(List.of(SHELL_PROMPT), Math.min(5000, Math.max(1000, timeoutMs)));
            if (USERNAME_PROMPT.matcher(out).find() || PASSWORD_PROMPT.matcher(out).find()) {
                    sessionLost = true;
                view = CliView.UNKNOWN;
                return false;
            }
            view = viewOf(out, view);
            return view != CliView.UNKNOWN;
        } catch (IOException e) {
            return false;
        }
    }

    /** 退回根视图 */
    private boolean ensureRootView(int timeoutMs) {
        for (int round = 0; round < 4 && view != CliView.ROOT; round++) {
            if (view == CliView.UNKNOWN && !probeView(timeoutMs)) {
                return false;
            }
            if (view == CliView.ROOT) {
                break;
            }
            String out = runQuietly("quit", timeoutMs);
            if (isAsking(out)) {
                runQuietly("n", timeoutMs);
            }
            if (out == null || out.isBlank() || USERNAME_PROMPT.matcher(out).find()) {
                sessionLost = true;
                view = CliView.UNKNOWN;
                return false;
            }
        }
        return view == CliView.ROOT;
    }

    /** 从提示符判断当前视图 */
    static CliView viewOf(String out, CliView current) {
        String line = lastLine(out);
        if (line.isEmpty()) {
            return current;
        }
        String prompt = line.toLowerCase(Locale.ROOT);
        if (prompt.contains("(config-") || prompt.contains("-if-")
                || prompt.contains("-ont-") || prompt.contains("-gem-") || prompt.contains("-aaa")) {
            return CliView.SUB;
        }
        if (prompt.contains("(config") || prompt.contains("config)#")) {
            return CliView.CONFIG;
        }
        if (line.endsWith(">") || line.endsWith("#") || line.endsWith("]")) {
            return CliView.ROOT;
        }
        return current;
    }

    private static final List<Pattern> LOGOUT_STOPS = List.of(INTERACTIVE_ASK, SHELL_PROMPT, USERNAME_PROMPT);

    /** 退出登录并关闭连接 */
    public void logoutAndClose() {
        try {
            for (int round = 0; round < 4; round++) {
                client.resetBuffer();
                client.sendLine("quit");
                String out = client.readUntilAny(LOGOUT_STOPS, 3000);
                if (isAsking(out)) {
                    client.resetBuffer();
                    client.sendLine("y");
                    out = client.readUntilAny(LOGOUT_STOPS, 3000);
                }
                if (out == null || out.isBlank() || USERNAME_PROMPT.matcher(out).find()) {
                    break;
                }
            }
        } catch (IOException e) {
        } finally {
            close();
        }
    }

    private void login(String username, String password, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        String out = readLoginScreen(List.of(USERNAME_PROMPT, PASSWORD_PROMPT, SHELL_PROMPT), deadline);
        record(out);

        if (USERNAME_PROMPT.matcher(out).find()) {
            client.sendLine(username);
            client.resetBuffer();
            out = readLoginScreen(List.of(PASSWORD_PROMPT, SHELL_PROMPT, LOGIN_FAILED), deadline);
            record(out);
        }
        if (PASSWORD_PROMPT.matcher(out).find()) {
            client.sendLine(password);
            client.resetBuffer();
            out = readLoginScreen(List.of(SHELL_PROMPT, LOGIN_FAILED, USERNAME_PROMPT), deadline);
            record(out);
        }
        if (LOGIN_FAILED.matcher(out).find()) {
            throw new OltTelnetException("登录失败（账号 " + username + "）：" + lastLine(out));
        }
        if (!SHELL_PROMPT.matcher(out).find()) {
            throw new OltTelnetException("登录后未发现命令行提示符，实际回显：" + lastLine(out));
        }
        prompt = lastLine(out);
        view = CliView.ROOT;
    }

    /** 记录登录回显 */
    private void record(String out) {
        transcript.append(out);
        banner.append(out).append('\n');
    }

    /** 读一屏登录回显 */
    private String readLoginScreen(List<Pattern> stops, long deadline) throws IOException {
        String out = readLoginOutput(stops, deadline);
        for (int round = 0; !matchesAny(stops, out); round++) {
            if (round >= MAX_LOGIN_ENTER || remaining(deadline) < 2000) {
                return client.readUntilAny(stops, remaining(deadline));
            }
            Logs.info(Logs.Module.OLT_COLLECT, "登录回显未出现预期提示（设备可能停在登录信息页），补一个回车: 第{}次", round + 1);
            transcript.append("[补回车] ");
            client.sendLine("");
            out = readLoginOutput(stops, deadline);
        }
        return out;
    }

    /** 读一段登录回显 */
    private String readLoginOutput(List<Pattern> stops, long deadline) throws IOException {
        try {
            return client.readIdle(LOGIN_IDLE_MS, Math.min(LOGIN_IDLE_MAX_MS, remaining(deadline)));
        } catch (IOException e) {
            String text = client.getText();
            if (matchesAny(stops, text)) {
                return text;
            }
            throw e;
        }
    }

    private static boolean matchesAny(List<Pattern> stops, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (Pattern stop : stops) {
            if (stop.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static long remaining(long deadline) {
        return Math.max(1000, deadline - System.currentTimeMillis());
    }

    /** 采集板卡温度 */
    private void applyTemperatures(List<OltBoard> boards, int timeout) {
        Map<Integer, Integer> temperatures = OltInfoParser.parseTemperatures(
                runCommand("display temperature all", timeout));
        for (OltBoard board : boards) {
            Integer temperature = temperatures.get(board.getSlotNo());
            if (temperature != null) {
                board.setTemperature(temperature);
            }
        }
        if (!temperatures.isEmpty()) {
            return;
        }
        for (OltBoard board : boards) {
            if (board.getSlotNo() == null || board.getTemperature() != null) {
                continue;
            }
            Integer temperature = OltInfoParser.parseBoardTemperature(
                    runCommand("display temperature 0/" + board.getSlotNo(), timeout));
            if (temperature != null) {
                board.setTemperature(temperature);
            }
        }
    }

    /** 关分页 */
    private void prepareTerminal(OltVendor vendor) {
        prepareTerminal(vendor, false);
    }

    /** 关分页（按需只发一条） */
    private void prepareTerminal(OltVendor vendor, boolean firstOnly) {
        if (vendor != null && vendor != OltVendor.GENERIC && pagingPrepared == vendor) {
            client.setPagingDisabled(true);
            return;
        }
        int timeout = Math.min(commandTimeoutMs, 4000);
        if (vendor != null && vendor != OltVendor.GENERIC) {
            List<String> commands = OltVendorProfiles.of(vendor).pagingCommands();
            if (runPaging(firstOnly && !commands.isEmpty() ? commands.subList(0, 1) : commands, timeout)) {
                pagingPrepared = vendor;
                client.setPagingDisabled(true);
            }
            return;
        }
        List<String> huawei = OltVendorProfiles.of(OltVendor.HUAWEI).pagingCommands();
        List<String> thirdParty = OltVendorProfiles.of(OltVendor.ZTE).pagingCommands();
        List<String> generic = OltVendorProfiles.of(OltVendor.GENERIC).pagingCommands();
        if (firstOnly) {
            runPaging(List.of(first(huawei), first(generic)), timeout);
            return;
        }
        List<String> all = new ArrayList<>(huawei);
        all.addAll(thirdParty);
        all.addAll(generic);
        runPaging(all, timeout);
    }

    /** 逐条关分页 */
    private boolean runPaging(List<String> commands, int timeout) {
        for (String command : commands) {
            if (command == null || command.isBlank()) {
                continue;
            }
            String out = runQuietly(command, timeout);
            if (out != null && !out.isBlank() && commandRejected(out) == null) {
                return true;
            }
        }
        return false;
    }

    private static String first(List<String> commands) {
        return commands == null || commands.isEmpty() ? "" : commands.get(0);
    }

    /** 执行命令并返回回显 */
    private String runCommand(String command) {
        return runCommand(command, commandTimeoutMs);
    }

    private String runCommand(String command, int timeoutMs) {
        return runCommand(command, timeoutMs, false);
    }

    private String runCommand(String command, int timeoutMs, boolean silent) {
        long start = System.currentTimeMillis();
        try {
            client.resetBuffer();
            client.sendLine(command);
            String out = readToPrompt(timeoutMs);
            logCommand(command, start, out, null);
            transcript.append("\n$ ").append(command).append(' ').append(cost(start))
                    .append('\n').append(out).append('\n');
            return out;
        } catch (IOException e) {
            String partial = client.getText();
            lastError = e;
            logCommand(command, start, partial, e.getMessage());
            transcript.append("\n$ ").append(command).append(' ').append(cost(start))
                    .append("\n[失败] ").append(e.getMessage()).append('\n')
                    .append(partial).append('\n');
            drain();
            view = CliView.UNKNOWN;
            if (partial != null && (USERNAME_PROMPT.matcher(partial).find() || PASSWORD_PROMPT.matcher(partial).find())) {
                sessionLost = true;
            }
            return partial;
        }
    }

    private void logCommand(String command, long start, String echo, String error) {
    }

    private static String capEcho(String echo) {
        int limit = 20000;
        if (echo.length() <= limit) {
            return echo;
        }
        return echo.substring(0, limit) + "\n...[日志截断，共 " + echo.length()
                + " 字符，完整回显见采集记录]";
    }

    /** 排空残留回显 */
    private void drain() {
        try {
            client.resetBuffer();
            client.readIdle(300, 2000);
        } catch (IOException e) {
            Logs.debug(Logs.Module.OLT_COLLECT, "排空残留回显失败(忽略): {}", e.getMessage());
        } finally {
            client.resetBuffer();
        }
    }

    /** 执行辅助命令 */
    private String runQuietly(String command, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            client.resetBuffer();
            client.sendLine(command);
            String out = readToPrompt(timeoutMs);
            transcript.append("\n$ ").append(command).append(' ').append(cost(start))
                    .append('\n').append(out).append('\n');
            return out;
        } catch (IOException e) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT 辅助命令失败(忽略): {} - {}", command, e.getMessage());
            transcript.append("\n$ ").append(command).append(' ').append(cost(start))
                    .append("\n[忽略] ").append(e.getMessage()).append('\n');
            return "";
        }
    }

    private static String cost(long start) {
        return "[" + (System.currentTimeMillis() - start) + "ms]";
    }

    /** 读到提示符为止 */
    private String readToPrompt(int timeoutMs) throws IOException {
        String out = client.readUntilAny(COMMAND_STOPS, timeoutMs, true);
        for (int round = 0; needsParameter(out) && round < MAX_PARAM_ROUNDS; round++) {
            transcript.append("[参数提示] ").append(out.trim()).append('\n');
            Logs.debug(Logs.Module.OLT_COLLECT, "设备停在参数提示，补回车继续读");
            client.resetBuffer();
            client.sendLine("");
            out = client.readUntilAny(COMMAND_STOPS, timeoutMs, true);
        }
        view = viewOf(out, view);
        if (USERNAME_PROMPT.matcher(out).find() || PASSWORD_PROMPT.matcher(out).find()) {
            sessionLost = true;
            view = CliView.UNKNOWN;
        }
        return out;
    }

    private static boolean needsParameter(String text) {
        return text != null && !text.isEmpty() && PARAM_PROMPT.matcher(text).find();
    }

    private static String lastLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line.length() > 120 ? line.substring(0, 120) : line;
            }
        }
        return "";
    }
}
