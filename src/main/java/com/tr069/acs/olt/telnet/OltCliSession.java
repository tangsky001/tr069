package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.model.OltDbaProfile;
import com.tr069.acs.olt.model.OltGemport;
import com.tr069.acs.olt.model.OltLineProfile;
import com.tr069.acs.olt.model.OltServicePort;
import com.tr069.acs.olt.model.OltSrvProfile;
import com.tr069.acs.olt.model.OltTrafficTable;
import com.tr069.acs.olt.model.OltTr069Profile;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltTemplateInfo;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltPortVlan;
import com.tr069.acs.olt.model.OltVlan;
import com.tr069.acs.olt.model.OltVlanInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一台 OLT 的命令行会话：登录 → 识别厂商 → 采集版本/板卡 → 关闭。
 *
 * <p>页面上的账号密码留空时按 root / admin 尝试——这两组是运营商 OLT 出厂默认值，
 * 页面的占位提示也是这么写的。</p>
 */
@Slf4j
public class OltCliSession implements Closeable {

    /** 账号留空时的默认值 */
    public static final String DEFAULT_USERNAME = "root";

    /** 密码留空时的默认值 */
    public static final String DEFAULT_PASSWORD = "admin";

    private static final Pattern USERNAME_PROMPT = Pattern.compile(
            "(?i)(?:user\\s*name|username|login)\\s*[:：]\\s*$");

    private static final Pattern PASSWORD_PROMPT = Pattern.compile(
            "(?i)(?:password|passwd)\\s*[:：]\\s*$");

    /** 命令行提示符：<HUAWEI> / ZXAN# / AN5516-01# / [~HUAWEI] */
    private static final Pattern SHELL_PROMPT = Pattern.compile("(?m)[^\\r\\n]{0,80}[>#\\]]\\s*$");

    /**
     * 命令还要参数时的提示，例如 MA5683T 的 display version：
     * {@code { <cr>|backplane<K>|frameid/slotid<S><Length 1-15> };}。
     * 设备停在这里等输入，不回提示符，不处理就只能等到超时。
     */
    static final Pattern PARAM_PROMPT = Pattern.compile(
            "(?m)(?:[^\\r\\n]*\\{\\s*<cr>[^\\r\\n]*\\}"
                    // 参数补全提示后面带冒号的（如 { all<K>|bound-info<K>|... }:），回车即执行
                    + "|[^\\r\\n]*\\{[^{}]*\\}\\s*[:：]\\s*$"
                    + "|[^\\r\\n]*param(?:eter)?s?\\s*[:：][^\\r\\n]*)\\s*[:：]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 参数提示最多回几次车，防止设备反复要参数时死循环 */
    private static final int MAX_PARAM_ROUNDS = 3;

    private static final Pattern LOGIN_FAILED = Pattern.compile(
            "(?i)(login\\s+(incorrect|invalid)|authentication\\s+(fail|failed|denied)"
                    + "|password\\s+(incorrect|invalid)|access\\s+denied|permission\\s+denied"
                    + "|too\\s+many\\s+(users|failures)|invalid\\s+user"
                    + "|密码错误|用户名或密码|登录失败|认证失败)");

    /** 板卡探测顺序：华为 → 中兴/烽火。不用不带参数的 display board，那会停在 Parameter 提示上 */
    private static final List<String> BOARD_COMMANDS = List.of("display board 0", "show card", "show slot");

    /** 版本探测顺序：华为 display → 第三方 show，谁回了内容就用谁的命令风格 */
    private static final List<String> VERSION_COMMANDS = List.of("display version", "show version");

    /** 设备不认这条命令时的回显，说明风格发错了，换另一套再试 */
    private static final Pattern COMMAND_REJECTED = Pattern.compile(
            "(?i)(unknown\\s+command|unrecognized\\s+command|invalid\\s+(command|input)"
                    + "|incorrect\\s+command|command\\s+not\\s+found"
                    + "|错误的命令|命令不存在|不支持的命令)");

    /** 单条版本命令的超时上限：认厂商要快，不能让它把后面板卡的时间吃掉 */
    private static final int VERSION_PROBE_TIMEOUT_MS = 8000;

    private static final List<Pattern> COMMAND_STOPS = List.of(SHELL_PROMPT, PARAM_PROMPT);

    /**
     * 登录过程中设备停下来要回车时，最多补几次。
     *
     * <p>鸿升光（HSGQ）登录成功后会把 {@code telnet login / info all login users}
     * 的登录记录表打出来——被外网扫描的设备这张表有几十上百行，打完（或打满一屏）
     * 就停在那儿，要按回车才继续出 {@code suc login!} 和 {@code OLT>} 提示符。
     * 不补回车就只能等到登录超时，现场看到的是「账号密码都对却连不上」。</p>
     */
    private static final int MAX_LOGIN_ENTER = 5;

    /** 判定「这一屏打完了」的静默时间，以及单次读取的上限 */
    private static final long LOGIN_IDLE_MS = 400L;
    private static final long LOGIN_IDLE_MAX_MS = 2000L;

    private final TelnetClient client;

    private final int commandTimeoutMs;

    private final StringBuilder transcript = new StringBuilder();

    /** 登录横幅，版本命令没回显时靠它识别厂商和型号 */
    private final StringBuilder banner = new StringBuilder();

    private String prompt;

    private IOException lastError;

    private OltCliSession(TelnetClient client, int commandTimeoutMs) {
        this.client = client;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    /**
     * 建立连接并完成登录。
     *
     * @param device          OLT 连接参数
     * @param charset         回显字符集
     * @param connectTimeoutMs 建连超时
     * @param loginTimeoutMs   登录超时
     * @param commandTimeoutMs 单条命令超时
     */
    public static OltCliSession open(OltDevice device, Charset charset, int connectTimeoutMs,
                                     int loginTimeoutMs, int commandTimeoutMs) throws IOException {
        String protocol = device.getProtocol() == null ? "TELNET" : device.getProtocol().trim().toUpperCase(Locale.ROOT);
        if (!"TELNET".equals(protocol)) {
            throw new OltTelnetException("当前仅实现 Telnet 登录，SSH 请先选 Telnet 或等待后续支持");
        }
        int port = device.getPort() == null || device.getPort() <= 0 ? 23 : device.getPort();
        TelnetClient client = TelnetClient.connect(device.getHost(), port, device.getBindLocalIp(),
                charset, connectTimeoutMs, Math.min(commandTimeoutMs, 3000));
        OltCliSession session = new OltCliSession(client, commandTimeoutMs);
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
        return username == null || username.isBlank() ? DEFAULT_USERNAME : username.trim();
    }

    public static String passwordOf(OltDevice device) {
        String password = device.getPassword();
        return password == null || password.isBlank() ? DEFAULT_PASSWORD : password;
    }

    /**
     * 采集信息。
     *
     * <p>顺序是：登录成功先用 {@code display version} / {@code show version} 定下
     * 命令风格与厂商（华为 display、第三方 show），再按厂商的命令集采集板卡、温度、
     * ONT、VLAN。风格定不下来时才两套命令都试一遍。</p>
     *
     * @param withBoards 是否一并采集板卡
     */
    public OltSnapshot collect(boolean withBoards) {
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();

        // 先关分页，否则版本/板卡回显都可能停在第一页
        prepareTerminal();

        // 第一步：判定命令风格与厂商。display version 有回显说明命令走 display 那一套
        // （华为用它，第三方也有用的），show version 有回显就走 show；厂商另外按回显关键字认
        VersionProbe probe = probeVersion();
        OltVendor vendor = detectVendor(probe.text());
        String versionText = probe.text();
        if (versionText.isBlank()) {
            notes.add("display version / show version 均无回显（命令超时或连接被断开）");
        }

        // 第二步：板卡。命令按上面定下的厂商/风格挑，只有风格完全没定下来才两套都试
        String boardText = "";
        List<OltBoard> boards = List.of();
        List<String> boardCommands = boardCommands(vendor, probe.style());
        if (withBoards && vendor != OltVendor.HSGQ) {
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
            // 版本命令没回来时，登录横幅里通常也有型号（Huawei MA5683T ...）
            model = OltInfoParser.extractModel(vendor, banner.toString());
        }
        if (withBoards && vendor == OltVendor.HSGQ) {
            // 现场确认：鸿升光不需要采集板卡信息，识别出厂商后直接按型号生成整机卡片出图
            // （E04 固定 4 个 PON 口；show pon-info 部分固件只回命令回显，采不到数据）
            boards = List.of(OltInfoParser.buildHsgqBoard(model));
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
            if (vendor == OltVendor.HUAWEI && !boards.isEmpty()) {
                collectFromConfigView(boards, notes, snapshot);
            }
            if (vendor == OltVendor.HSGQ && !boards.isEmpty()) {
                collectHsgq(boards.get(0), notes);
            }
            if (snapshot.getBackplane() == null && vendor == OltVendor.HUAWEI) {
                // MA5683T 这类设备的 display version 不带 PCB/Backplane 段，
                // display board 0 的回显里也没有背板行，背板型号要单独问：
                // display version backplane 0 →「Backplane: H802MAB0」
                String backplaneText = runCommand("display version backplane 0");
                if (backplaneText != null && !backplaneText.isBlank()) {
                    snapshot.setBackplane(OltInfoParser.extractBackplane(backplaneText));
                }
            }
            if (snapshot.getBackplane() == null) {
                snapshot.setBackplane(OltInfoParser.extractBackplaneBoard(boardText));
            }
            snapshot.setBoards(boards);
            // 机框槽位数跟着 display board 0 的槽位表走（空槽位也在表里），页面按它画整框；
            // 鸿升光是整机，槽位表采不到（也不该画成 12 个空槽位围一台机器），按板卡数给
            snapshot.setSlotCount(vendor == OltVendor.HSGQ
                    ? boards.size() : OltInfoParser.parseSlotCount(boardText));
        }

        if (lastError != null) {
            notes.add("命令异常：" + lastError.getMessage());
        }
        snapshot.setNotes(notes);
        snapshot.setTranscript(transcript.toString());
        return snapshot;
    }

    /**
     * 配置视图里的一次性采集：温度、端口状态、ONT 数量、光模块数值、VLAN、模板。
     *
     * <p>现场这台 MA5683T 的这几条命令都只在 config 视图下认，用户视图直接
     * {@code % Unknown command}。光模块数值（发光/收光/温度/偏置/电压）还要再往下一层，
     * 在 {@code interface gpon 0/<槽位>} 接口视图里发 {@code display port state <口>}，
     * 见 {@link #collectPortOptics}。</p>
     */
    private void collectFromConfigView(List<OltBoard> boards, List<String> notes, OltSnapshot snapshot) {
        int timeout = Math.min(commandTimeoutMs, 8000);
        if (!enterConfigView(timeout)) {
            notes.add("进不了配置视图（enable → config），温度/ONT/VLAN 未采到");
            return;
        }
        boolean ontOk;
        try {
            applyTemperatures(boards, timeout);
            // 先把每块板的端口（含光模块不在位、链路离线的空口）建出来，ONT 再往上面挂
            collectBoardPortStates(boards, timeout);
            ontOk = collectOnts(boards, timeout);
            // 光模块数值（发光/收光/温度/偏置/电压）：端口建好之后再逐口问
            collectPortOptics(boards, timeout);
            collectVlans(boards, snapshot, timeout);
            collectTemplates(snapshot, timeout);
        } finally {
            // 只退到特权视图：再 quit 一次就是 logout，设备会停下来问 y/n
            runQuietly("quit", timeout);
        }
        if (!ontOk) {
            notes.add("ONT 数量未采到：display ont info 0 <槽位> all 无有效回显");
        }
    }

    /**
     * 逐板发 {@code display board 0/<槽位>}，把端口表采回来：光模块在位状态
     * （absence = 不在位）与链路状态（offline = 离线）一起入档。
     *
     * <p>之前只从 {@code display ont info} 的回显建端口，回显里只有配了 ONT 的口，
     * 空口（业务板 P3-P7、主控/上联板全部）就没有端口数据；这条命令把每块板的
     * 全部端口连同状态补齐，空口也记录为「不在位/离线」而不是缺失。</p>
     */
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

    /** 端口状态挂到端口上：已有就更新，没有就新建一条（空口也要有端口记录） */
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

    /**
     * 逐口采光模块数值：{@code interface gpon 0/<槽位>} 进接口视图，再
     * {@code display port state <口>}（用户视图/config 视图直接发不认，必须在接口视图里）。
     *
     * <p>只对 PON 业务板发；某块板第一个口就没有光模块回显，说明这版设备/这块板不认这条命令，
     * 立刻跳过这块板，不挨个口等超时。</p>
     */
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
                // 没进接口视图就别继续发（quit 会把 config 视图也退掉）
                continue;
            }
            try {
                int missed = 0;
                for (int portNo = 0; portNo < count; portNo++) {
                    OltPort optics = OltInfoParser.parsePortStateOptics(
                            runCommand("display port state " + portNo, timeout));
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

    /** 光模块数值挂到端口上：补空字段，不覆盖 display board 采到的在位状态和链路状态 */
    private static void applyPortOptics(OltBoard board, int portNo, OltPort optics) {
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
        // Optical Module status 顺带把「在位」补上：display board 没给出状态时用它兜底
        String moduleStatus = optics.getModuleStatus();
        if (port.getOpticalPresent() == null && moduleStatus != null) {
            String state = moduleStatus.toLowerCase(Locale.ROOT);
            port.setOpticStatus(state);
            port.setOpticalPresent(!(state.contains("offline") || state.contains("absent")));
        }
    }

    /**
     * VLAN：{@code display vlan all} 一条拿 VLAN 列表，再对上行口/主控口逐个
     * {@code display port vlan 0/<槽位>/<端口>} 拿放通 VLAN。
     *
     * <p>只问上行口和主控口——PON 口是业务口，几十个口逐个问既慢又没意义；
     * 「透传 VLAN」在网管上看的也正是这几个上联口。某块板第一个口就回 Unknown
     * 说明这版设备没这条命令，立刻收手，不挨个口等超时。</p>
     */
    private void collectVlans(List<OltBoard> boards, OltSnapshot snapshot, int timeout) {
        OltVlanInfo info = new OltVlanInfo();
        String vlanText = runCommand("display vlan all", timeout);
        List<OltVlan> vlans = OltInfoParser.parseVlans(vlanText);
        if (!vlans.isEmpty()) {
            info.setVlans(vlans);
            int total = OltInfoParser.parseVlanTotal(vlanText);
            info.setTotal(total > 0 ? total : vlans.size());
        }
        for (OltBoard board : boards) {
            if (board.getSlotNo() == null || !isUplinkKind(board.getBoardKind())) {
                continue;
            }
            int count = portCount(board);
            int failed = 0;
            for (int portNo = 0; portNo < count; portNo++) {
                String text = runCommand("display port vlan 0/" + board.getSlotNo() + "/" + portNo, timeout);
                OltPortVlan portVlan = OltInfoParser.parsePortVlan(text);
                if (portVlan == null) {
                    // 连着两个口都没回显就别再问了，多半是这版设备不认这条命令
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
        // 三层接口和路由表各一条，开销很小，顺手就采了
        info.setIpInterfaces(OltInfoParser.parseIpInterfaces(runCommand("display ip interface brief", timeout)));
        info.setStaticRoutes(OltInfoParser.parseStaticRoutes(runCommand("display ip routing-table", timeout)));
        if (!info.getVlans().isEmpty() || !info.getPortVlans().isEmpty()) {
            info.setCollectedAt(LocalDateTime.now().withNano(0).toString().replace('T', ' '));
            snapshot.setVlanInfo(info);
        }
    }

    /**
     * DBA 模板与线路模板：{@code display dba-profile all} +
     * {@code display ont-lineprofile gpon all}，两条都在 config 视图下认。
     *
     * <p>看的就是「这条线路模板用了哪档带宽」——线路模板挂 T-CONT、T-CONT 引用 DBA 模板，
     * 所以两份一起采，页面上按 T-CONT 把两边对起来。采不到就整份不写，页面提示未采集。</p>
     */
    private void collectTemplates(OltSnapshot snapshot, int timeout) {
        // 总表没有模板名，先拿块状详情（含 Profile-name），名字按 ID 对回总表；
        // 版本不支持 detail 参数时命令报错、解析为空，就只靠总表
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
        // 总表行式对不上（老/新版本列又变了）就退回块状详情当数据源
        if (dbaProfiles.isEmpty() && !dbaDetails.isEmpty()) {
            dbaProfiles = dbaDetails;
        }
        // 模板命令里的 gpon/epon 跟着 PON 板类型走（epon 板要用 epon）：先按 gpon 采，采不到换 epon
        List<OltLineProfile> lineProfiles = OltInfoParser.parseLineProfiles(
                runCommand("display ont-lineprofile gpon all", timeout));
        String linePonType = "gpon";
        if (lineProfiles.isEmpty()) {
            linePonType = "epon";
            lineProfiles = OltInfoParser.parseLineProfiles(
                    runCommand("display ont-lineprofile epon all", timeout));
        }
        final String lineType = linePonType;
        final List<OltLineProfile> finalLineProfiles = lineProfiles;
        finalLineProfiles.forEach(profile -> {
            if (profile.getType() == null || profile.getType().isBlank()) {
                profile.setType(lineType);
            }
        });
        // all 只给模板概要，T-CONT / GEM 映射明细在 profile-id 详情里：逐条取回补齐
        // 0 号是系统内置默认模板（line-profile_default_0），页面不展示
        lineProfiles.removeIf(profile -> Integer.valueOf(0).equals(profile.getProfileId()));
        for (OltLineProfile profile : lineProfiles) {
            if (profile.getProfileId() == null) {
                continue;
            }
            String detail = runCommand("display ont-lineprofile " + linePonType
                    + " profile-id " + profile.getProfileId(), timeout);
            OltInfoParser.parseLineProfiles(detail).stream()
                    .filter(d -> profile.getProfileId().equals(d.getProfileId()))
                    .findFirst()
                    .ifPresent(d -> mergeLineProfile(profile, d));
        }
        // 配置脚本兜底：部分版本 display ont-lineprofile profile-id 的明细回显认不出
        // GEM 映射（页面映射关系一列全是「-」），而 current-configuration 的脚本行
        // （tcont / gem add / gem mapping）字段含义固定不随版本变，缺什么从这补
        String configScript = runCommand("display current-configuration section global-config", timeout);
        if (OltInfoParser.looksUnsupported(configScript)) {
            configScript = runCommand("display current-configuration", timeout);
        }
        List<OltLineProfile> configLineProfiles = configScript.isBlank()
                ? new ArrayList<>() : OltInfoParser.parseLineProfiles(configScript);
        for (OltLineProfile profile : lineProfiles) {
            if (profile.getProfileId() == null) {
                continue;
            }
            configLineProfiles.stream()
                    .filter(c -> profile.getProfileId().equals(c.getProfileId()))
                    .findFirst()
                    .ifPresent(c -> mergeLineProfileFromConfig(profile, c));
        }
        // 概要命令连一条模板都没认出来（回显格式又变了）时，脚本里的就是全部线路模板
        for (OltLineProfile fromConfig : configLineProfiles) {
            Integer id = fromConfig.getProfileId();
            if (id == null || id == 0 || fromConfig.getName() == null) {
                continue;
            }
            boolean exists = lineProfiles.stream().anyMatch(p -> id.equals(p.getProfileId()));
            if (!exists) {
                fromConfig.setType(lineType);
                lineProfiles.add(fromConfig);
            }
        }
        // 业务流与 ONT 能力模板同在 config 视图下认，顺这两条一起采（采不到就留空）
        // service-port 主用配置脚本段：字段含义固定（表格回显各版本列序不一，端口列老对不上）
        List<OltServicePort> servicePorts = OltInfoParser.parseServicePortsFromConfig(
                runCommand("display current-configuration section service-port", timeout));
        if (servicePorts.isEmpty()) {
            servicePorts = OltInfoParser.parseServicePorts(
                    runCommand("display service-port all", timeout));
        }
        // 能力模板同样分 gpon/epon
        List<OltSrvProfile> srvProfiles = OltInfoParser.parseSrvProfiles(
                runCommand("display ont-srvprofile gpon all", timeout));
        String srvPonType = "gpon";
        if (srvProfiles.isEmpty()) {
            srvPonType = "epon";
            srvProfiles = OltInfoParser.parseSrvProfiles(
                    runCommand("display ont-srvprofile epon all", timeout));
        }
        final String srvType = srvPonType;
        srvProfiles.forEach(profile -> profile.setPonType(srvType));
        // all 同样只有概要：ont-port / 能力项明细在 profile-id 详情里，逐条取回补齐
        for (OltSrvProfile profile : srvProfiles) {
            if (profile.getProfileId() == null) {
                continue;
            }
            String detail = runCommand("display ont-srvprofile " + srvPonType
                    + " profile-id " + profile.getProfileId(), timeout);
            OltInfoParser.parseSrvProfiles(detail).stream()
                    .filter(d -> profile.getProfileId().equals(d.getProfileId()))
                    .findFirst()
                    .ifPresent(d -> mergeSrvProfile(profile, d));
        }
        // 配置脚本兜底：all 概要表行没认出来（回显格式变了）时，脚本里的
        // ont-srvprofile 行就是全部能力模板；已有的补齐网口/电话口数量与名字
        for (OltSrvProfile from : configScript.isBlank()
                ? new ArrayList<OltSrvProfile>() : OltInfoParser.parseSrvProfiles(configScript)) {
            if (from.getProfileId() == null) {
                continue;
            }
            OltSrvProfile target = null;
            for (OltSrvProfile p : srvProfiles) {
                if (from.getProfileId().equals(p.getProfileId())) {
                    target = p;
                    break;
                }
            }
            if (target == null) {
                target = from;
                srvProfiles.add(from);
            }
            target.setPonType(srvType);
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
        // TR069 服务器模板是独立的一类模板，ACS 地址与账号都在它上面
        List<OltTr069Profile> tr069Profiles = OltInfoParser.parseTr069Profiles(
                runCommand("display ont tr069-server-profile all", timeout));
        if (tr069Profiles.isEmpty()) {
            // 有的版本不认 all，去掉再试（不支持也只是多一个来回）
            tr069Profiles = OltInfoParser.parseTr069Profiles(
                    runCommand("display ont tr069-server-profile", timeout));
        }
        if (tr069Profiles.isEmpty() && !configScript.isBlank()) {
            // 脚本兜底：ont tr069-server-profile add profile-id ... url ... user ...
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

    /** 概要行只带模板 ID / 名字，映射明细以 profile-id 详情为准，有才覆盖 */
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

    /**
     * 配置脚本补漏：明细回显缺 T-CONT / GEM 映射时，从 current-configuration
     * 的脚本行（tcont / gem add / gem mapping）按模板 ID 对回补齐。
     * 只补空缺，不覆盖明细回显里已经认出来的数据。
     */
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

    /** 能力模板概要只有 ID / 名字：ont-port 数量、能力项以 profile-id 详情为准 */
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

    /** 上联口才算「上行 VLAN 透传」，主控板的网口也一起算（现场 0/6/x 就是主控口） */
    private static boolean isUplinkKind(String boardKind) {
        return OltInfoParser.BoardKind.UPLINK.name().equals(boardKind)
                || OltInfoParser.BoardKind.CONTROL.name().equals(boardKind);
    }

    /** enable → config。enable 在已是特权视图时会回一句报错，忽略即可。 */
    private boolean enterConfigView(int timeout) {
        runQuietly("enable", timeout);
        String out = runQuietly("config", timeout);
        if (out == null || !out.contains("(config)#")) {
            return false;
        }
        // scroll 在特权视图才生效（登录后的用户视图发了也不认），进 config 后补一次，
        // 后面 display ont info / service-port 这类长回显就不分页了
        runQuietly("scroll", timeout);
        return true;
    }

    /**
     * 单条 ONT 详情查询（详情 / 光信息 / 版本 / 注册历史），返回原始回显。
     *
     * <p>详情（info）在 config 视图用全坐标写法：{@code display ont info 0 <槽位> <端口> <ONT ID>}，
     * 也就是现场习惯的 {@code display ont info 0 1 0 1}（机框 0、槽位 1、端口 0、ONT 1）；
     * 光信息 / 版本 / 注册历史按现场习惯进 GPON 端口视图：
     * {@code interface gpon 0/<槽位>} → {@code display ont <子命令> <端口> <ONT ID>}，
     * 命令报错时两种写法互为兜底。每次查询都新开一次登录，按需点才触发，不进轮询采集。</p>
     */
    public String queryOntDetail(int slot, int port, int ontId, String subcommand) {
        prepareTerminal();
        int timeout = commandTimeoutMs;
        if (!enterConfigView(timeout)) {
            // config 都进不去（权限不够之类）：直接试端口视图
            runCommand("interface gpon 0/" + slot, timeout);
            String out = runCommand("display ont " + subcommand + " " + port + " " + ontId, timeout);
            runQuietly("quit", timeout);
            return out;
        }
        String fullCoordinates = "display ont " + subcommand + " 0 " + slot + " " + port + " " + ontId;
        String portView = "display ont " + subcommand + " " + port + " " + ontId;
        String out;
        if ("capability".equals(subcommand)) {
            // 硬件能力现场写法：display ont capability 0/<槽位> <端口> <ONT ID>（config 视图，注意是 0/1 不是 0 1）
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
            // 光信息 / 版本 / 注册历史：现场是在端口视图敲的
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

    /**
     * 单条 ONT 的配置摘录 + 业务流表格 + 详情，返回 {配置摘录, 业务流回显, 详情回显}。
     *
     * <p>配置摘录 {@code display current-configuration ont 0/<槽位>/<端口> <ONT ID>} 带回
     * 授权行（{@code ont add ... sn-auth "..." ont-lineprofile-id 100 ont-srvprofile-id 100}）；
     * 业务流用现场习惯的 {@code display service-port port 0/<槽位>/<端口> ont <ONT ID>}，
     * 表格回显的 INDEX / VLAN ID / VLAN ATTR / VCI / FLOW PARA / STATE 对应页面上的
     * 业务流ID / OLT侧VLAN / VLAN类型 / GEM Port / 用户侧VLAN / 状态；
     * 详情 {@code display ont info 0 <槽位> <端口> <ONT ID>} 里的 ONT IP 是管理地址，
     * 点序列号时顺带取回来填「管理IP」列。
     * 老版本不认 {@code ont} 过滤时退回整口查询，由上层按 VPI（ONT ID）筛。</p>
     */
    public String[] queryOntConfigAndServicePorts(int slot, int port, int ontId) {
        prepareTerminal();
        int timeout = commandTimeoutMs;
        if (!enterConfigView(timeout)) {
            throw new IllegalStateException("进不了 config 视图，查不到 ONT 配置");
        }
        String config = runCommand("display current-configuration ont 0/" + slot + "/" + port + " " + ontId, timeout);
        String ports = runServicePortQuery(slot, port, ontId, timeout);
        String info = runCommand("display ont info 0 " + slot + " " + port + " " + ontId, timeout);
        return new String[]{config, ports, info};
    }

    /**
     * 单条 ONT 的业务流表格：{@code display service-port port 0/<槽位>/<端口> ont <ONT ID>}。
     *
     * <p>老版本不认 {@code ont} 过滤时退回整口查询，由上层按 VPI（ONT ID）筛。</p>
     */
    public String queryServicePorts(int slot, int port, int ontId) {
        prepareTerminal();
        int timeout = commandTimeoutMs;
        if (!enterConfigView(timeout)) {
            throw new IllegalStateException("进不了 config 视图，查不到业务流");
        }
        return runServicePortQuery(slot, port, ontId, timeout);
    }

    private String runServicePortQuery(int slot, int port, int ontId, int timeout) {
        String ports = runCommand("display service-port port 0/" + slot + "/" + port + " ont " + ontId, timeout);
        if (OltInfoParser.looksUnsupported(ports) || OltInfoParser.parseServicePorts(ports).isEmpty()) {
            ports = runCommand("display service-port port 0/" + slot + "/" + port, timeout);
        }
        return ports;
    }

    /**
     * 在 config 视图下逐条下发配置命令（DBA 模板增删这类写操作）。
     *
     * <p>某条被 OLT 拒绝（回显含 Error / 错误）立即中断并抛出，把 OLT 的拒绝原因带给页面；
     * 全部成功返回每条的回显。</p>
     */
    public List<String> sendConfigCommands(List<String> commands) {
        prepareTerminal();
        if (!enterConfigView(commandTimeoutMs)) {
            throw new IllegalStateException("进不了 config 视图，无法下发配置");
        }
        List<String> outputs = new ArrayList<>();
        for (String command : commands) {
            String out = runCommand(command, commandTimeoutMs);
            outputs.add(out);
            String reason = commandRejected(out);
            if (reason != null) {
                throw new IllegalStateException("OLT 拒绝命令「" + command + "」：" + reason);
            }
        }
        return outputs;
    }

    /** OLT 拒绝命令时的回显特征，返回拒绝原因那一行，没拒绝返回 null */
    private static final Pattern REJECT_ROW = Pattern.compile("(?im)^.*(Error:|错误[:：]|无效|非法|existed|not exist).*$");

    private String commandRejected(String out) {
        if (out == null || out.isBlank()) {
            return null;
        }
        Matcher matcher = REJECT_ROW.matcher(out);
        // 命令本身（回显里的 "Error:" 提示才算拒绝，提示符/参数提示不算）
        while (matcher.find()) {
            String row = matcher.group().trim();
            if (!row.startsWith("$") && !row.contains("(config")) {
                return row;
            }
        }
        return null;
    }

    /**
     * 鸿升光进配置视图：{@code enable} → {@code config}，有的固件只认 {@code configure}，
     * 所以两条都试（不支持的那条只回一句报错，代价是一个来回）。
     * 判据是提示符变成 {@code OLT(config)#}。
     */
    private boolean enterHsgqConfigView(int timeout) {
        runQuietly("enable", timeout);
        String out = runQuietly("config", timeout);
        if (out != null && out.contains("(config)#")) {
            return true;
        }
        out = runQuietly("configure", timeout);
        return out != null && out.contains("(config)#");
    }

    /**
     * 鸿升光整机采集：配置视图下 {@code show onu-info all}（每条 ONU 的 MAC 与在线状态）
     * 与 {@code show pon-info}（每口在线/总数），按 PON 号挂到对应端口上。
     *
     * <p>整机没有板卡概念，卡片是按型号生成的（{@link OltInfoParser#buildHsgqBoard}），
     * 这里只把这两条命令带回来的真实数据补进去；采不到就留空，页面显示「-」，
     * 不会把没采到当成 0，也不会打乱按型号生成的端口。</p>
     */
    private void collectHsgq(OltBoard board, List<String> notes) {
        int timeout = Math.min(commandTimeoutMs, 8000);
        if (!enterHsgqConfigView(timeout)) {
            notes.add("进不了配置视图（enable → config/configure），PON 口与 ONU 未采到");
            return;
        }
        try {
            String ponText = runCommand("show pon-info", timeout);
            String onuText = runCommand("show onu-info all", timeout);
            Map<Integer, int[]> counts = OltInfoParser.parseHsgqPonInfo(ponText);
            Map<Integer, List<OltOnt>> onts = OltInfoParser.parseHsgqOnuInfo(onuText);
            if (counts.isEmpty() && onts.isEmpty()) {
                notes.add("ONU 数量未采到：show pon-info / show onu-info all 无有效回显");
                return;
            }
            List<OltPort> ports = board.getPorts();
            if (ports == null) {
                ports = new ArrayList<>();
                board.setPorts(ports);
            }
            for (OltPort port : ports) {
                Integer ponNo = port.getPortNo();
                if (ponNo == null) {
                    continue;
                }
                int[] stat = counts.get(ponNo);
                if (stat != null) {
                    port.setOnuOnline(stat[0]);
                    port.setOnuTotal(stat[1]);
                }
                // 明细优先用 onu-info 的列表；只有 pon-info 时留空，免得列表和计数对不上
                List<OltOnt> list = onts.get(ponNo);
                if (list != null) {
                    port.setOnts(list);
                }
            }
        } finally {
            // 只退到特权视图：再 quit 一次就是 logout，设备会停下来问 y/n
            runQuietly("quit", timeout);
        }
    }

    /**
     * ONT 数量：config 视图下 {@code display ont info 0 <槽位> all}，参数是空格分隔。
     *
     * <p>一条命令就把这块板上所有端口的 ONT 明细和「In port ... the total of ONTs
     * are: N, online: M」都带回来了，所以按板查、不按端口查——8 口板省下 7 条命令，
     * 一轮采集快很多。回显里没有的端口（没配 ONT）记 0/0，页面上照样列出。</p>
     */
    private boolean collectOnts(List<OltBoard> boards, int timeout) {
        boolean any = false;
        for (OltBoard board : boards) {
            // 只有 PON 业务板挂 ONT，主控板/上联板/电源板不用问
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
                // 汇总行缺失时用明细兜底：有 ONT 明细就不能记成 0/0，否则页面把这个口画成离线
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
                any = true;
            }
        }
        return any;
    }

    /** 板卡端口数：有端口快照用快照，没有就用 display board 0 里的端口数 */
    private static int portCount(OltBoard board) {
        if (board.getPorts() != null && !board.getPorts().isEmpty()) {
            return board.getPorts().size();
        }
        return board.getPortCount() == null ? 0 : board.getPortCount();
    }

    /** ONT 数量和明细挂到端口上：端口已有（光模块采到了）就补数，没有就新建一条 */
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

    /** display ont info <机框> <槽位> all，一次拿整块板；参数是空格分隔 */
    private static String ontCommand(int slotNo) {
        return "display ont info 0 " + slotNo + " all";
    }

    /**
     * 厂商判定：版本回显 → 板卡回显 → 登录横幅 → 提示符，前面的认不出再用后面的兜底。
     *
     * <p>只认回显里的厂商关键字，命令风格不参与判定——风格是 display 的第三方 OLT
     * 不能被记成华为，否则页面走华为界面、命令走华为那一套，在第三方设备上全是报错。
     * 风格单独由 {@link #cliStyleOf} 决定，认不出厂商时按探测到的那条命令走。</p>
     */
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

    /**
     * 命令风格：认出厂商就用厂商自己的，认不出就用探测时那条版本命令的风格。
     */
    private static OltVendor.CliStyle cliStyleOf(OltVendor vendor, OltVendor.CliStyle probed) {
        if (vendor != null && vendor.getCliStyle() != null) {
            return vendor.getCliStyle();
        }
        return probed;
    }

    /**
     * 板卡命令：按厂商/风格挑一套，只有风格完全没定下来才两套都试。
     */
    private static List<String> boardCommands(OltVendor vendor, OltVendor.CliStyle style) {
        if (vendor != OltVendor.GENERIC) {
            return vendor.getBoardCommands();
        }
        if (style == OltVendor.CliStyle.DISPLAY) {
            return List.of("display board 0");
        }
        if (style == OltVendor.CliStyle.SHOW) {
            return List.of("show card", "show slot");
        }
        return BOARD_COMMANDS;
    }

    /**
     * 版本探测：{@code display version} → {@code show version}，谁回了内容就用谁的风格。
     *
     * <p>华为认 display、第三方认 show，发错了设备只回一句 Unknown command，那就换
     * 下一套；两条都不认时风格为 null，后面两套命令都试。探测用较短的超时，免得
     * 版本命令卡住把板卡采集的时间一起吃掉。</p>
     */
    private VersionProbe probeVersion() {
        int timeout = Math.min(commandTimeoutMs, VERSION_PROBE_TIMEOUT_MS);
        String versionText = "";
        OltVendor.CliStyle style = null;
        for (String command : VERSION_COMMANDS) {
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

    /**
     * 采集过程中是否出现过超时/失败（回显仍是部分数据）。
     */
    public IOException getLastError() {
        return lastError;
    }

    @Override
    public void close() {
        client.close();
    }

    // ---------------------------------------------------------------- 登录

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
    }

    /** 把这一屏登录回显记进 transcript 与登录横幅（横幅用于版本命令没回显时兜底认型号）。 */
    private void record(String out) {
        transcript.append(out);
        banner.append(out).append('\n');
    }

    /**
     * 读登录过程中的一屏，设备停在登录信息页上要回车时自动补。
     *
     * <p>先等回显静默（横幅是分几包慢慢来的），没命中任何预期提示就补一个回车再读，
     * 最多补 {@link #MAX_LOGIN_ENTER} 次。补完还是不出提示，就按超时读一把，
     * 让 {@code 等待设备回显超时} 连同已收到的文本一起抛出去。</p>
     */
    private String readLoginScreen(List<Pattern> stops, long deadline) throws IOException {
        String out = readLoginOutput(stops, deadline);
        for (int round = 0; !matchesAny(stops, out); round++) {
            if (round >= MAX_LOGIN_ENTER || remaining(deadline) < 2000) {
                return client.readUntilAny(stops, remaining(deadline));
            }
            log.info("登录回显未出现预期提示（设备可能停在登录信息页），补一个回车: 第{}次", round + 1);
            transcript.append("[补回车] ");
            client.sendLine("");
            out = readLoginOutput(stops, deadline);
        }
        return out;
    }

    /**
     * 读一段登录回显：等它静默再返回（不命中提示也不报错）。
     *
     * <p>设备说完就断开连接不算错——登录失败被踢、只回一句横幅就关连接都很常见，
     * 缓冲里已经能判定状态（登录失败提示、提示符）时按已收到的文本收尾，
     * 让调用方去判断该抛「登录失败」还是继续。</p>
     */
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

    // ---------------------------------------------------------------- 命令执行

    /**
     * 板卡温度：先试 display temperature all（一次拿全）；这版 MA5683T 会回
     * {@code % Parameter error}，只能退到按槽位 {@code display temperature 0/<槽位>}，
     * 回显是 {@code The temperature of the board:  41C( 105F)}。
     */
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

    /**
     * 关分页。此时还不知道是哪家设备，华为和中兴的命令各试一条，
     * 不支持的那条只会回一句报错，代价是一个来回。
     */
    private void prepareTerminal() {
        for (String command : OltVendor.HUAWEI.getPagingCommands()) {
            runQuietly(command, Math.min(commandTimeoutMs, 4000));
        }
        for (String command : OltVendor.ZTE.getPagingCommands()) {
            runQuietly(command, Math.min(commandTimeoutMs, 4000));
        }
    }

    /**
     * 执行命令并读到提示符，失败时返回已收到的部分回显（不抛异常，避免一条命令拖垮整次采集）。
     */
    private String runCommand(String command) {
        return runCommand(command, commandTimeoutMs);
    }

    private String runCommand(String command, int timeoutMs) {
        try {
            client.resetBuffer();
            client.sendLine(command);
            String out = readToPrompt(timeoutMs);
            transcript.append("\n$ ").append(command).append('\n').append(out).append('\n');
            return out;
        } catch (IOException e) {
            String partial = client.getText();
            lastError = e;
            log.debug("OLT 命令执行失败: {} - {}", command, e.getMessage());
            transcript.append("\n$ ").append(command)
                    .append("\n[失败] ").append(e.getMessage()).append('\n')
                    .append(partial).append('\n');
            // 超时往往意味着设备还在慢慢吐回显：先排空，否则这批旧文本会被下一条命令当成自己的输出
            drain();
            return partial;
        }
    }

    /**
     * 把设备残留的回显读干净再丢掉。命令超时后必做，不然下一条命令会立刻匹配到旧提示符，
     * 拿到一段不属于自己的文本——现象就是「同步成功但 0 块板卡」。
     */
    private void drain() {
        try {
            client.resetBuffer();
            client.readIdle(300, 2000);
        } catch (IOException e) {
            log.debug("排空残留回显失败(忽略): {}", e.getMessage());
        } finally {
            client.resetBuffer();
        }
    }

    /**
     * 尽力执行的命令：回显不参与解析，主要用于关分页。
     */
    private String runQuietly(String command, int timeoutMs) {
        try {
            client.resetBuffer();
            client.sendLine(command);
            String out = readToPrompt(timeoutMs);
            transcript.append("\n$ ").append(command).append('\n').append(out).append('\n');
            return out;
        } catch (IOException e) {
            log.debug("OLT 辅助命令失败(忽略): {} - {}", command, e.getMessage());
            transcript.append("\n$ ").append(command).append("\n[忽略] ").append(e.getMessage()).append('\n');
            return "";
        }
    }

    /**
     * 读到提示符为止。中途设备停在参数提示上（如 display version 的
     * {@code { <cr>|backplane<K>|frameid/slotid<S> };}）就补一个回车取默认值 &lt;cr&gt;，
     * 设备手册里回车即「用默认参数」，不必选具体机框。
     */
    private String readToPrompt(int timeoutMs) throws IOException {
        String out = client.readUntilAny(COMMAND_STOPS, timeoutMs);
        for (int round = 0; needsParameter(out) && round < MAX_PARAM_ROUNDS; round++) {
            // 先清掉参数提示本身再回车，避免把已到的正文一起丢掉
            transcript.append("[参数提示] ").append(out.trim()).append('\n');
            log.debug("设备停在参数提示，补回车继续读");
            client.resetBuffer();
            client.sendLine("");
            out = client.readUntilAny(COMMAND_STOPS, timeoutMs);
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
