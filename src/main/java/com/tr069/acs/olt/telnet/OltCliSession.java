package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.model.OltOnt;
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
    private static final Pattern PARAM_PROMPT = Pattern.compile(
            "(?m)(?:[^\\r\\n]*\\{\\s*<cr>[^\\r\\n]*\\}|[^\\r\\n]*param(?:eter)?s?\\s*[:：][^\\r\\n]*)\\s*[:：]?\\s*$",
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

    /** 版本探测顺序：华为 → 中兴/烽火 */
    private static final List<String> VERSION_COMMANDS = List.of("display version", "show version");

    private static final List<Pattern> LOGIN_STOPS = List.of(USERNAME_PROMPT, PASSWORD_PROMPT, SHELL_PROMPT);

    private static final List<Pattern> COMMAND_STOPS = List.of(SHELL_PROMPT, PARAM_PROMPT);

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
     * <p>顺序是现场定的：登录成功先敲 {@code display board 0} 把机框/板卡拿到手，
     * 再取版本、补丁、背板，最后按槽位补温度。版本命令在部分设备上很慢（甚至要选机框），
     * 放在前面一旦超时，后面的板卡采集会一起落空。</p>
     *
     * @param withBoards 是否一并采集板卡
     */
    public OltSnapshot collect(boolean withBoards) {
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();

        // 先关分页，否则 display board / display version 都可能停在第一页
        prepareTerminal();

        // 第一步：板卡。华为 display board 0，其次中兴/烽火的 show card / show slot
        String boardText = "";
        List<OltBoard> boards = List.of();
        if (withBoards) {
            for (String command : BOARD_COMMANDS) {
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
                        ? "display board 0 无回显（命令超时或连接被断开）"
                        : "display board 0 回显无法解析：" + headLine(boardText));
            }
        }

        // 第二步：版本
        String versionText = "";
        for (String command : VERSION_COMMANDS) {
            String out = runCommand(command);
            if (out != null && !out.isBlank()) {
                versionText = out;
                if (OltVendor.detect(out) != OltVendor.GENERIC) {
                    break;
                }
            }
        }
        if (versionText.isBlank()) {
            notes.add("display version 无回显（命令超时或连接被断开）");
        }

        OltVendor vendor = detectVendor(versionText, boardText);
        String model = OltInfoParser.extractModel(vendor, versionText);
        if (model == null) {
            // 版本命令没回来时，登录横幅里通常也有型号（Huawei MA5683T ...）
            model = OltInfoParser.extractModel(vendor, banner.toString());
        }

        snapshot.setVendor(vendor);
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
            if (snapshot.getBackplane() == null) {
                snapshot.setBackplane(OltInfoParser.extractBackplaneBoard(boardText));
            }
            snapshot.setBoards(boards);
        }

        if (lastError != null) {
            notes.add("命令异常：" + lastError.getMessage());
        }
        snapshot.setNotes(notes);
        snapshot.setTranscript(transcript.toString());
        return snapshot;
    }

    /**
     * 配置视图里的一次性采集：温度、各端口 ONT 数量。
     *
     * <p>现场这台 MA5683T 的这几条命令都只在 config 视图下认，用户视图直接
     * {@code % Unknown command}。这一版的 {@code display port} 只有
     * desc/mode/statistics/vlan，没有端口光模块状态，所以不发 display port 命令，
     * 页面上只看得到端口的 ONT 数量。</p>
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
            ontOk = collectOnts(boards, timeout);
            collectVlans(boards, snapshot, timeout);
        } finally {
            // 只退到特权视图：再 quit 一次就是 logout，设备会停下来问 y/n
            runQuietly("quit", timeout);
        }
        if (!ontOk) {
            notes.add("ONT 数量未采到：display ont info 0 <槽位> all 无有效回显");
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

    /** 上联口才算「上行 VLAN 透传」，主控板的网口也一起算（现场 0/6/x 就是主控口） */
    private static boolean isUplinkKind(String boardKind) {
        return OltInfoParser.BoardKind.UPLINK.name().equals(boardKind)
                || OltInfoParser.BoardKind.CONTROL.name().equals(boardKind);
    }

    /** enable → config。enable 在已是特权视图时会回一句报错，忽略即可。 */
    private boolean enterConfigView(int timeout) {
        runQuietly("enable", timeout);
        String out = runQuietly("config", timeout);
        return out != null && out.contains("(config)#");
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
                applyOntCount(board, portNo, counts.getOrDefault(portNo, new int[]{0, 0}), details.get(portNo));
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
        String out = client.readUntilAny(LOGIN_STOPS, remaining(deadline));
        transcript.append(out);
        banner.append(out).append('\n');

        if (USERNAME_PROMPT.matcher(out).find()) {
            client.sendLine(username);
            client.resetBuffer();
            out = client.readUntilAny(List.of(PASSWORD_PROMPT, SHELL_PROMPT, LOGIN_FAILED), remaining(deadline));
            transcript.append(out);
            banner.append(out).append('\n');
        }
        if (PASSWORD_PROMPT.matcher(out).find()) {
            client.sendLine(password);
            client.resetBuffer();
            out = client.readUntilAny(List.of(SHELL_PROMPT, LOGIN_FAILED, USERNAME_PROMPT), remaining(deadline));
            transcript.append(out);
            banner.append(out).append('\n');
        }
        if (LOGIN_FAILED.matcher(out).find()) {
            throw new OltTelnetException("登录失败（账号 " + username + "）：" + lastLine(out));
        }
        if (!SHELL_PROMPT.matcher(out).find()) {
            throw new OltTelnetException("登录后未发现命令行提示符，实际回显：" + lastLine(out));
        }
        prompt = lastLine(out);
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
