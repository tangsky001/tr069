/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.607
 *
 */

package com.PilotACS.olt.vendor.Hsgq;

import com.PilotACS.olt.entity.OltBoard;
import com.PilotACS.olt.model.OltAction;
import com.PilotACS.olt.model.OltFrontPanel;
import com.PilotACS.olt.model.OltOnt;
import com.PilotACS.olt.model.OltPort;
import com.PilotACS.olt.model.OltQuickSetupRequest;
import com.PilotACS.olt.model.OltTr069Profile;
import com.PilotACS.olt.model.OltTr069ProfileRequest;
import com.PilotACS.olt.model.VlanIdExpression;
import com.PilotACS.olt.model.OltVlan;
import com.PilotACS.olt.model.OltVlanInfo;
import com.PilotACS.olt.model.OltVlanPort;
import com.PilotACS.olt.telnet.OltSnapshot;
import com.PilotACS.olt.telnet.OltVendor;
import com.PilotACS.olt.vendor.AbstractOltVendorProfile;
import com.PilotACS.olt.vendor.OltCli;

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

/** 鸿升光 HSGQ-E04 等 EPON 一体化 OLT */
public class HsgqOltProfile extends AbstractOltVendorProfile {

    private static final String DEFAULT_MODEL = "HSGQ-E04";

    /** 型号 → PON 口数 */
    private static final Map<String, Integer> PON_PORT_COUNT = Map.of(
            "HSGQ-E04", 4,
            "HSGQ-E04P", 4,
            "HSGQ-E08", 8,
            "HSGQ-E16", 16);

    /** 从型号抠口数 */
    private static final Pattern MODEL_PORT_COUNT = Pattern.compile("(?i)^HSGQ-[A-Z]*(\\d{1,2})");

    /** 型号 */
    private static final Pattern MODEL = Pattern.compile("(?i)\\b(HSGQ-[A-Za-z0-9]{2,10})\\b");

    /** show pon-info 端口行 */
    private static final Pattern PON_PORT = Pattern.compile(
            "(?im)^\\s*PON(\\d{1,2})\\s+(Up|Down)\\s+(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s*$");

    /** show onu-info all 数据行 */
    private static final Pattern ONU_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})\\s+"
                    + "([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\s+"
                    + "(Online|Offline|Initial|Init)\\b(.*)$");

    private static final Pattern ONU_FLAG = Pattern.compile("(?i)\\b(TRUE|FALSE)\\b");

    private static final Pattern ONU_REG_TIME = Pattern.compile(
            "\\d{4}/\\d{1,2}/\\d{1,2}\\s+\\d{1,2}:\\d{2}:\\d{2}");

    /** ONU 名称列 */
    private static final Pattern ONU_NAME = Pattern.compile("\\b[A-Za-z]{2,8}\\d{1,2}/\\d{1,3}\\b");

    /** show onu-version all 数据行 */
    private static final Pattern ONU_VERSION_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})\\s+"
                    + "(\\S+)\\s+"
                    + "([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\s+"
                    + "(\\S+)\\s+(\\S+)\\s+"
                    + "(\\S+)\\s+"
                    + "(\\S+)\\s+"
                    + "(\\S+)\\s*(.*)$");

    /** show optical-info 数据行 */
    private static final Pattern ONU_OPTICAL_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})\\s+(\\S+)\\s+"
                    + "([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\s+(.*)$");

    private static final Pattern OPTICAL_DBM = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*dBm");

    /** show port state all 端口名 */
    private static final Pattern PORT_STATE_PORT = Pattern.compile(
            "(?i)(?<![a-z0-9])(epon|gpon|ge|xge)\\s?(\\d{1,3})(?![0-9])");

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
        return List.of("show pon-info");
    }

    @Override
    public List<String> pagingCommands() {
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

    /** 计算 PON 口数 */
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

    /** 按型号生成整机卡片 */
    @Override
    public OltBoard buildWholeDevice(String model) {
        String resolved = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        HsgqModelCatalog.Spec spec = HsgqModelCatalog.match(resolved);
        if (spec == null) {
            spec = HsgqModelCatalog.fallback(ponTypeName(), ponPortCount(resolved));
        }
        int count = spec.ponCount();
        OltBoard board = new OltBoard();
        board.setSlotNo(0);
        board.setBoardType(resolved);
        board.setStatus("Up");
        board.setPortCount(count);
        board.setPortMode(spec.ponType());
        board.setBoardKind("SERVICE");
        board.setDescription(spec.label() + " 整机（" + spec.summary() + "）");
        board.setFrontPanel(new OltFrontPanel(resolved, spec.ponType(), count,
                spec.geCount(), spec.xgeCount(), spec.hasNms(), spec.hasConsole(), spec.hasUsb(),
                spec.summary()));
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

    /** PON 口类型名 */
    protected String ponTypeName() {
        return "EPON";
    }

    @Override
    public Integer slotCount(String boardText, List<OltBoard> boards) {
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

    /** 配置视图下补采 PON 口与 ONU 数据 */
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
            cli.run("quit", timeoutMs);
        }
    }

    /** 补采每口 ONU 数量与明细 */
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
                List<OltOnt> list = onts.get(ponNo);
                if (list != null) {
                    port.setOnts(list);
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

    /** 进 PON 口视图补采 ONU 版本与光功率 */
    private int fillOnuDetail(OltCli cli, int ponNo, List<OltOnt> onts, int timeoutMs) {
        cli.run("interface epon " + ponNo, timeoutMs);
        String versionText = cli.run("show onu-version all", timeoutMs);
        String opticalText = cli.run("show optical-info", timeoutMs);
        cli.run("exit", timeoutMs);
        int parsed = applyOnuVersion(ponNo, onts, versionText);
        parsed += applyOpticalInfo(ponNo, onts, opticalText);
        return parsed;
    }

    /** 解析 ONU 版本 */
    private static int applyOnuVersion(int ponNo, List<OltOnt> onts, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int parsed = 0;
        Matcher matcher = ONU_VERSION_ROW.matcher(text);
        while (matcher.find()) {
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

    /** 解析 ONU 光功率 */
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

    /** VLAN 快照 */
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

    /** 可做 VLAN 成员的端口类型 */
    @Override
    public Set<String> vlanMemberPortTypes() {
        return Set.of("epon", "ge", "xge");
    }

    /** 是否支持 TR-069 模板 */
    @Override
    public boolean supportsTr069Profile() {
        return true;
    }

    @Override
    public List<String> tr069ProfileQueryCommands() {
        return List.of("show onu-tr069-profile all");
    }

    /** 拼接 TR-069 模板新增命令 */
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

    /** 是否支持一键开局 */
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
        if (!commands.isEmpty()) {
            commands.add(cmdSave());
        }
        return commands;
    }

    /** 开局业务 VLAN 命令 */
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

    /** 逐端口下发 VLAN 模式命令 */
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

    /** 取列表第一个 VLAN ID */
    private static String firstVlanId(String vlanList) {
        if (vlanList == null || vlanList.isBlank()) {
            return "";
        }
        String head = vlanList.split(",")[0].trim();
        int dash = head.indexOf('-');
        return dash > 0 ? head.substring(0, dash) : head;
    }

    /** 计算端口放通的 VLAN 列表 */
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



    /** TR-069 模板表格行 */
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

    /** 汇总可加进 VLAN 的端口清单 */
    protected List<OltVlanPort> vlanMemberPorts(OltSnapshot snapshot, String portStateText) {
        Map<String, OltVlanPort> merged = new LinkedHashMap<>();
        int ponCount = ponPortCount(snapshot == null ? null : snapshot.getModel());
        int boardPonCount = 0;
        if (snapshot != null && snapshot.getBoards() != null) {
            for (OltBoard board : snapshot.getBoards()) {
                boardPonCount += board.getPorts() == null ? 0 : board.getPorts().size();
            }
        }
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

    /** PON 口端口类型关键字 */
    protected String ponType() {
        return "epon";
    }

    /** 兜底上联口清单 */
    protected List<OltVlanPort> fallbackUplinkPorts() {
        List<OltVlanPort> ports = new ArrayList<>();
        for (int no = 1; no <= 4; no++) {
            ports.add(new OltVlanPort("ge", no));
        }
        return ports;
    }

    /** 端口排序 */
    private static void sortMemberPorts(List<OltVlanPort> ports) {
        List<String> order = List.of("epon", "gpon", "ge", "xge");
        ports.sort(Comparator
                .comparingInt((OltVlanPort port) -> order.indexOf(port.getType()))
                .thenComparingInt(port -> port.getNo() == null ? 0 : port.getNo()));
    }

    private static boolean isUplinkPort(OltVlanPort port) {
        return "ge".equals(port.getType()) || "xge".equals(port.getType());
    }

    /** 解析端口清单 */
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
        for (String type : List.of("epon", "gpon", "ge", "xge")) {
            Set<Integer> numbers = byType.get(type);
            if (numbers == null) {
                continue;
            }
            numbers.forEach(no -> ports.add(new OltVlanPort(type, no)));
        }
        return ports;
    }

    /** 建 VLAN 并加入成员端口 */
    @Override
    public List<String> createVlanCommands(int startVlanId, int endVlanId,
                                           List<String> tagged, List<String> untagged) {
        List<String> taggedCommands = memberPortCommands(tagged, "tagged");
        List<String> untaggedCommands = memberPortCommands(untagged, "untagged");
        if (taggedCommands.isEmpty() && untaggedCommands.isEmpty()) {
            return createVlanCommands(startVlanId, endVlanId);
        }
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

    /** 生成成员端口命令 */
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

    /** 端口号列表格式化 */
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

    /** 解析 show pon-info */
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

    /** 解析 show onu-info all */
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

    /** 拆 ONU 行其余列 */
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

    public static final int MAX_VLAN_ID = 4094;

    /** running-config 的 VLAN 定义行 */
    private static final Pattern CFG_VLAN = Pattern.compile(
            "(?im)^\\s*vlan\\s+standard\\s+(\\d{1,4})(?:\\s+(\\d{1,4}))?\\s*$");

    /** vlan 视图成员端口行 */
    private static final Pattern CFG_VLAN_PORT = Pattern.compile(
            "(?im)^\\s*port\\s+(epon|ge|xge)\\s+([0-9][0-9,\\-]*)\\s*(tagged|untagged|default-vlan)?");

    /** show vlan 的 VLAN ID 行 */
    private static final Pattern SHOW_VLAN_ID = Pattern.compile("(?im)^\\s*VLAN\\s*ID\\s*[:：]\\s*(\\d{1,4})\\s*$");

    /** show vlan 成员端口行 */
    private static final Pattern SHOW_VLAN_FIELD = Pattern.compile(
            "(?im)^\\s*(tagged\\s*ports|untagged\\s*ports|default\\s*vlan\\s*ports)\\s*[:：]\\s*(.*?)\\s*$");

    /** 从 running-config 解析 VLAN */
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

    /** 从 show vlan 解析 VLAN */
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

    /** 成员端口格式化 */
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
                continue;
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

    /** 创建单个 VLAN 命令 */
    public static String cmdCreateVlan(int vlanId) {
        return "vlan standard " + vlanId;
    }

    /** 批量创建 VLAN 命令 */
    public static String cmdCreateVlanBatch(int startVlanId, int endVlanId) {
        return "vlan standard " + startVlanId + " " + endVlanId;
    }

    /** 删除单个 VLAN 命令 */
    public static String cmdDeleteVlan(int vlanId) {
        return "no vlan " + vlanId;
    }

    /** 批量删除 VLAN 命令 */
    public static String cmdDeleteVlanBatch(int startVlanId, int endVlanId) {
        return "no vlan " + startVlanId + " " + endVlanId;
    }

    /** 删除所有 VLAN 命令 */
    public static String cmdDeleteAllVlan() {
        return "no vlan all";
    }

    /** 端口放通 VLAN（trunk）命令 */
    public static String cmdVlanTrunk(String vlanList) {
        return "vlan trunk " + vlanList;
    }

    /** 端口 VLAN（hybrid）命令 */
    public static String cmdVlanHybrid(String vlanList, String tagMode) {
        return "vlan hybrid " + vlanList + " " + tagMode;
    }

    /** 端口 VLAN（access）命令 */
    public static String cmdVlanAccess(String vlanId) {
        return "vlan access " + vlanId;
    }

    /** VLAN 加成员端口命令 */
    public static String cmdAddVlanPort(String portType, String ports, String mode) {
        return "port " + portType + " " + ports + " " + mode;
    }

    /** VLAN 删成员端口命令 */
    public static String cmdRemoveVlanPort(String portType, String ports) {
        return "no port " + portType + " " + ports;
    }

    /** 保存配置命令 */
    public static String cmdSave() {
        return "save";
    }

    @Override
    public boolean supportsVlanConfig() {
        return true;
    }

    @Override
    public List<OltAction> actions() {
        return HsgqActionCatalog.actions();
    }

    /** 建 VLAN 命令 */
    @Override
    public List<String> createVlanCommands(int startVlanId, int endVlanId) {
        String create = endVlanId > startVlanId
                ? cmdCreateVlanBatch(startVlanId, endVlanId)
                : cmdCreateVlan(startVlanId);
        return List.of(create, "exit");
    }

    /** 删 VLAN 命令 */
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
