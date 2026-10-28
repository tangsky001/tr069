package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltQuickSetupRequest;
import com.tr069.acs.olt.model.OltTr069Profile;
import com.tr069.acs.olt.model.OltTr069ProfileRequest;
import com.tr069.acs.olt.model.VlanIdExpression;
import com.tr069.acs.olt.model.OltVlan;
import com.tr069.acs.olt.model.OltVlanInfo;
import com.tr069.acs.olt.model.OltVlanPort;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 鸿升光 HSGQ-E04 等 EPON 一体化 OLT。
 *
 * <p>这类机器没有板卡插框的概念：整台设备就是一块卡、一堆 PON 口，
 * {@code show pon-info} 在部分固件上只回一声命令回显，压根采不到板卡表。
 * 所以按型号直接生成整机卡片出图（{@link #buildWholeDevice}），再把
 * {@code show pon-info} / {@code show onu-info all} 采到的真实数据补到端口上。</p>
 *
 * <p>提示符是 {@code OLT>} / {@code OLT(config)#}，进配置视图是
 * {@code enable} → {@code config}，有的固件只认 {@code configure}，两条都试。</p>
 *
 * <p><b>以后出新型号：</b>PON 口数在 {@link #PON_PORT_COUNT} 里加一行就行；
 * 型号名字是 {@code HSGQ-E08} 这种「字母 + 数字」的写法时不用加，
 * {@link #ponPortCount} 会自动从型号里抠出口数。</p>
 */
public class HsgqOltProfile extends AbstractOltVendorProfile {

    /** 没有采集到型号时的兜底（现场最常见的一台） */
    private static final String DEFAULT_MODEL = "HSGQ-E04";

    /** 型号 → PON 口数。加新型号在这里加一行 */
    private static final Map<String, Integer> PON_PORT_COUNT = Map.of(
            "HSGQ-E04", 4,
            "HSGQ-E04P", 4,
            "HSGQ-E08", 8,
            "HSGQ-E16", 16);

    /** 从型号后半段抠口数：HSGQ-E08A → 8 */
    private static final Pattern MODEL_PORT_COUNT = Pattern.compile("(?i)^HSGQ-[A-Z]*(\\d{1,2})");

    /** HSGQ-E04（show version 的 Software Version 和 Product 行里都有） */
    private static final Pattern MODEL = Pattern.compile("(?i)\\b(HSGQ-[A-Za-z0-9]{2,10})\\b");

    /** {@code show pon-info} 的端口行：{@code PON01   Up        5/5}（端口名 / 状态 / 在线数/总数） */
    private static final Pattern PON_PORT = Pattern.compile(
            "(?im)^\\s*PON(\\d{1,2})\\s+(Up|Down)\\s+(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s*$");

    /**
     * {@code show onu-info all} 的数据行：
     * {@code 1/1   c8:21:da:f8:2f:70   Online   TRUE   TRUE   2026/10/25 00:46:10   ONU01/01   NO-DESCRIPTI}。
     * 前面的 1/1 是「PON 口号/ONU 号」，后面跟着 MAC 与在线状态；末行 {@code Total: 8  Online:7  Offline:1}。
     */
    private static final Pattern ONU_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})\\s+"
                    + "([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\s+"
                    + "(Online|Offline|Initial|Init)\\b(.*)$");

    /** ONU 行剩下的列里，前两个 TRUE/FALSE 是 Auth 与 Cfg */
    private static final Pattern ONU_FLAG = Pattern.compile("(?i)\\b(TRUE|FALSE)\\b");

    /** 注册时间列：{@code 2026/10/25 00:46:10} */
    private static final Pattern ONU_REG_TIME = Pattern.compile(
            "\\d{4}/\\d{1,2}/\\d{1,2}\\s+\\d{1,2}:\\d{2}:\\d{2}");

    /** ONU 名称列：{@code ONU01/01}（各固件前缀不同，认「字母 + 数字 / 数字」这个形状） */
    private static final Pattern ONU_NAME = Pattern.compile("\\b[A-Za-z]{2,8}\\d{1,2}/\\d{1,3}\\b");

    /**
     * {@code show onu-version all} 的行（手册 6.4）：
     * {@code 1/1 ONU01/01 <MAC> HSGQ 0000 HGU 1ge V1.9.0-200326 NO-DESCRIPTION}。
     */
    private static final Pattern ONU_VERSION_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})\\s+"
                    + "(\\S+)\\s+"
                    + "([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\s+"
                    + "(\\S+)\\s+(\\S+)\\s+"
                    + "(\\S+)\\s+"
                    + "(\\S+)\\s+"
                    + "(\\S+)\\s*(.*)$");

    /**
     * {@code show optical-info} 的行（手册 6.5）：
     * {@code 1/1 ONU01/01 <MAC> 47 C 3.30 V 14 mA 2.0194 dBm -7.3119 dBm}。
     */
    private static final Pattern ONU_OPTICAL_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})\\s+(\\S+)\\s+"
                    + "([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\s+(.*)$");

    /** 光功率数值：{@code -7.3119 dBm} */
    private static final Pattern OPTICAL_DBM = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*dBm");

    /**
     * {@code show port state all} 里的端口名：{@code epon1} / {@code ge1} / {@code xge1}。
     *
     * <p>前面加 {@code (?<![a-z0-9])} 是为了不把 page、large 这种词里的 ge 当成端口。</p>
     */
    private static final Pattern PORT_STATE_PORT = Pattern.compile(
            "(?i)(?<![a-z0-9])(epon|gpon|ge|xge)\\s?(\\d{1,3})(?![0-9])");

    /** 页面传上来的端口写法：{@code epon1} / {@code ge3} */
    private static final Pattern MEMBER_PORT_REF = Pattern.compile(
            "(?i)^(epon|gpon|ge|xge)\\s*(\\d{1,3})$");

    @Override
    public OltVendor vendor() {
        return OltVendor.HSGQ;
    }

    @Override
    public boolean matches(String output) {
        return containsAny(output, "hsgq", "hongsheng", "鸿升光");
    }

    @Override
    public List<String> versionCommands() {
        return List.of("show version");
    }

    @Override
    public List<String> boardCommands() {
        // 整机设备没有板卡命令：这条只是「按 show 风格试一下」，采到了也用不上，
        // 真正的数据在配置视图的 pon-info / onu-info 里
        return List.of("show pon-info");
    }

    @Override
    public List<String> pagingCommands() {
        // 这家设备的回显都很短，且这条命令在部分固件上不认，发了只是白等一个来回
        return List.of();
    }

    @Override
    protected List<Pattern> modelPatterns() {
        return List.of(MODEL, MODEL_LABEL);
    }

    @Override
    public boolean wholeDevice() {
        return true;
    }

    /** 这台机器有几个 PON 口：先查型号表，型号表里没有就从型号的字母数字里抠 */
    public int ponPortCount(String model) {
        if (model != null && !model.isBlank()) {
            Integer known = PON_PORT_COUNT.get(model.trim().toUpperCase(Locale.ROOT));
            if (known != null) {
                return known;
            }
            Matcher matcher = MODEL_PORT_COUNT.matcher(model.trim());
            if (matcher.find()) {
                int count = Integer.parseInt(matcher.group(1));
                if (count > 0) {
                    return count;
                }
            }
        }
        return 4;
    }

    /**
     * 整机卡片：按型号生成一张卡片，端口只给名字（PON01…），
     * ONU 数量由 {@link #collectDetails} 采到真实数据后补上。
     */
    @Override
    public OltBoard buildWholeDevice(String model) {
        String resolved = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        int count = ponPortCount(resolved);
        OltBoard board = new OltBoard();
        board.setSlotNo(0);
        board.setBoardType(resolved);
        board.setStatus("Up");
        board.setPortCount(count);
        board.setPortMode("EPON");
        board.setBoardKind("SERVICE");
        board.setDescription("整机（" + count + " 口 EPON）");
        List<OltPort> ports = new ArrayList<>();
        for (int no = 1; no <= count; no++) {
            OltPort port = new OltPort();
            port.setFrameNo(0);
            port.setSlotNo(0);
            port.setPortNo(no);
            port.setPortName(String.format("PON%02d", no));
            ports.add(port);
        }
        board.setPorts(ports);
        return board;
    }

    @Override
    public Integer slotCount(String boardText, List<OltBoard> boards) {
        // 整机设备没有槽位表，也没必要画成一圈空槽位围着一台机器
        return boards == null ? null : boards.size();
    }

    @Override
    public boolean enterConfigView(OltCli cli, int timeoutMs) {
        if (cli == null) {
            return false;
        }
        cli.run("enable", timeoutMs);
        String out = cli.run("config", timeoutMs);
        if (out != null && out.contains("(config)#")) {
            return true;
        }
        out = cli.run("configure", timeoutMs);
        return out != null && out.contains("(config)#");
    }

    /**
     * 配置视图下补采三样东西：每口 ONU 数（{@code show pon-info}）、每条 ONU 明细
     * （{@code show onu-info all}）、VLAN 快照（{@code show running-config}）。
     *
     * <p>采不到就留空，页面显示「-」——不会把没采到当成 0，也不会打乱按型号生成的端口。</p>
     */
    @Override
    public void collectDetails(OltCli cli, OltSnapshot snapshot, List<OltBoard> boards,
                               List<String> notes, int timeoutMs) {
        if (cli == null || boards == null || boards.isEmpty()) {
            return;
        }
        if (!enterConfigView(cli, timeoutMs)) {
            notes.add("进不了配置视图（enable → config/configure），PON 口 / ONU / VLAN 未采到");
            return;
        }
        try {
            fillPortOnts(cli, boards, notes, timeoutMs);
            collectVlans(cli, snapshot, notes, timeoutMs);
        } finally {
            // 只退到特权视图：再 quit 一次就是 logout，设备会停下来问 y/n
            cli.run("quit", timeoutMs);
        }
    }

    /** {@code show pon-info} 给每口 ONU 在线数，{@code show onu-info all} 给每条 ONU 的 MAC 与状态 */
    private void fillPortOnts(OltCli cli, List<OltBoard> boards, List<String> notes, int timeoutMs) {
        String ponText = cli.run("show pon-info", timeoutMs);
        String onuText = cli.run("show onu-info all", timeoutMs);
        Map<Integer, int[]> counts = parsePonInfo(ponText);
        Map<Integer, List<OltOnt>> onts = parseOnuInfo(onuText);
        if (counts.isEmpty() && onts.isEmpty()) {
            notes.add("ONU 数量未采到：show pon-info / show onu-info all 无有效回显");
            return;
        }
        int detailParsed = 0;
        for (OltBoard board : boards) {
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
                    // onu-info 只有名字/MAC/状态，设备类型、ONU 类型、光功率要进 PON 口视图另采
                    if (!list.isEmpty()) {
                        detailParsed += fillOnuDetail(cli, ponNo, list, timeoutMs);
                    }
                }
            }
        }
        if (detailParsed == 0 && !onts.isEmpty()) {
            notes.add("ONU 版本 / 光功率未采到：show onu-version all / show optical-info 无有效回显"
                    + "（不影响 ONU 列表本身）");
        }
    }

    /**
     * 进 PON 口视图补两样东西（手册 6.4 / 6.5）：
     * {@code show onu-version all} 给设备类型（SFU/HGU）与 ONU 类型（1ge1fe），
     * {@code show optical-info} 给收发光功率。
     *
     * <p>按 ONU ID 合并进已有的列表，采不到的 ONU 保持空，页面显示「-」。</p>
     *
     * @return 这次解析出多少条 ONU 的附加信息，0 表示这台设备不认这两条命令
     */
    private int fillOnuDetail(OltCli cli, int ponNo, List<OltOnt> onts, int timeoutMs) {
        cli.run("interface epon " + ponNo, timeoutMs);
        String versionText = cli.run("show onu-version all", timeoutMs);
        String opticalText = cli.run("show optical-info", timeoutMs);
        cli.run("exit", timeoutMs);
        int parsed = applyOnuVersion(ponNo, onts, versionText);
        parsed += applyOpticalInfo(ponNo, onts, opticalText);
        return parsed;
    }

    /** {@code show onu-version all}：{@code 1/1 ONU01/01 <MAC> HSGQ 0000 HGU 1ge V1.9.0-xxxxxx DESC} */
    private static int applyOnuVersion(int ponNo, List<OltOnt> onts, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int parsed = 0;
        Matcher matcher = ONU_VERSION_ROW.matcher(text);
        while (matcher.find()) {
            // 回显里可能带着别的 PON 口（同一屏刷出来的），按 PON/ONU 两段一起认，别串口
            if (Integer.parseInt(matcher.group(1)) != ponNo) {
                continue;
            }
            OltOnt ont = findOnt(onts, Integer.parseInt(matcher.group(2)));
            if (ont == null) {
                continue;
            }
            ont.setDeviceType(emptyToNull(matcher.group(7)));
            ont.setOnuType(emptyToNull(matcher.group(8)));
            parsed++;
        }
        return parsed;
    }

    /** {@code show optical-info}：{@code 1/1 ONU01/01 <MAC> 47 C 3.30 V 14 mA 2.0194 dBm -7.3119 dBm} */
    private static int applyOpticalInfo(int ponNo, List<OltOnt> onts, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int parsed = 0;
        Matcher matcher = ONU_OPTICAL_ROW.matcher(text);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) != ponNo) {
                continue;
            }
            OltOnt ont = findOnt(onts, Integer.parseInt(matcher.group(2)));
            if (ont == null) {
                continue;
            }
            // 一行里两个 dBm：先发光（Tx）后收光（Rx），页面要的是最后一个
            Matcher dbm = OPTICAL_DBM.matcher(matcher.group(5));
            String last = null;
            while (dbm.find()) {
                last = dbm.group(1);
            }
            if (last != null) {
                ont.setRxPower(last + " dBm");
                parsed++;
            }
        }
        return parsed;
    }

    private static OltOnt findOnt(List<OltOnt> onts, int ontId) {
        for (OltOnt ont : onts) {
            if (ont.getOntId() != null && ont.getOntId() == ontId) {
                return ont;
            }
        }
        return null;
    }

    private static String emptyToNull(String text) {
        return text == null || text.isBlank() || "-".equals(text) ? null : text;
    }

    /**
     * VLAN 快照。
     *
     * <p>文档里查 VLAN 是 {@code show vlan}，但那条命令只在 vlan 视图
     * （{@code OLT(config-vlan-100)#}）里给单个 VLAN 的 Attribute/Value 表，
     * 想一次问清「这台设备有哪些 VLAN」只能用 {@code show running-config}：
     * 里面 {@code vlan standard 100} 一行一个 VLAN，紧跟的 {@code port epon 2 untagged}
     * 就是成员端口。running-config 没有 VLAN 段时再退回 {@code show vlan} 兜底。</p>
     */
    protected void collectVlans(OltCli cli, OltSnapshot snapshot, List<String> notes, int timeoutMs) {
        if (snapshot == null) {
            return;
        }
        List<OltVlan> vlans = parseRunningConfigVlans(cli.run("show running-config", timeoutMs));
        if (vlans.isEmpty()) {
            vlans = parseShowVlan(cli.run("show vlan", timeoutMs));
        }
        if (vlans.isEmpty()) {
            notes.add("VLAN 未采到：show running-config / show vlan 无有效回显");
        }
        // 端口清单是「创建 VLAN」弹窗的勾选项。注意 show port state all 在这家设备上只列上联电口，
        // PON 口不在这条回显里，得按型号/板卡补上，否则弹窗里只有 GE 没有 PON
        String portStateText = cli.run("show port state all", timeoutMs);
        List<OltVlanPort> memberPorts = vlanMemberPorts(snapshot, portStateText);
        if (memberPorts.stream().noneMatch(HsgqOltProfile::isUplinkPort)) {
            memberPorts.addAll(fallbackUplinkPorts());
            sortMemberPorts(memberPorts);
            notes.add("上联口未采到（show port state all 无有效回显），GE 口按 4 个给的");
        }
        OltVlanInfo info = new OltVlanInfo();
        info.setVlans(vlans);
        info.setTotal(vlans.size());
        info.setMemberPorts(memberPorts);
        info.setCollectedAt(LocalDateTime.now().withNano(0).toString().replace('T', ' '));
        snapshot.setVlanInfo(info);
    }

    /** 能做 VLAN 成员的端口类型：EPON 的 PON 口是二层口，可以进 VLAN */
    @Override
    public Set<String> vlanMemberPortTypes() {
        return Set.of("epon", "ge", "xge");
    }

    // ------------------------------------------------------------ TR-069 模板

    /**
     * TR-069（ACS）模板：不落库，页面刷新时实时 {@code show onu-tr069-profile all}，
     * 增加时当场下发 {@code onu-tr069-profile add ...}。
     *
     * <p>命令都在 {@code OLT(config)#} 下执行，和 VLAN 一样。</p>
     */
    @Override
    public boolean supportsTr069Profile() {
        return true;
    }

    @Override
    public List<String> tr069ProfileQueryCommands() {
        return List.of("show onu-tr069-profile all");
    }

    /**
     * 拼 {@code onu-tr069-profile add}：参数按关键字走，所以空值直接不写
     * （设备按关键字解析，不认位置；写了空串反而会报参数错误）。
     */
    @Override
    public List<String> createTr069ProfileCommands(OltTr069ProfileRequest request) {
        if (request == null || request.getProfileId() == null) {
            return List.of();
        }
        StringBuilder command = new StringBuilder("onu-tr069-profile add profile-id ")
                .append(request.getProfileId());
        appendTr069Arg(command, "profile-name", request.getProfileName());
        appendTr069Arg(command, "url", request.getUrl());
        appendTr069Arg(command, "acs-password", request.getAcsPassword());
        appendTr069Arg(command, "acs-username", request.getAcsUsername());
        if (request.getPort() != null) {
            command.append(" port ").append(request.getPort());
        }
        appendTr069Arg(command, "password", request.getPassword());
        appendTr069Arg(command, "username", request.getUsername());
        // 去使能定期通知时不下发这条：设备没有单独的开关，带上间隔就等于使能了
        if (Boolean.TRUE.equals(request.getInformEnable()) && request.getInformInterval() != null) {
            command.append(" inform-interval ").append(request.getInformInterval());
        }
        return List.of(command.toString());
    }

    private static void appendTr069Arg(StringBuilder command, String key, String value) {
        if (value != null && !value.isBlank()) {
            command.append(' ').append(key).append(' ').append(value.trim());
        }
    }

    // ---------------------------------------------------------------- 一键开局

    /**
     * 一键开局：按「建 VLAN → 配端口 VLAN → 建 TR-069 模板 → 存盘」的顺序编命令。
     *
     * <p><b>顺序是设备逼出来的，两步都不能反：</b></p>
     * <ul>
     *   <li>先建 VLAN 再配端口：{@code vlan trunk 41,45-46} 在 VLAN 不存在时直接报
     *       {@code Error, PORT trunk vlan set fail, reason: Specified VLANs not exsits.}</li>
     *   <li>端口成员只能靠 {@code vlan trunk/hybrid/access} 配：trunk 端口再去 VLAN 视图下
     *       {@code port epon 1-4 tagged} 会报 {@code Error, VLAN add port fail, reason:
     *       port mode is not hybrid.}——那条命令只对 hybrid 端口有意义。</li>
     * </ul>
     *
     * <p>每步可以单选：{@code createVlanIds} 空就不建 VLAN，{@code ports} 空就不动端口，
     * {@code tr069} 为 null 就不建模板。全都空会返回空表，由上层提示用户至少勾一项。</p>
     */
    @Override
    public boolean supportsQuickSetup() {
        return true;
    }

    @Override
    public List<String> quickSetupCommands(OltQuickSetupRequest request) {
        if (request == null) {
            return List.of();
        }
        List<String> commands = new ArrayList<>(quickVlanCommands(request));
        commands.addAll(quickPortModeCommands(request));
        OltTr069ProfileRequest tr069 = request.getTr069();
        if (tr069 != null && tr069.getProfileId() != null) {
            commands.addAll(createTr069ProfileCommands(tr069));
        }
        // 开局配完必须存盘：这台设备只写运行配置，不 save 的话重启/掉电全回到开局前，
        // 现场看到的就是「配了个寂寞」
        if (!commands.isEmpty()) {
            commands.add(cmdSave());
        }
        return commands;
    }

    /**
     * 开局的业务 VLAN：只建 VLAN，不在这里绑端口。
     *
     * <p>连续段直接一条 {@code vlan standard 101 110}。端口成员交给端口视图的
     * {@code vlan trunk/hybrid/access}——在 VLAN 视图下 {@code port ... tagged}
     * 只对 hybrid 端口生效，trunk 端口上去就被设备顶回来。</p>
     */
    private List<String> quickVlanCommands(OltQuickSetupRequest request) {
        String ids = request.getCreateVlanIds();
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        for (int[] segment : VlanIdExpression.segments(ids)) {
            commands.addAll(createVlanCommands(segment[0], segment[1]));
        }
        return commands;
    }

    /**
     * 逐端口：进端口视图 → 改模式 → 按模式配 VLAN → 退出。
     *
     * <p>三种模式的写法各不相同（手册 4.2）：trunk 用 {@code vlan trunk <列表>}，
     * hybrid 用 {@code vlan hybrid <列表> <tagged|untagged>}，access 用 {@code vlan access <单个>}。
     * 放通列表 = 页面上填的 + 这次新建的。</p>
     */
    private List<String> quickPortModeCommands(OltQuickSetupRequest request) {
        List<OltQuickSetupRequest.PortRef> ports = request.getPorts();
        if (ports == null || ports.isEmpty()) {
            return List.of();
        }
        String mode = request.getMode() == null || request.getMode().isBlank()
                ? "trunk"
                : request.getMode().trim().toLowerCase(Locale.ROOT);
        String vlanList = quickTrunkVlanList(request);
        String accessVlan = firstVlanId(vlanList);
        List<String> commands = new ArrayList<>();
        for (OltQuickSetupRequest.PortRef port : ports) {
            commands.add("interface " + port.getType() + " " + port.getNo());
            commands.add("vlan mode " + mode);
            if (!vlanList.isEmpty()) {
                if ("access".equals(mode)) {
                    // access 端口只属于一个 VLAN，列表填多了取第一个
                    commands.add(cmdVlanAccess(accessVlan));
                } else if ("hybrid".equals(mode)) {
                    commands.add(cmdVlanHybrid(vlanList, "tagged"));
                } else {
                    commands.add(cmdVlanTrunk(vlanList));
                }
            }
            commands.add("exit");
        }
        return commands;
    }

    /** {@code 41,45-46} → {@code 41}：access 端口只要一个 VLAN */
    private static String firstVlanId(String vlanList) {
        if (vlanList == null || vlanList.isBlank()) {
            return "";
        }
        String head = vlanList.split(",")[0].trim();
        int dash = head.indexOf('-');
        return dash > 0 ? head.substring(0, dash) : head;
    }

    /**
     * 端口要放通的 VLAN：页面上填的放通清单 + 这次要在设备上新建的。
     *
     * <p>页面上这两件事共用同一个输入框，「顺带创建」只是决定要不要多下发
     * {@code vlan standard}，所以两边会给同一份清单，必须去重——不然会拼出
     * {@code vlan trunk 41,45-46,41,45-46} 这种设备看着莫名其妙的东西。</p>
     */
    private String quickTrunkVlanList(OltQuickSetupRequest request) {
        Set<Integer> vlanIds = new TreeSet<>();
        if (request.getVlanList() != null && !request.getVlanList().isBlank()) {
            vlanIds.addAll(VlanIdExpression.expand(request.getVlanList()));
        }
        String ids = request.getCreateVlanIds();
        if (Boolean.TRUE.equals(request.getAddCreatedToPorts()) && ids != null && !ids.isBlank()) {
            vlanIds.addAll(VlanIdExpression.expand(ids));
        }
        return vlanIds.isEmpty() ? "" : formatPortNoList(new ArrayList<>(vlanIds));
    }



    /**
     * {@code show onu-tr069-profile all} 的表格：
     * <pre>
     * Profile ID  Profile Name    BindTimes
     * --------------------------------------
     * 10          acs_tr069       0
     * </pre>
     */
    private static final Pattern TR069_PROFILE_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,3})\\s+(\\S+)(?:\\s+(\\d{1,6}))?\\s*$");

    @Override
    public List<OltTr069Profile> parseTr069Profiles(String text) {
        List<OltTr069Profile> profiles = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return profiles;
        }
        Matcher matcher = TR069_PROFILE_ROW.matcher(text);
        while (matcher.find()) {
            OltTr069Profile profile = new OltTr069Profile();
            profile.setProfileId(Integer.parseInt(matcher.group(1)));
            profile.setName(matcher.group(2));
            if (matcher.group(3) != null) {
                profile.setBindTimes(Integer.parseInt(matcher.group(3)));
            }
            profiles.add(profile);
        }
        return profiles;
    }

    /**
     * 可加进 VLAN 的端口清单：PON 口 + 上联口。
     *
     * <p>两处来源拼起来，谁都不能单独胜任：{@code show port state all} 只列上联电口
     * （PON 口的状态走 {@code show pon-info}），而 PON 口号在 {@code show pon-info} 里
     * 只给「有 ONU 的那几个」，空口不一定出现——所以 PON 口按型号口数（板卡采到了就按板卡）。
     * 只保留 {@link #vlanMemberPortTypes()} 里的类型，GPON 的 gpon 口在这里就被筛掉了。</p>
     */
    protected List<OltVlanPort> vlanMemberPorts(OltSnapshot snapshot, String portStateText) {
        Map<String, OltVlanPort> merged = new LinkedHashMap<>();
        int ponCount = ponPortCount(snapshot == null ? null : snapshot.getModel());
        int boardPonCount = 0;
        if (snapshot != null && snapshot.getBoards() != null) {
            for (OltBoard board : snapshot.getBoards()) {
                boardPonCount += board.getPorts() == null ? 0 : board.getPorts().size();
            }
        }
        // 板卡里真实采到的口数优先，型号解析不出来时也准
        ponCount = Math.max(ponCount, boardPonCount);
        for (int no = 1; no <= ponCount; no++) {
            OltVlanPort port = new OltVlanPort(ponType(), no);
            merged.put(port.getType() + port.getNo(), port);
        }
        for (OltVlanPort port : parsePortStatePorts(portStateText)) {
            merged.putIfAbsent(port.getType() + port.getNo(), port);
        }
        List<OltVlanPort> ports = new ArrayList<>(merged.values());
        ports.removeIf(port -> !vlanMemberPortTypes().contains(port.getType()));
        sortMemberPorts(ports);
        return ports;
    }

    /** PON 口在命令里的关键字：EPON 是 {@code epon}；GPON 返回 {@code gpon}，随后被类型白名单筛掉 */
    protected String ponType() {
        return "epon";
    }

    /** 上联口一个都没采到时兜底给 4 个 GE，让弹窗至少有东西可勾 */
    protected List<OltVlanPort> fallbackUplinkPorts() {
        List<OltVlanPort> ports = new ArrayList<>();
        for (int no = 1; no <= 4; no++) {
            ports.add(new OltVlanPort("ge", no));
        }
        return ports;
    }

    /** 端口顺序：PON 在前、上联在后，同类型按编号——跟设备面板上看到的一致 */
    private static void sortMemberPorts(List<OltVlanPort> ports) {
        List<String> order = List.of("epon", "gpon", "ge", "xge");
        ports.sort(Comparator
                .comparingInt((OltVlanPort port) -> order.indexOf(port.getType()))
                .thenComparingInt(port -> port.getNo() == null ? 0 : port.getNo()));
    }

    private static boolean isUplinkPort(OltVlanPort port) {
        return "ge".equals(port.getType()) || "xge".equals(port.getType());
    }

    /**
     * {@code show port state all} 里的端口名 → 端口清单。
     *
     * <p>回显各固件列序不一样，但端口名一定是 {@code epon1} / {@code ge1} / {@code xge1} 这种写法，
     * 所以整段扫端口名就够了；扫不到返回空表，交给 {@link #fallbackMemberPorts} 兜底。</p>
     */
    public static List<OltVlanPort> parsePortStatePorts(String text) {
        List<OltVlanPort> ports = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return ports;
        }
        Map<String, Set<Integer>> byType = new LinkedHashMap<>();
        Matcher matcher = PORT_STATE_PORT.matcher(text);
        while (matcher.find()) {
            String type = matcher.group(1).toLowerCase(Locale.ROOT);
            byType.computeIfAbsent(type, key -> new java.util.TreeSet<>())
                    .add(Integer.parseInt(matcher.group(2)));
        }
        // 固定顺序输出：PON 在前、上联在后，跟面板上看到的顺序一致
        for (String type : List.of("epon", "gpon", "ge", "xge")) {
            Set<Integer> numbers = byType.get(type);
            if (numbers == null) {
                continue;
            }
            numbers.forEach(no -> ports.add(new OltVlanPort(type, no)));
        }
        return ports;
    }

    /**
     * 建 VLAN 并把勾选的端口加进去：每建一个 VLAN 进一次 vlan 视图下 {@code port ...}，再 exit。
     *
     * <p>建段的命令（{@code vlan standard 101 110}）只负责把 VLAN 建出来，成员端口必须在
     * 各自的 vlan 视图里配，所以段内每个 VLAN 都要单独进一次视图。</p>
     */
    @Override
    public List<String> createVlanCommands(int startVlanId, int endVlanId,
                                           List<String> tagged, List<String> untagged) {
        List<String> taggedCommands = memberPortCommands(tagged, "tagged");
        List<String> untaggedCommands = memberPortCommands(untagged, "untagged");
        if (taggedCommands.isEmpty() && untaggedCommands.isEmpty()) {
            return createVlanCommands(startVlanId, endVlanId);
        }
        // 一段多个 VLAN 时先批量建一条，省得逐个建；单个 VLAN 就别重复进视图了
        // （页面上「新增 VLAN」和「一键开局」的预览都是按最小命令数画的，这里要对得上）
        List<String> commands = new ArrayList<>();
        if (endVlanId > startVlanId) {
            commands.addAll(createVlanCommands(startVlanId, endVlanId));
        }
        for (int vlanId = startVlanId; vlanId <= endVlanId; vlanId++) {
            commands.add(cmdCreateVlan(vlanId));
            commands.addAll(taggedCommands);
            commands.addAll(untaggedCommands);
            commands.add("exit");
        }
        return commands;
    }

    /** {@code epon1, epon2, ge3} → {@code port epon 1-2 tagged} + {@code port ge 3 tagged} */
    private static List<String> memberPortCommands(List<String> ports, String mode) {
        Map<String, List<Integer>> byType = new LinkedHashMap<>();
        if (ports != null) {
            for (String port : ports) {
                Matcher matcher = MEMBER_PORT_REF.matcher(port == null ? "" : port.trim());
                if (!matcher.matches()) {
                    continue;
                }
                byType.computeIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT),
                        key -> new ArrayList<>()).add(Integer.parseInt(matcher.group(2)));
            }
        }
        List<String> commands = new ArrayList<>();
        byType.forEach((type, numbers) ->
                commands.add("port " + type + " " + formatPortNoList(numbers) + " " + mode));
        return commands;
    }

    /** 端口号列表 → 设备认的写法：连续 3 个以上合并成 {@code 1-4}，否则逗号隔开 */
    private static String formatPortNoList(List<Integer> numbers) {
        List<Integer> sorted = new ArrayList<>(new java.util.TreeSet<>(numbers));
        if (sorted.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        int start = sorted.get(0);
        int prev = start;
        for (int i = 1; i <= sorted.size(); i++) {
            int next = i == sorted.size() ? -1 : sorted.get(i);
            if (next == prev + 1) {
                prev = next;
                continue;
            }
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(start == prev ? String.valueOf(start) : start + "-" + prev);
            if (next > 0) {
                start = next;
                prev = next;
            }
        }
        return text.toString();
    }

    /**
     * {@code show pon-info}：PON 口号 → {在线数, 总数}。
     * 采不到就返回空表，页面上 ONU 列照旧显示「-」。
     */
    public static Map<Integer, int[]> parsePonInfo(String text) {
        Map<Integer, int[]> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher matcher = PON_PORT.matcher(text);
        while (matcher.find()) {
            int ponNo = Integer.parseInt(matcher.group(1));
            int online = Integer.parseInt(matcher.group(3));
            int total = Integer.parseInt(matcher.group(4));
            result.put(ponNo, new int[]{online, total});
        }
        return result;
    }

    /**
     * {@code show onu-info all}：PON 口号 → 这条 PON 口下的 ONU 列表。
     * EPON 没有 GPON 的 SN，唯一标识就是 MAC，所以 MAC 放在 sn 上（页面「SN/MAC」那列）。
     */
    public static Map<Integer, List<OltOnt>> parseOnuInfo(String text) {
        Map<Integer, List<OltOnt>> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher matcher = ONU_ROW.matcher(text);
        while (matcher.find()) {
            int ponNo = Integer.parseInt(matcher.group(1));
            OltOnt ont = new OltOnt();
            ont.setOntId(Integer.parseInt(matcher.group(2)));
            ont.setSn(matcher.group(3).toUpperCase(Locale.ROOT));
            ont.setRunState(matcher.group(4));
            ont.setOnline("online".equalsIgnoreCase(matcher.group(4)));
            fillOnuRow(ont, matcher.group(5));
            result.computeIfAbsent(ponNo, key -> new ArrayList<>()).add(ont);
        }
        return result;
    }

    /**
     * 拆 ONU 行剩下的列：Auth、Cfg、Reg-time、ONU-Name、ONU-Desc。
     *
     * <p>各固件列序一样，但描述里可能带空格，所以不能按空格切：先认两个 TRUE/FALSE
     * （认证、配置），再认日期（注册时间），再认 {@code ONU01/01} 这种名称，
     * 剩下的整段都当描述。</p>
     */
    private static void fillOnuRow(OltOnt ont, String rest) {
        if (rest == null || rest.isBlank()) {
            return;
        }
        String text = rest;
        Matcher flags = ONU_FLAG.matcher(text);
        if (flags.find()) {
            ont.setAuthorized("TRUE".equalsIgnoreCase(flags.group(1)));
        }
        Matcher time = ONU_REG_TIME.matcher(text);
        if (time.find()) {
            ont.setRegisterTime(time.group());
            text = text.replace(time.group(), " ");
        }
        text = ONU_FLAG.matcher(text).replaceAll(" ");
        Matcher name = ONU_NAME.matcher(text);
        if (name.find()) {
            ont.setName(name.group());
            text = text.replace(name.group(), " ");
        }
        String description = text.trim();
        if (!description.isEmpty()) {
            ont.setDescription(description);
        }
    }

    // ------------------------------------------------------------ VLAN

    /** VLAN ID 上限（文档：取值 1-4094） */
    public static final int MAX_VLAN_ID = 4094;

    /**
     * running-config 里的 VLAN 定义（文档 4.1）：
     * {@code vlan standard 100} 单个、{@code vlan standard 101 110} 一段连续 ID。
     */
    private static final Pattern CFG_VLAN = Pattern.compile(
            "(?im)^\\s*vlan\\s+standard\\s+(\\d{1,4})(?:\\s+(\\d{1,4}))?\\s*$");

    /**
     * vlan 视图里的成员端口：{@code port epon 2 untagged} / {@code port ge 1,3 tagged}
     * / {@code port ge 3 default-vlan}（文档 4.1 表格）。
     */
    private static final Pattern CFG_VLAN_PORT = Pattern.compile(
            "(?im)^\\s*port\\s+(epon|ge|xge)\\s+([0-9][0-9,\\-]*)\\s*(tagged|untagged|default-vlan)?");

    /** {@code show vlan} 的 VLAN ID 行：{@code VLAN ID         :100} */
    private static final Pattern SHOW_VLAN_ID = Pattern.compile("(?im)^\\s*VLAN\\s*ID\\s*[:：]\\s*(\\d{1,4})\\s*$");

    /** {@code show vlan} 的成员端口行：Tagged Ports / Untagged Ports / Default Vlan Ports */
    private static final Pattern SHOW_VLAN_FIELD = Pattern.compile(
            "(?im)^\\s*(tagged\\s*ports|untagged\\s*ports|default\\s*vlan\\s*ports)\\s*[:：]\\s*(.*?)\\s*$");

    /**
     * 从 {@code show running-config} 里取 VLAN 列表与成员端口。
     *
     * <p>按行扫描：遇到 {@code vlan standard ...} 开一个新 VLAN（连续 ID 展开成多个），
     * 紧随其后的 {@code port epon/ge/xge ... [tagged|untagged|default-vlan]} 就是它的成员端口。
     * 设备没配 VLAN 时返回空表，由 {@link #parseShowVlan} 兜底。</p>
     */
    public static List<OltVlan> parseRunningConfigVlans(String text) {
        Map<Integer, OltVlan> byId = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        List<OltVlan> current = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            Matcher vlan = CFG_VLAN.matcher(line);
            if (vlan.matches()) {
                int start = Integer.parseInt(vlan.group(1));
                int end = vlan.group(2) == null ? start : Integer.parseInt(vlan.group(2));
                if (end < start) {
                    continue;
                }
                current = new ArrayList<>();
                for (int id = start; id <= Math.min(end, MAX_VLAN_ID); id++) {
                    int vlanId = id;
                    current.add(byId.computeIfAbsent(vlanId, key -> {
                        OltVlan created = new OltVlan();
                        created.setVlanId(key);
                        created.setType("standard");
                        return created;
                    }));
                }
                continue;
            }
            Matcher member = CFG_VLAN_PORT.matcher(line);
            if (member.find() && !current.isEmpty()) {
                String ports = formatMemberPorts(member.group(1), member.group(2));
                String mode = member.group(3) == null ? "untagged" : member.group(3).toLowerCase(Locale.ROOT);
                for (OltVlan item : current) {
                    switch (mode) {
                        case "tagged" -> item.setTaggedPorts(appendPorts(item.getTaggedPorts(), ports));
                        case "default-vlan" ->
                                item.setDefaultVlanPorts(appendPorts(item.getDefaultVlanPorts(), ports));
                        default -> item.setUntaggedPorts(appendPorts(item.getUntaggedPorts(), ports));
                    }
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * {@code show vlan} 的 Attribute/Value 表（文档 4.1 / 4.1 例1、例2）：
     * <pre>
     * Attribute       Value
     * VLAN ID         :100
     * Tagged Ports    :
     * Untagged Ports  :GE03
     * Default Vlan Ports :
     * </pre>
     * 按 VLAN ID 分段，一段一个 VLAN；回显里可能有连续多段，按出现顺序收。
     */
    public static List<OltVlan> parseShowVlan(String text) {
        List<OltVlan> vlans = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return vlans;
        }
        OltVlan current = null;
        for (String line : text.split("\\r?\\n")) {
            Matcher id = SHOW_VLAN_ID.matcher(line);
            if (id.matches()) {
                current = new OltVlan();
                current.setVlanId(Integer.parseInt(id.group(1)));
                current.setType("standard");
                vlans.add(current);
                continue;
            }
            Matcher field = SHOW_VLAN_FIELD.matcher(line);
            if (field.matches() && current != null) {
                String value = field.group(2) == null || field.group(2).isBlank() ? null : field.group(2).trim();
                String key = field.group(1).toLowerCase(Locale.ROOT);
                if (key.startsWith("untagged")) {
                    current.setUntaggedPorts(value);
                } else if (key.startsWith("tagged")) {
                    current.setTaggedPorts(value);
                } else {
                    current.setDefaultVlanPorts(value);
                }
            }
        }
        return vlans;
    }

    /** {@code epon 2} → {@code EPON02}；{@code ge 1,3-5} → {@code GE01,GE03,GE04,GE05} */
    private static String formatMemberPorts(String type, String portSpec) {
        String prefix = type.toUpperCase(Locale.ROOT);
        StringBuilder text = new StringBuilder();
        for (String part : portSpec.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int dash = token.indexOf('-');
            int from;
            int to;
            try {
                if (dash > 0) {
                    from = Integer.parseInt(token.substring(0, dash).trim());
                    to = Integer.parseInt(token.substring(dash + 1).trim());
                } else {
                    from = Integer.parseInt(token);
                    to = from;
                }
            } catch (NumberFormatException e) {
                continue; // 端口写法不认识就跳过，不因为一行怪写法丢掉整份 VLAN
            }
            for (int no = from; no <= to; no++) {
                if (text.length() > 0) {
                    text.append(',');
                }
                text.append(prefix).append(String.format("%02d", no));
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    private static String appendPorts(String existing, String ports) {
        if (ports == null) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return ports;
        }
        return existing + "," + ports;
    }

    // ------------------------------------------------ 配置命令（《EPON OLT 配置命令》V3）
    // 文档里给的写法，页面上的 VLAN / 端口下发功能接上来时直接用这些，别在别处拼字符串

    /** 创建单个 VLAN：{@code vlan standard 100}（进入 vlan 视图，退出来用 exit） */
    public static String cmdCreateVlan(int vlanId) {
        return "vlan standard " + vlanId;
    }

    /** 批量创建连续 VLAN：{@code vlan standard 101 110} */
    public static String cmdCreateVlanBatch(int startVlanId, int endVlanId) {
        return "vlan standard " + startVlanId + " " + endVlanId;
    }

    /** 删除单个 VLAN：{@code no vlan 100} */
    public static String cmdDeleteVlan(int vlanId) {
        return "no vlan " + vlanId;
    }

    /** 批量删除连续 VLAN：{@code no vlan 101 110} */
    public static String cmdDeleteVlanBatch(int startVlanId, int endVlanId) {
        return "no vlan " + startVlanId + " " + endVlanId;
    }

    /** 删除所有 VLAN：{@code no vlan all} */
    public static String cmdDeleteAllVlan() {
        return "no vlan all";
    }

    /**
     * 端口放通 VLAN（trunk）：{@code vlan trunk 41,45-46}。
     *
     * <p>这些 VLAN 必须先在设备上存在，否则设备回
     * {@code Error, PORT trunk vlan set fail, reason: Specified VLANs not exsits.}</p>
     */
    public static String cmdVlanTrunk(String vlanList) {
        return "vlan trunk " + vlanList;
    }

    /** 端口 VLAN（hybrid），带标签方式：{@code vlan hybrid 41,45-46 tagged} */
    public static String cmdVlanHybrid(String vlanList, String tagMode) {
        return "vlan hybrid " + vlanList + " " + tagMode;
    }

    /** 端口 VLAN（access），只允许一个 VLAN：{@code vlan access 41} */
    public static String cmdVlanAccess(String vlanId) {
        return "vlan access " + vlanId;
    }

    /**
     * 往 VLAN 里加成员端口：{@code port ge 3 untagged}。
     *
     * @param portType epon / ge / xge
     * @param ports    端口列表写法，设备认 {@code 3} 和 {@code 1,3-4}
     * @param mode     tagged / untagged / default-vlan
     */
    public static String cmdAddVlanPort(String portType, String ports, String mode) {
        return "port " + portType + " " + ports + " " + mode;
    }

    /** 从 VLAN 里删成员端口：{@code no port ge 3} */
    public static String cmdRemoveVlanPort(String portType, String ports) {
        return "no port " + portType + " " + ports;
    }

    /** 保存配置：{@code save} */
    public static String cmdSave() {
        return "save";
    }

    // ------------------------------------------------ VLAN 下发（页面上的新增 / 删除）

    @Override
    public boolean supportsVlanConfig() {
        return true;
    }

    /** 手册里的命令动作全集（页面「设备配置」页签按章节渲染） */
    @Override
    public List<OltAction> actions() {
        return HsgqActionCatalog.actions();
    }

    /**
     * 建 VLAN：{@code vlan standard 100}（单个）或 {@code vlan standard 101 110}（一段）。
     *
     * <p>这条命令执行完提示符会切进 vlan 视图（{@code OLT(config-vlan-100)#}），
     * 后面补一条 {@code exit} 退回全局配置视图——同一批里要是还有别的命令，
     * 不然会在 vlan 视图里发，设备直接报错。</p>
     */
    @Override
    public List<String> createVlanCommands(int startVlanId, int endVlanId) {
        String create = endVlanId > startVlanId
                ? cmdCreateVlanBatch(startVlanId, endVlanId)
                : cmdCreateVlan(startVlanId);
        return List.of(create, "exit");
    }

    /**
     * 删 VLAN：{@code no vlan 100}；勾选的 ID 连续时合成一条 {@code no vlan 101 110}。
     *
     * <p>这是全局配置视图的命令，不像 {@code vlan standard} 会切视图，所以不用补 exit。</p>
     */
    @Override
    public List<String> deleteVlanCommands(List<Integer> vlanIds) {
        if (vlanIds == null || vlanIds.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = vlanIds.stream().filter(id -> id != null).distinct().sorted().toList();
        List<String> commands = new ArrayList<>();
        int start = ids.get(0);
        int prev = start;
        for (int i = 1; i <= ids.size(); i++) {
            int next = i == ids.size() ? -1 : ids.get(i);
            if (next == prev + 1) {
                prev = next;
                continue;
            }
            commands.add(prev > start ? cmdDeleteVlanBatch(start, prev) : cmdDeleteVlan(start));
            if (next > 0) {
                start = next;
                prev = next;
            }
        }
        return commands;
    }
}
