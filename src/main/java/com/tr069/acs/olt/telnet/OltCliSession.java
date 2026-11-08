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
import com.tr069.acs.olt.model.OltOntDeleteResult;
import com.tr069.acs.olt.model.OltOntInfo;
import com.tr069.acs.olt.model.OltTemplateInfo;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltPortVlan;
import com.tr069.acs.olt.model.OltVlan;
import com.tr069.acs.olt.model.OltVlanInfo;
import com.tr069.acs.olt.vendor.OltVendorProfile;
import com.tr069.acs.olt.vendor.OltVendorProfiles;
import lombok.extern.slf4j.Slf4j;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 一台 OLT 的命令行会话：登录 → 识别厂商 → 采集版本/板卡 → 关闭 */
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

    /** 命令行提示符： / ZXAN# / AN5516-01# / [~HUAWEI]；必须在回显末尾，配置回显里的 # 分隔行不算 */
    private static final Pattern SHELL_PROMPT = Pattern.compile("[^\\r\\n]{0,80}[>#\\]]\\s*\\z");

    /** 命令还要参数时的提示，例如 MA5683T 的 display version */
    static final Pattern PARAM_PROMPT = Pattern.compile(
            "(?m)(?:[^\\r\\n]*\\{\\s*<cr>[^\\r\\n]*\\}"
                    // 参数补全提示后面带冒号的（如 { all<K>|bound-info<K>|... }:），回车即执行
                    + "|[^\\r\\n]*\\{[^{}]*\\}\\s*[:：]\\s*$"
                    + "|[^\\r\\n]*param(?:eter)?s?\\s*[:：][^\\r\\n]*)\\s*[:：]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 命令序列里，以这个前缀开头的行是「对上一条命令追问的应答」，不是要下发的命令 */
    public static final String ANSWER_PREFIX = ">>";

    /** 交互式命令停在追问上的样子：最后一行的行尾是冒号或问号 */
    private static final Pattern INTERACTIVE_ASK = Pattern.compile("(?m)[^\\r\\n]*[:：?？]\\s*$");

    /** 交互式命令的收尾条件：要么停在追问（要继续应答），要么回到提示符（结束） */
    private static final List<Pattern> INTERACTIVE_STOPS = List.of(INTERACTIVE_ASK, SHELL_PROMPT);

    /** 参数提示最多回几次车，防止设备反复要参数时死循环 */
    private static final int MAX_PARAM_ROUNDS = 3;

    private static final Pattern LOGIN_FAILED = Pattern.compile(
            "(?i)(login\\s+(incorrect|invalid)|authentication\\s+(fail|failed|denied)"
                    + "|password\\s+(incorrect|invalid)|access\\s+denied|permission\\s+denied"
                    + "|too\\s+many\\s+(users|failures)|invalid\\s+user"
                    + "|密码错误|用户名或密码|登录失败|认证失败)");

    /** 板卡探测顺序：华为 → 中兴/烽火。不用不带参数的 display board，那会停在 Parameter 提示上 */
    private static final List<String> BOARD_COMMANDS = List.of("display board 0", "show card", "show slot");

    /** 设备不认这条命令时的回显，说明风格发错了，换另一套再试 */
    private static final Pattern COMMAND_REJECTED = Pattern.compile(
            "(?i)(unknown\\s+command|unrecognized\\s+command|invalid\\s+(command|input)"
                    + "|incorrect\\s+command|command\\s+not\\s+found"
                    + "|错误的命令|命令不存在|不支持的命令)");

    /** 单条版本命令的超时上限：认厂商要快，不能让它把后面板卡的时间吃掉 */
    private static final int VERSION_PROBE_TIMEOUT_MS = 8000;

    /** 快速采集（只要板卡）时单条命令的超时上限：首屏等不起 20 秒 */
    private static final int BOARD_PROBE_TIMEOUT_MS = 8000;

    /** 存盘写 flash 的等待上限：大配置的 save 也要几十秒，超了说明设备真卡住了 */
    private static final int SAVE_TIMEOUT_MS = 90_000;

    /** save 回到提示符后的缓冲期：个别固件提示符回来后还在收尾，期间不再接新任务 */
    private static final long SAVE_GRACE_MS = 5_000L;

    private static final List<Pattern> COMMAND_STOPS = List.of(SHELL_PROMPT, PARAM_PROMPT);

    /** 登录过程中设备停下来要回车时，最多补几次 */
    private static final int MAX_LOGIN_ENTER = 5;

    /** 判定「这一屏打完了」的静默时间，以及单次读取的上限 */
    private static final long LOGIN_IDLE_MS = 400L;
    private static final long LOGIN_IDLE_MAX_MS = 2000L;

    private final TelnetClient client;

    private final int commandTimeoutMs;

    /** 每条命令的完整回显是否打进日志（acs.olt.log-echo，排障用，量大时关掉） */
    private final boolean logEcho;

    /** 台账里已经认出来的厂商（上次采集写进去的） */
    private final OltVendor vendorHint;

    private final StringBuilder transcript = new StringBuilder();

    /** 登录横幅，版本命令没回显时靠它识别厂商和型号 */
    private final StringBuilder banner = new StringBuilder();

    private volatile String prompt;

    /**
     * 会话当前停留的视图。长连接不退出登录，所以必须自己记住停在哪一层：
     * 下次要用 config 就直接发命令，已经在 config 里就连 enable/config 都不发。
     */
    private volatile CliView view = CliView.ROOT;

    /** 视图层级：根视图（用户/特权）、配置视图、接口/模板等子视图、位置不明 */
    public enum CliView {
        ROOT, CONFIG, SUB, UNKNOWN
    }

    /** 已经为本连接关过分页的厂商：同一厂商不再重发关分页命令 */
    private OltVendor pagingPrepared;

    /** 正在执行 save（写 flash）：期间这台设备不能接任何其它命令 */
    private volatile boolean saving;

    /** 上一次 save 回到提示符的时间；之后 SAVE_GRACE_MS 内算缓冲期 */
    private volatile long saveFinishedAt;

    /** 连接掉回登录界面（quit 退过头）：这条连接对业务失效，交给会话池重建 */
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

    /** 建立连接并完成登录；logEcho 为 true 时每条命令的完整回显打进日志（排障用） */
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
        return username == null || username.isBlank() ? DEFAULT_USERNAME : username.trim();
    }

    public static String passwordOf(OltDevice device) {
        String password = device.getPassword();
        if (password == null || password.isBlank()) {
            return DEFAULT_PASSWORD;
        }
        return com.tr069.acs.olt.util.OltCrypto.decrypt(password);
    }

    /** 采集信息 */
    /** 按页签同步的采集段：页面点进哪个页签就只采对应段，不再一次采全部 */
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

        // 长连接可能停在上一次任务留下的 config/接口视图：版本/板卡命令要在根视图发，
        // 已经在根视图时这里不发任何命令
        ensureRootView(Math.min(commandTimeoutMs, 5000));
        // 先关分页，否则版本/板卡回显都可能停在第一页
        prepareTerminal(vendorHint);

        // 第一步：判定命令风格与厂商。display version 有回显说明命令走 display 那一套
        // （华为用它，第三方也有用的），show version 有回显就走 show；厂商另外按回显关键字认
        VersionProbe probe = probeVersion();
        OltVendor vendor = detectVendor(probe.text());
        if (vendor == OltVendor.GENERIC && vendorHint != OltVendor.GENERIC) {
            // 这次没认出来（版本命令没回显之类）就沿用台账里上次认出的厂商，
            // 不然页面会当场从华为界面掉到第三方界面
            vendor = vendorHint;
        }
        String versionText = probe.text();
        if (versionText.isBlank()) {
            notes.add("display version / show version 均无回显（命令超时或连接被断开）");
        }

        // 第二步：板卡。命令按上面定下的厂商/风格挑，只有风格完全没定下来才两套都试
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
            // 版本命令没回来时，登录横幅里通常也有型号（Huawei MA5683T ...）
            model = OltInfoParser.extractModel(vendor, banner.toString());
        }
        if (withBoards && OltVendorProfiles.of(vendor).wholeDevice()) {
            // 一体化整机（鸿升光 HSGQ 这类）：不采板卡，识别出厂商后直接按型号生成整机卡片出图
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
            // sections 为空时只采版本与板卡，不进配置视图
            if (!sections.isEmpty()) {
                if (vendor == OltVendor.HUAWEI && !boards.isEmpty()) {
                    // 华为那套（温度 / 逐板端口与光模块 / ONT / VLAN / 模板）还在这里逐条编排，
                    // 没搬到 profile 里；别的新厂商在自己的 profile.collectDetails 里接管
                    collectFromConfigView(boards, notes, snapshot, sections, true);
                } else if (!boards.isEmpty()) {
                    OltVendorProfiles.of(vendor)
                            .collectDetails(this::runCommand, snapshot, boards, notes, Math.min(commandTimeoutMs, 8000));
                }
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
            // 一体化整机由 profile 给（那类设备没有槽位表，也不该画成一圈空槽位围一台机器）
            snapshot.setSlotCount(OltVendorProfiles.of(vendor).slotCount(boardText, boards));
        }

        if (lastError != null) {
            notes.add("命令异常：" + lastError.getMessage());
        }
        snapshot.setNotes(notes);
        snapshot.setTranscript(transcript.toString());
        return snapshot;
    }

    /** 快速采集：只要板卡——型号、板卡类型、所在槽位、机框槽位数，别的都不采 */
    public OltSnapshot collectBoardsOnly() {
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();
        OltVendor vendor = vendorHint != OltVendor.GENERIC ? vendorHint : detectVendor(banner.toString());
        int timeout = Math.min(commandTimeoutMs, BOARD_PROBE_TIMEOUT_MS);
        // 板卡命令是根视图的命令：上一次任务可能停在 config 里，按需退回（已在根视图不发命令）
        ensureRootView(Math.min(commandTimeoutMs, 5000));
        prepareTerminal(vendor, true);

        String boardText = "";
        List<OltBoard> boards = List.of();
        List<String> commands = boardCommands(vendor, cliStyleOf(vendor, null));
        // 一体化整机没有板卡命令可问，别为一个必然没有结果的来回付钱
        if (!OltVendorProfiles.of(vendor).wholeDevice()) {
            for (String command : commands) {
                String out = runCommand(command, timeout);
                if (out == null || out.isBlank()) {
                    continue; // 这条不认（命令风格不对），换下一条
                }
                // 有回显就收手：解析不出板卡再问一遍拿到的还是同一段文本，只白等一个超时
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
            // 台账里的厂商跟这台设备对不上（换过设备 / 手填错）：换另一套命令再试一条
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
            // 横幅和板卡回显里都没有型号（部分版本横幅只有版权声明），补一条版本命令；
            // 一体化整机没有板卡回显，型号只能从版本命令里来
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
            // 一体化整机没有板卡概念：按型号生成一张卡片，PON 口与 ONU 数据留给深度采集
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

    /** 配置视图里的一次性采集：温度、端口状态、ONT 数量、光模块数值、VLAN、模板 */
    private void collectFromConfigView(List<OltBoard> boards, List<String> notes, OltSnapshot snapshot,
                                       Set<Section> sections, boolean enrichOntDetails) {
        int timeout = Math.min(commandTimeoutMs, 8000);
        if (!enterConfigView(timeout)) {
            notes.add("进不了配置视图（enable → config），温度/ONT/VLAN 未采到");
            return;
        }
        // 任务结束就停在 config 里，不再多退一层：下个任务照样在 config 里干活，
        // enterConfigView 会直接短路；真要回根视图（版本/板卡命令）由 ensureRootView 按需退
        if (sections.contains(Section.TEMPERATURES)) {
            applyTemperatures(boards, timeout);
        }
        // 先把每块板的端口（含光模块不在位、链路离线的空口）建出来，ONT 再往上面挂
        if (sections.contains(Section.PORT_STATES)) {
            collectBoardPortStates(boards, timeout);
        }
        boolean ontOk = !sections.contains(Section.ONTS) || collectOnts(boards, timeout, notes, enrichOntDetails);
        // 光模块数值（发光/收光/温度/偏置/电压）：端口建好之后再逐口问
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

    /** 按页签同步的入口：只采指定段。板卡列表用库里那份（判断业务板/上行口/端口数足够了） */
    public OltSnapshot collectSections(List<OltBoard> boards, Set<Section> sections) throws OltTelnetException {
        return collectSections(boards, sections, true);
    }

    /** 不逐台补管理 IP / MAC / 模板号——那部分一台一条命令 */
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

    /** 只补 ONT 单台详情：对缺管理 IP / MAC / 模板号的 ONT 逐台发 */
    public OltSnapshot collectOntDetails(List<OltBoard> boards) throws OltTelnetException {
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();
        List<OltBoard> workBoards = boards == null ? List.of() : boards;
        prepareTerminal(vendorHint);
        int timeout = Math.min(commandTimeoutMs, 8000);
        if (!enterConfigView(timeout)) {
            notes.add("进不了配置视图（enable → config），ONT 详情未补采");
        } else {
            // 补完就停在 config 里：下次任务直接接着用，不额外退视图
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

    /** 逐板发 {@code display board 0/<槽位>}，把端口表采回来：光模块在位状态 */
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

    /** 逐口采光模块数值：{@code interface gpon 0/<槽位>} 进接口视图，再 */
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
                // 逐口光模块是秒回命令：给它 3 秒就够。空口 / 不认这条命令的口
                // 等满 8 秒纯属白等，8 口就是一分多钟
                int portTimeout = Math.min(timeout, 3000);
                for (int portNo = 0; portNo < count; portNo++) {
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

    /** 单端口光模块查询：进接口视图发一条 display port state，返回原始回显 */
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
            // EPON 板卡的接口视图是 interface epon
            runQuietly("interface epon 0/" + slot, timeout);
        }
        try {
            return runCommand("display port state " + portNo, timeout);
        } finally {
            runQuietly("quit", timeout);
        }
    }

    /** 光模块数值挂到端口上：补空字段，不覆盖 display board 采到的在位状态和链路状态 */
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
        // Optical Module status 顺带把「在位」补上：display board 没给出状态时用它兜底
        String moduleStatus = optics.getModuleStatus();
        if (port.getOpticalPresent() == null && moduleStatus != null) {
            String state = moduleStatus.toLowerCase(Locale.ROOT);
            port.setOpticStatus(state);
            port.setOpticalPresent(!(state.contains("offline") || state.contains("absent")));
        }
    }

    /** VLAN：{@code display vlan all} 一条拿 VLAN 列表，再对上行口/主控口逐个 */
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
            // 逐口 VLAN 也是秒回命令，同样不占用 8 秒超时
            int portTimeout = Math.min(timeout, 3000);
            for (int portNo = 0; portNo < count; portNo++) {
                String text = runCommand("display port vlan 0/" + board.getSlotNo() + "/" + portNo, portTimeout);
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

    /** 只重读一条线路模板：加完 T-CONT / GEM Port / GEM 映射后刷新页面用 */
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
            // 退回 config 视图：留在模板视图里下次登录的状态就对不上了
            runQuietly("quit", timeout);
        }
    }

    /** 清空业务板上的全部 ONT：现场怎么敲这里就怎么发，不跑全量采集、不探版本/温度 */
    public List<OltOntDeleteResult> deleteBoardOnts(List<Integer> wantedSlots) throws OltTelnetException {
        // display board 0 要在根视图发：上一次任务留下的 config/接口视图先按需退回
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

    /** {@code display board 0} → 只要业务板（PON 板）；指定了槽位就只留那几块 */
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

    /** 一块板的完整删除流程：拆业务虚口 → 进 PON 板视图 → 逐口 ont delete all（答 y）→ quit */
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
        // 先在 config 视图把这块板上所有业务虚口拆干净：带着 service-port 引用的
        // ont delete 会被设备顶回来，就算删掉也会留下一堆指向空 ONT 的死流
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
            log.info("清板 {} 前先拆掉 {} 条业务虚口", slot, undoCommands.size());
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
        // 退回 config 视图：留在接口视图里，下一块板的命令就发错地方了
        runQuietly("quit", timeout);
        return result;
    }

    /** {@code display board 0/2} 回显里「挂了 ONT 的端口」：认每口末尾那句 total of ONTs */
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

    /** 只重采 VLAN：{@code display vlan all} + 各上行/主控口 {@code display port vlan} */
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
            // 只退到特权视图：再 quit 一次就是 logout，设备会停下来问 y/n
            runQuietly("quit", timeout);
        }
        return snapshot.getVlanInfo();
    }

    /** DBA 模板与线路模板：{@code display dba-profile all} + */
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

    /** 配置脚本补漏：明细回显缺 T-CONT / GEM 映射时，从 current-configuration */
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

    /** 进配置视图，写法由厂商 profile 决定（华为 {@code enable}→{@code config} 后还要补一条 */
    /**
     * 进配置视图：会话已经停在 config 里就直接返回，连 enable / config 都不发；
     * 停在接口/模板等子视图就先按层级退回根视图再进。长连接下这是省命令的关键。
     */
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

    /** 台账里认出来的这家设备对应的 profile（写操作、单条查询这些按设备走缓存里的厂商） */
    private OltVendorProfile profile() {
        return OltVendorProfiles.of(vendorHint);
    }

    /** 单条 ONT 详情查询（详情 / 光信息 / 版本 / 注册历史），返回原始回显 */
    public String queryOntDetail(int slot, int port, int ontId, String subcommand) {
        prepareTerminal(vendorHint);
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

    /** 单条 ONT 的配置摘录 + 业务流表格 + 详情，返回 {配置摘录, 业务流回显, 详情回显} */
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

    /** 单条 ONT 的业务流表格：{@code display service-port port 0/<槽位>/<端口> ont } */
    public String queryServicePorts(int slot, int port, int ontId) {
        prepareTerminal(vendorHint);
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
        // 命令和它的应答要一起发：交互式命令（terminal user name）发完会停在
        // 追问上等输入，逐条独立下发的话中间隔着一个等不来的提示符
        for (List<String> group : groupWithAnswers(commands)) {
            String command = group.get(0);
            List<String> answers = group.subList(1, group.size());
            // save 是特殊命令：先停在 y/n 确认，答完写 flash 好几十秒，期间设备不接任何活
            String out = isSaveCommand(command) ? runSave()
                    : answers.isEmpty() ? runCommand(command, commandTimeoutMs) : runInteractive(command, answers);
            outputs.add(out);
            String reason = commandRejected(out);
            if (reason == null) {
                continue;
            }
            if (tolerateExisting && ALREADY_EXISTS.matcher(reason).find()) {
                log.info("设备上已有该配置，跳过「{}」：{}", command, reason);
                continue;
            }
            throw new IllegalStateException("OLT 拒绝命令「" + command + "」：" + reason);
        }
        return outputs;
    }

    /**
     * 存盘专用：华为的 {@code save} 先停在「Are you sure ... (y/n)[n]:」上，
     * 答完写 flash 好几十秒（期间设备不接任何命令），直到提示符回来才算完。
     * 不专门等完就返回的话，下一条任务会在设备还在写 flash 时发进去，必然被拒。
     */
    private String runSave() {
        long start = System.currentTimeMillis();
        saving = true;
        StringBuilder whole = new StringBuilder();
        try {
            client.resetBuffer();
            client.sendLine("save");
            String out = client.readUntilAny(INTERACTIVE_STOPS, Math.min(commandTimeoutMs, 8000));
            whole.append(out);
            if (isAsking(out)) {
                // 停在「Are you sure ... (y/n)[n]:」：答 y 才真的会存
                transcript.append("[应答] y\n");
                client.resetBuffer();
                client.sendLine("y");
                out = client.readUntilAny(List.of(SHELL_PROMPT), SAVE_TIMEOUT_MS);
                whole.append(out);
            } else if (!SHELL_PROMPT.matcher(tailOf(out)).find()) {
                // 没问确认也没回提示符：设备已经在写 flash，继续等到提示符回来
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
            log.info("OLT 存盘执行失败: {}", e.getMessage());
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

    /** 刚保存完配置还在缓冲期内：个别固件提示符回来后仍在收尾，不宜马上发命令 */
    public boolean inSaveGrace() {
        return saveFinishedAt > 0 && System.currentTimeMillis() - saveFinishedAt < SAVE_GRACE_MS;
    }

    /** 下发一条会逐步追问参数的命令，并按顺序把每个应答发过去 */
    public String runInteractive(String command, List<String> answers) {
        StringBuilder whole = new StringBuilder();
        try {
            client.resetBuffer();
            client.sendLine(command);
            String out = client.readUntilAny(INTERACTIVE_STOPS, commandTimeoutMs);
            whole.append(out);
            for (String answer : answers) {
                if (!isAsking(out)) {
                    break; // 设备不问了（多半已经报错或者收工了），别再往里灌
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
            log.debug("OLT 交互命令执行失败: {} - {}", command, e.getMessage());
            transcript.append("\n$ ").append(command)
                    .append("\n[失败] ").append(e.getMessage()).append('\n')
                    .append(partial).append('\n');
            drain();
            return whole.length() > 0 ? whole.toString() : partial;
        }
    }

    /** 把命令序列按「一条命令 + 它的应答」切成若干组，{@code >>} 开头的行归到上一条命令 */
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
            return false; // 已经回到提示符，这次交互结束了
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

    /** OLT 拒绝命令时的回显特征，返回拒绝原因那一行，没拒绝返回 null */
    private static final Pattern REJECT_ROW = Pattern.compile(
            "(?im)^.*(\\berror\\b|\\bfail(?:ed|ure)?\\b|错误[:：]?|无效|非法|not\\s+exist|exsits|不存在).*$");

    /** 设备说「这个东西已经有了」 */
    private static final Pattern ALREADY_EXISTS = Pattern.compile(
            // 设备说「这件事已经做过了」的几种说法，实测遇到的：
            //   Failure: VLAN has existed                        重复建 VLAN
            //   Failure: The port is already in the VLAN         重复把口加进 VLAN
            //   Failure: The dba profile already exists          重复建模板
            //   Failure: Make configuration repeatedly           重复下发同一条配置
            // already / has been 后面跟的动词不枚举了——设备版本之间措辞不一样
            // （added / created / configured / bound），漏一个就得再跑一趟。
            "(?i)(has\\s+existed|has\\s+been\\s+\\w+|already\\s+\\w+|repeat\\w*|exsits|已存在|已经存在)");

    /** 「失败数为 0」不是失败 */
    private static final Pattern ZERO_FAILURES = Pattern.compile("(?i)\\bfailed\\s*[:：]?\\s*0\\b");

    private String commandRejected(String out) {
        if (out == null || out.isBlank()) {
            return null;
        }
        // 先把「零失败」的统计抹掉，留下来的才算真的拒绝
        Matcher matcher = REJECT_ROW.matcher(ZERO_FAILURES.matcher(out).replaceAll(" "));
        // 命令本身（回显里的 "Error:" 提示才算拒绝，提示符/参数提示不算）
        while (matcher.find()) {
            String row = matcher.group().trim();
            if (!row.startsWith("$") && !row.contains("(config")) {
                return row;
            }
        }
        return null;
    }


    /** ONT 数量：config 视图下 {@code display ont info 0 <槽位> all}，参数是空格分隔 */
    private boolean collectOnts(List<OltBoard> boards, int timeout, List<String> notes,
                                boolean enrichOntDetails) {
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
            if (details.isEmpty() && !counts.isEmpty()) {
                // 汇总行认到了、明细行一条没拆出来：回显格式可能变了。
                // 写库时这种「有数量没明细」的端口会沿旧明细（不被抹），这里把异常亮出来
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
                if (enrichOntDetails) {
                    enrichOntProfiles(board.getSlotNo(), portNo, list, timeout, notes);
                }
                any = true;
            }
        }
        return any;
    }

    /** 给还缺 MAC 的在线 ONT 发 display ont ipconfig，补到就跳过 */
    private void enrichOntProfiles(Integer slotNo, int portNo, List<OltOnt> onts, int timeout,
                                   List<String> notes) {
        if (slotNo == null || onts == null || onts.isEmpty()) {
            return;
        }
        // 部分版本的单台详情不给 MAC（现场这台 MA5683T 就没有），只有 ipconfig 里有
        if (onts.stream().anyMatch(ont -> ont.getMac() == null && !Boolean.FALSE.equals(ont.getOnline())
                && !Boolean.TRUE.equals(ont.getMacUnavailable()))) {
            enrichOntIpconfig(slotNo, portNo, onts, timeout, notes);
        }
    }

    /** 补 MAC / 管理地址：{@code interface gpon 0/<槽位>} 进接口视图 */
    private void enrichOntIpconfig(Integer slotNo, int portNo, List<OltOnt> onts, int timeout,
                                   List<String> notes) {
        runCommand("interface gpon 0/" + slotNo, timeout);
        try {
            boolean noted = false;
            for (OltOnt ont : onts) {
                if (ont.getOntId() == null || ont.getMac() != null
                        || Boolean.FALSE.equals(ont.getOnline())
                        || Boolean.TRUE.equals(ont.getMacUnavailable())) {
                    continue;
                }
                String text = runCommand("display ont ipconfig " + portNo + " " + ont.getOntId(), timeout);
                if (text != null && OltInfoParser.looksUnsupported(text)) {
                    // 端口视图写法不认：退回 config 视图换全坐标写法，再回到接口视图继续
                    runQuietly("quit", timeout);
                    text = runCommand("display ont ipconfig 0 " + slotNo + " " + portNo + " " + ont.getOntId(),
                            timeout);
                    runCommand("interface gpon 0/" + slotNo, timeout);
                }
                if (text == null || text.isBlank()) {
                    continue;
                }
                OltOntInfo info = OltInfoParser.parseOntIpconfig(text);
                // 只落 MAC：管理 IP 属于「点开详情」才采的数据，这条命令顺带给的也不入库
                if (ont.getMac() == null && info.getMac() != null) {
                    ont.setMac(info.getMac());
                }
                // 设备明确回了 0000-0000-0000：这台没学到 MAC，标记一下，下一轮别再问它
                if (ont.getMac() == null && ZERO_MAC.matcher(text).find()) {
                    ont.setMacUnavailable(Boolean.TRUE);
                }
                // 采完还是没 MAC：把回显片段记下来（每端口只记第一条，摘要别刷屏）
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
            // 退回 config 视图，后面的端口继续按原流程采
            runQuietly("quit", timeout);
        }
    }

    /** {@code interface gpon 0/<槽位>} 段头，用来定位后面 ont add 行属于哪个槽位 */
    private static final Pattern PON_INTERFACE = Pattern.compile(
            "(?i)^\\s*interface\\s+(gpon|epon)\\s+0/(\\d+)");

    /** {@code ont add <端口> <ONT编号> sn-auth ...}：接口视图下的授权行 */
    private static final Pattern ONT_ADD_ROW = Pattern.compile(
            "(?i)^\\s*ont\\s+add\\s+(\\d+)\\s+(\\d+)\\s+\\S+");

    private static final Pattern LINE_PROFILE_ID = Pattern.compile(
            "(?i)ont-lineprofile-id\\s+(\\d{1,6})");

    private static final Pattern SRV_PROFILE_ID = Pattern.compile(
            "(?i)ont-srvprofile-id\\s+(\\d{1,6})");

    /** 批量补 ONT 模板号：拉一次配置脚本，按槽位/端口/ONT 号对回去，返回补上台数 */
    public int fillOntProfilesFromConfig(List<OltBoard> boards, List<String> notes) {
        if (boards == null || boards.isEmpty()) {
            return 0;
        }
        int timeout = Math.min(commandTimeoutMs, 60000);
        if (!enterConfigView(timeout)) {
            notes.add("进不了配置视图（enable → config），ONT 模板号未补采");
            return 0;
        }
        // 先试只取 ont 段的分段写法，设备不认再退回全量配置
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
            // 模板号可能和 ont add 同行，也可能被设备折到下一两行
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
            // 配置行找到了但模板号缺一个：设备配置本身就没写，再拉一遍也一样，标记跳过
            if (lineId == null || srvId == null) {
                ont.setProfileUnavailable(Boolean.TRUE);
            }
            if (changed) {
                filled++;
            }
        }
        // 配置里压根找不到这个 ONT（比如 EPON 段 / 未授权）：同样标记，避免每轮白拉一次全量配置
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

    /** {@code ONT MAC : 0000-0000-0000}：ONT 桥接 / 没学到 MAC 时设备就这么回 */
    private static final java.util.regex.Pattern ZERO_MAC = java.util.regex.Pattern.compile(
            "(?im)^\\s*ONT\\s+MAC[^:：\\r\\n]*[:：]\\s*0{4}[-:]?0{4}[-:]?0{4}\\s*$");

    /** 回显里的 IP 字段是「-」说明没拿到地址（DHCP 未获取），不算采到 */
    private static String validIp(String ip) {
        if (ip == null || ip.isBlank() || "-".equals(ip.trim())) {
            return null;
        }
        return ip.trim();
    }

    /** MAC 优先取单台详情回显里的 MAC 字段；设备没给时，MAC 认证（EPON）的认证码 */
    private static String resolveMac(String deviceMac, String authCode) {
        if (deviceMac != null && !deviceMac.isBlank()) {
            return OltInfoParser.normalizeMac(deviceMac);
        }
        if (authCode == null) {
            return null;
        }
        return OltInfoParser.normalizeMac(authCode.split(" ")[0]);
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

    /** 厂商判定：版本回显 → 板卡回显 → 登录横幅 → 提示符，前面的认不出再用后面的兜底 */
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

    /** 命令风格：认出厂商就用厂商自己的，认不出就用探测时那条版本命令的风格 */
    private static OltVendor.CliStyle cliStyleOf(OltVendor vendor, OltVendor.CliStyle probed) {
        if (vendor != null && vendor.getCliStyle() != null) {
            return vendor.getCliStyle();
        }
        return probed;
    }

    /** 板卡命令：按厂商/风格挑一套，只有风格完全没定下来才两套都试 */
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

    /** 版本探测：{@code display version} → {@code show version}，谁回了内容就用谁的风格 */
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

    /** 版本命令清单：认出厂商就用厂商自己的，省掉一整轮探测来回 */
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

    /** 采集过程中是否出现过超时/失败（回显仍是部分数据） */
    public IOException getLastError() {
        return lastError;
    }

    @Override
    public void close() {
        client.close();
    }

    /** 连接是否还活着：只看通道状态，不发任何探测命令（长连接不能为「体检」去占设备一次往返） */
    public boolean isAlive() {
        return !sessionLost && client.isAlive();
    }

    /** 一条新任务开始：清上一轮的记录，登录态与当前视图原样保留，不做任何复位 */
    public void beginTask() {
        transcript.setLength(0);
        lastError = null;
    }

    /** 当前停留的视图（页面/日志排障用） */
    public CliView currentView() {
        return view;
    }

    /**
     * 探活：只发一个回车看提示符回不回得来，不改视图、不发业务命令。
     * 保活巡检用它，比 {@code display client} 轻，而且任何视图下都成立。
     */
    public boolean ping(int timeoutMs) {
        return probeView(timeoutMs);
    }

    /** 敲一个回车拿提示符，顺便校准当前视图；位置不明时用它，绝不盲发 quit */
    private boolean probeView(int timeoutMs) {
        try {
            client.resetBuffer();
            client.sendLine("");
            String out = client.readUntilAny(List.of(SHELL_PROMPT), Math.min(5000, Math.max(1000, timeoutMs)));
            if (USERNAME_PROMPT.matcher(out).find() || PASSWORD_PROMPT.matcher(out).find()) {
                // 已经回到登录界面：这条连接不能再复用，池里下次会重新登录
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

    /**
     * 退回根视图（特权/用户视图）。已经在根视图时一条命令都不发——
     * 多发的那个 quit 会直接退出登录，白丢一次 OLT 的登录名额。
     */
    private boolean ensureRootView(int timeoutMs) {
        for (int round = 0; round < 4 && view != CliView.ROOT; round++) {
            if (view == CliView.UNKNOWN && !probeView(timeoutMs)) {
                return false;
            }
            if (view == CliView.ROOT) {
                break;
            }
            String out = runQuietly("quit", timeoutMs);
            // 某些设备 quit 会停下来问 y/n（视图层级与预期不同）：答 n 留在设备里，不退出登录
            if (isAsking(out)) {
                runQuietly("n", timeoutMs);
            }
            if (out == null || out.isBlank() || USERNAME_PROMPT.matcher(out).find()) {
                // 已经退到登录界面：这条连接对业务来说不能再用了，交给会话池重建
                sessionLost = true;
                view = CliView.UNKNOWN;
                return false;
            }
        }
        return view == CliView.ROOT;
    }

    /** 从回显末尾的提示符判断停在哪一层视图（包内可见，便于单测） */
    static CliView viewOf(String out, CliView current) {
        String line = lastLine(out);
        if (line.isEmpty()) {
            return current;
        }
        String prompt = line.toLowerCase(Locale.ROOT);
        // 接口/模板/aaa 这类子视图：config 后面带短横线的一律算子视图，不能当成 config
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

    /** 退出登录过程中的停点：y/n 确认、回到提示符（quit 不被认时）、重新出登录提示 */
    private static final List<Pattern> LOGOUT_STOPS = List.of(INTERACTIVE_ASK, SHELL_PROMPT, USERNAME_PROMPT);

    /** 优雅退出登录并关闭连接：发 {@code quit}，停在「确认退出? (y/n)」上就答 y */
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
                // 连接已被设备断开（读到空），或者退回到登录界面，就是退出完成
                if (out == null || out.isBlank() || USERNAME_PROMPT.matcher(out).find()) {
                    break;
                }
            }
        } catch (IOException e) {
            // 设备退出登录后直接断连接，读抛异常是正常收尾
        } finally {
            close();
        }
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
        // 登录成功必然停在根视图：后续任务从这里开始沿用，不再重新登录
        view = CliView.ROOT;
    }

    /** 把这一屏登录回显记进 transcript 与登录横幅（横幅用于版本命令没回显时兜底认型号） */
    private void record(String out) {
        transcript.append(out);
        banner.append(out).append('\n');
    }

    /** 读登录过程中的一屏，设备停在登录信息页上要回车时自动补 */
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

    /** 读一段登录回显：等它静默再返回（不命中提示也不报错） */
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

    /** 板卡温度：先试 display temperature all（一次拿全）；这版 MA5683T 会回 */
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

    /** {@code display board 0} 顶多二十来行，一条 scroll 关掉分页就够了 */
    private void prepareTerminal(OltVendor vendor, boolean firstOnly) {
        // 长连接下关分页的效果是持续的：同一厂商已关过就不再重发，省掉每条任务的开场命令
        if (vendor != null && vendor != OltVendor.GENERIC && pagingPrepared == vendor) {
            return;
        }
        int timeout = Math.min(commandTimeoutMs, 4000);
        if (vendor != null && vendor != OltVendor.GENERIC) {
            List<String> commands = OltVendorProfiles.of(vendor).pagingCommands();
            if (runPaging(firstOnly && !commands.isEmpty() ? commands.subList(0, 1) : commands, timeout)) {
                pagingPrepared = vendor;
            }
            return;
        }
        List<String> huawei = OltVendorProfiles.of(OltVendor.HUAWEI).pagingCommands();
        List<String> thirdParty = OltVendorProfiles.of(OltVendor.ZTE).pagingCommands();
        List<String> generic = OltVendorProfiles.of(OltVendor.GENERIC).pagingCommands();
        if (firstOnly) {
            // 认不出厂商：华为的 scroll 与第三方通用的 terminal length 0 各来一条
            runPaging(List.of(first(huawei), first(generic)), timeout);
            return;
        }
        List<String> all = new ArrayList<>(huawei);
        all.addAll(thirdParty);
        all.addAll(generic);
        runPaging(all, timeout);
    }

    /** 逐条关分页，设备认下第一条就收手；返回是否有关分页命令被设备接受 */
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

    /** 执行命令并读到提示符，失败时返回已收到的部分回显（不抛异常，避免一条命令拖垮整次采集） */
    private String runCommand(String command) {
        return runCommand(command, commandTimeoutMs);
    }

    private String runCommand(String command, int timeoutMs) {
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
            // 超时往往意味着设备还在慢慢吐回显：先排空，否则这批旧文本会被下一条命令当成自己的输出
            drain();
            // 超时后会话视图不确定：标记视图为 UNKNOWN，下次复用前会先 probeView 校准
            view = CliView.UNKNOWN;
            // 如果排空后发现已经退到登录界面，直接标记会话失效
            if (partial != null && (USERNAME_PROMPT.matcher(partial).find() || PASSWORD_PROMPT.matcher(partial).find())) {
                sessionLost = true;
            }
            return partial;
        }
    }

    /** 命令耗时打点：发送 → 首字节（设备处理）→ 完成（收完回显），用来定位慢在哪一环；logEcho 时连回显一起打 */
    private void logCommand(String command, long start, String echo, String error) {
        long firstByte = client.firstByteAt();
        String firstByteText = firstByte > 0 ? (firstByte - start) + "ms" : "无回显";
        int echoChars = echo == null ? 0 : echo.length();
        if (error == null) {
            log.info("OLT 命令耗时 [{}] 首字节 {} / 完成 {}ms / 回显 {} 字符",
                    command, firstByteText, System.currentTimeMillis() - start, echoChars);
        } else {
            log.info("OLT 命令耗时 [{}] 首字节 {} / 失败于 {}ms / 已收 {} 字符: {}",
                    command, firstByteText, System.currentTimeMillis() - start, echoChars, error);
        }
        if (logEcho && echoChars > 0) {
            log.info("OLT 回显 [{}] {} 字符:\n{}", command, echoChars, capEcho(echo));
        }
    }

    /** 日志里的回显超长截断，完整内容仍在采集记录里 */
    private static String capEcho(String echo) {
        int limit = 20000;
        if (echo.length() <= limit) {
            return echo;
        }
        return echo.substring(0, limit) + "\n...[日志截断，共 " + echo.length()
                + " 字符，完整回显见采集记录]";
    }

    /** 把设备残留的回显读干净再丢掉。命令超时后必做，不然下一条命令会立刻匹配到旧提示符 */
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

    /** 尽力执行的命令：回显不参与解析，主要用于关分页 */
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
            log.debug("OLT 辅助命令失败(忽略): {} - {}", command, e.getMessage());
            transcript.append("\n$ ").append(command).append(' ').append(cost(start))
                    .append("\n[忽略] ").append(e.getMessage()).append('\n');
            return "";
        }
    }

    /** 回显里给每条命令标一下往返耗时：慢在哪条一眼能看出来 */
    private static String cost(long start) {
        return "[" + (System.currentTimeMillis() - start) + "ms]";
    }

    /** 读到提示符为止。中途设备停在参数提示上（如 display version 的 */
    private String readToPrompt(int timeoutMs) throws IOException {
        // pageAssist=true：长输出卡在分页上时，不等 More 提示被识别，看门狗直接按键推进
        String out = client.readUntilAny(COMMAND_STOPS, timeoutMs, true);
        for (int round = 0; needsParameter(out) && round < MAX_PARAM_ROUNDS; round++) {
            // 先清掉参数提示本身再回车，避免把已到的正文一起丢掉
            transcript.append("[参数提示] ").append(out.trim()).append('\n');
            log.debug("设备停在参数提示，补回车继续读");
            client.resetBuffer();
            client.sendLine("");
            out = client.readUntilAny(COMMAND_STOPS, timeoutMs, true);
        }
        // 每条命令的提示符都在回显末尾：顺手更新视图，下一次就知道自己停在哪一层
        view = viewOf(out, view);
        // 设备把空闲连接踢回登录界面时 TCP 不一定断：识别出来标记失效，让会话池重建，
        // 否则后续命令永远发在登录提示符上，自动注册这类后台任务会一直失败且不恢复
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
