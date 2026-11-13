package com.tr069.acs.olt.vendor.hw;

import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltOntAutofind;
import com.tr069.acs.olt.model.OltQuickSetupRequest;
import com.tr069.acs.olt.model.OltTerminalUserRequest;
import com.tr069.acs.olt.telnet.OltCliSession;
import com.tr069.acs.olt.telnet.OltInfoParser;
import com.tr069.acs.olt.telnet.OltVendor;
import com.tr069.acs.olt.vendor.AbstractOltVendorProfile;
import com.tr069.acs.olt.vendor.OltCli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 华为 MA5600T / MA5683T / MA5800 / EA5800 */
public class HuaweiOltProfile extends AbstractOltVendorProfile {

    /** MA5683T / MA5600T / MA5800-X7；注意别把版本号 MA5600V800R015C00 当成型号 */
    private static final Pattern MODEL = Pattern.compile(
            "(?i)\\b(MA5800-[A-Z0-9]+|EA5800-[A-Z0-9]+|MA6800T|MA5\\d{3,4}T?)\\b");

    @Override
    public OltVendor vendor() {
        return OltVendor.HUAWEI;
    }

    @Override
    public OltVendor.CliStyle cliStyle() {
        return OltVendor.CliStyle.DISPLAY;
    }

    @Override
    public boolean matches(String output) {
        return containsAny(output, "huawei", "quidway", "versatile routing", "smartax",
                "ma5600", "ma5603", "ma5608", "ma5680", "ma5683", "ma5800", "ea5800", "ma6800");
    }

    @Override
    public List<String> pagingCommands() {
        // 不发 undo smart（部分版本会弹交互确认）；scroll 必须带参数，无参会卡在翻屏提示等按键，512 行一屏最接近不分页
        return List.of("scroll 512", "screen-length 0 temporary");
    }

    @Override
    public List<String> versionCommands() {
        return List.of("display version");
    }

    @Override
    public List<String> boardCommands() {
        return List.of("display board 0");
    }

    @Override
    protected List<Pattern> modelPatterns() {
        return List.of(MODEL, MODEL_LABEL);
    }

    @Override
    public boolean enterConfigView(OltCli cli, int timeoutMs) {
        if (cli == null) {
            return false;
        }
        // enable 在已是特权视图时会回一句报错，忽略即可
        cli.run("enable", timeoutMs);
        String out = cli.run("config", timeoutMs);
        if (out == null || !out.contains("(config)#")) {
            return false;
        }
        // scroll 只在特权视图生效（用户视图发了也不认），进 config 后补一次，长回显就不分页
        cli.run("scroll", timeoutMs);
        return true;
    }

    // ---------------------------------------------------------------- 一键开局

    /** 华为已经接入一键开局：DBA → 线路模板 → 业务模板 → TR-069 模板 */
    @Override
    public boolean supportsQuickSetup() {
        return true;
    }

    /** 华为开局建的是模板而不是改端口模式，所以只认 {@link OltQuickSetupRequest#getHuawei()} */
    @Override
    public List<String> quickSetupCommands(OltQuickSetupRequest request) {
        if (request == null || request.getHuawei() == null) {
            return List.of();
        }
        return HuaweiQuickSetupPlanner.plan(request.getHuawei());
    }

    // ------------------------------------------------------------ 待注册 ONT（autofind）

    @Override
    public boolean supportsOntAutofind() {
        return true;
    }

    /** 只看 {@code all}：不带端口参数能一次拿到所有 PON 口上报上来的 ONT */
    @Override
    public List<String> ontAutofindCommands() {
        return List.of("display ont autofind all");
    }

    /** 这条命令刷的是 GPON 侧的待注册表，所以标记成 gpon */
    @Override
    public List<OltOntAutofind> parseOntAutofind(String text) {
        List<OltOntAutofind> items = OltInfoParser.parseOntAutofind(text);
        items.forEach(item -> item.setPonType("gpon"));
        return items;
    }

    // ------------------------------------------------- PON 口自动发现（ont-auto-find）

    @Override
    public boolean supportsOntAutoFind() {
        return true;
    }

    @Override
    public String ponViewCommand(int frame, int slot) {
        return "interface gpon " + frame + "/" + slot;
    }

    @Override
    public String ontAutoFindQueryCommand(int port) {
        return "display ont-auto-find " + port;
    }

    /** 回显一般长这样 */
    @Override
    public Boolean parseOntAutoFindState(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("failure") || lower.contains("unknown")
                || lower.contains("unrecognized") || lower.contains("not found")) {
            return null;
        }
        // disable 里不含 enable，但先判关闭更保险
        if (lower.contains("disable")) {
            return false;
        }
        if (lower.contains("enable")) {
            return true;
        }
        return null;
    }

    /** 一个口一条 {@code port N ont-auto-find enable}，按板分组，免得上百条命令里反复进出视图 */
    @Override
    public List<String> ontAutoFindEnableCommands(List<String> fspList) {
        if (fspList == null || fspList.isEmpty()) {
            return List.of();
        }
        Map<String, List<Integer>> byBoard = new LinkedHashMap<>();
        for (String fsp : fspList) {
            String[] parts = fsp == null ? new String[0] : fsp.split("/");
            if (parts.length != 3) {
                continue;
            }
            try {
                byBoard.computeIfAbsent(parts[0] + "/" + parts[1], key -> new ArrayList<>())
                        .add(Integer.parseInt(parts[2].trim()));
            } catch (NumberFormatException ignored) {
                // 端口号不是数字就跳过，不因为一条怪数据让整批开不了
            }
        }
        List<String> commands = new ArrayList<>();
        byBoard.forEach((board, ports) -> {
            commands.add("interface gpon " + board);
            ports.forEach(port -> commands.add("port " + port + " ont-auto-find enable"));
            commands.add("quit");
        });
        if (!commands.isEmpty()) {
            commands.add("save");
        }
        return commands;
    }

    // ------------------------------------------------------------ ONT 注册（ont confirm）

    @Override
    public boolean supportsOntConfirm() {
        return true;
    }

    /** {@code ont confirm  <认证方式> <认证串> omci [ont-lineprofile-id X] [ont-srvprofile-id Y]} */
    @Override
    public String ontConfirmCommand(int port, String authMode, String authValue,
                                    Integer lineProfileId, Integer srvProfileId) {
        String keyword = ontAuthKeyword(authMode);
        if (keyword == null) {
            return null;
        }
        if (authValue == null || authValue.isBlank()) {
            // 关键字后面空着就是一条必然被拒的命令，宁可返回 null 让上层报「缺认证串」
            return null;
        }
        StringBuilder command = new StringBuilder("ont confirm ")
                .append(port).append(' ').append(keyword).append(' ')
                .append(authValue.trim()).append(" omci");
        if (lineProfileId != null) {
            command.append(" ont-lineprofile-id ").append(lineProfileId);
        }
        if (srvProfileId != null) {
            command.append(" ont-srvprofile-id ").append(srvProfileId);
        }
        return command.toString();
    }

    @Override
    public boolean supportsOntDescription() {
        return true;
    }

    /** {@code ont modify <port> <ontid> desc "<描述>"}，描述带空格也能用（整体加引号） */
    @Override
    public String ontDescriptionCommand(int port, int ontId, String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        // 描述里不能有引号：会把后面的参数一起吞掉，命令被拒
        String text = description.trim().replace("\"", "");
        return "ont modify " + port + ' ' + ontId + " desc \"" + text + "\"";
    }

    /** SN / MAC / LOID / PASSWORD → 设备认的认证关键字 */
    private static String ontAuthKeyword(String authMode) {
        String mode = authMode == null ? "SN" : authMode.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "SN" -> "sn-auth";
            case "MAC" -> "mac-auth";
            case "LOID" -> "loid-auth";
            case "PASSWORD" -> "password-auth";
            default -> null;
        };
    }

    /** {@code board confirm 0}：把 auto-find 状态的板卡一次确认加载 */
    @Override
    public String boardConfirmCommand() {
        return "board confirm 0";
    }

    /** 线路模板详情：{@code display ont-lineprofile gpon profile-id 512} */
    @Override
    public String lineProfileDetailCommand(String ponType, int profileId) {
        return "display " + lineProfileView(ponType, profileId);
    }

    /** 加 T-CONT：{@code tcont 1 dba-profile-id 1000}，必须先有那条 DBA 模板 */
    @Override
    public List<String> lineTcontAddCommands(String ponType, int profileId, int tcontId, int dbaProfileId) {
        return lineProfileCommands(lineProfileView(ponType, profileId),
                "tcont " + tcontId + " dba-profile-id " + dbaProfileId);
    }

    /** 加 GEM Port：{@code gem add 1 eth tcont 1 encrypt on} */
    @Override
    public List<String> lineGemportAddCommands(String ponType, int profileId, int gemportId, int tcontId) {
        return lineProfileCommands(lineProfileView(ponType, profileId),
                "gem add " + gemportId + " eth tcont " + tcontId + " encrypt on");
    }

    /** 加 GEM 映射：{@code gem mapping  <映射序号> vlan }，一条映射一条命令 */
    @Override
    public List<String> lineGemMappingAddCommands(String ponType, int profileId, int gemportId,
                                                  List<int[]> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        List<String> body = new ArrayList<>();
        for (int[] mapping : mappings) {
            body.add("gem mapping " + gemportId + " " + mapping[0] + " vlan " + mapping[1]);
        }
        return lineProfileCommands(lineProfileView(ponType, profileId), body.toArray(new String[0]));
    }

    /** {@code ont-lineprofile gpon profile-id 100}；模板上没写 PON 类型就按 gpon */
    private static String lineProfileView(String ponType, int profileId) {
        String view = ponType == null || ponType.isBlank()
                ? "gpon" : ponType.trim().toLowerCase(Locale.ROOT);
        return "ont-lineprofile " + view + " profile-id " + profileId;
    }

    /** 模板视图里的一段命令：进视图 → 配置 → commit（不 commit 不生效）→ quit 回 config 视图 */
    private static List<String> lineProfileCommands(String view, String... body) {
        List<String> commands = new ArrayList<>();
        commands.add(view);
        commands.addAll(java.util.Arrays.asList(body));
        commands.add("commit");
        commands.add("quit");
        return commands;
    }

    /** 业务虚口：一条 VLAN 一条流，{@code translate} 表示用户侧 VLAN 原样透传到上联 */
    @Override
    public boolean supportsServicePorts() {
        return true;
    }

    @Override
    public String servicePortCommand(int index, int vlan, int frame, int slot, int port,
                                     int ontId, int gemPort) {
        return "service-port " + index + " vlan " + vlan + " gpon "
                + frame + "/" + slot + "/" + port + " ont " + ontId + " gemport " + gemPort
                + " multi-service user-vlan " + vlan + " tag-transform translate";
    }

    @Override
    public String ontDeleteCommand(int port, int ontId) {
        return "ont delete " + port + " " + ontId;
    }

    /** {@code display service-port port 0/<槽>/<口> ont <id>}；ontId 传 null 查整个端口 */
    @Override
    public String servicePortListCommand(int frame, int slot, int port, Integer ontId) {
        String base = "display service-port port " + frame + "/" + slot + "/" + port;
        return ontId == null ? base : base + " ont " + ontId;
    }

    /** {@code undo service-port <index>}（config 视图） */
    @Override
    public String servicePortDeleteCommand(int index) {
        return "undo service-port " + index;
    }

    /** 单块板详情：{@code display board 0/2}，回显里就有每口挂了几个 ONT */
    @Override
    public String boardDetailCommand(int frame, int slot) {
        return "display board " + frame + "/" + slot;
    }

    /** 清空一个 PON 口下全部 ONT：{@code ont delete 0 all}（设备随后问 y/n） */
    @Override
    public String ontDeleteAllCommand(int port) {
        return "ont delete " + port + " all";
    }

    /** EPON 板进 {@code interface epon 0/2}，GPON 板进 {@code interface gpon 0/2} */
    @Override
    public String ponViewCommand(int frame, int slot, String ponType) {
        return "interface " + ("epon".equalsIgnoreCase(ponType) ? "epon" : "gpon") + " " + frame + "/" + slot;
    }

    /** 整块板所有口的 ONT 明细：{@code display ont info 0 <槽位> all} */
    @Override
    public String ontListCommand(int frame, int slot) {
        return "display ont info " + frame + " " + slot + " all";
    }

    @Override
    public Map<Integer, List<OltOnt>> parseOntList(String text) {
        return OltInfoParser.parseOntDetails(text);
    }

    // ------------------------------------------------------------ OLT 本地用户

    @Override
    public boolean supportsTerminalUser() {
        return true;
    }

    /** 建本地用户：设备这一串提问顺序是固定的，应答必须一条对一条 */
    @Override
    public List<String> terminalUserAddCommands(OltTerminalUserRequest request) {
        if (request == null) {
            return List.of();
        }
        String username = request.getUsername() == null ? null : request.getUsername().trim();
        String password = request.getPassword();
        // 长度是设备定的（6~15），不合规它只会顶回来，提前挡掉省一次往返
        if (username == null || username.length() < 6 || username.length() > 15
                || password == null || password.length() < 6 || password.length() > 15) {
            return List.of();
        }
        // 1=Common User、2=Operator、3=Administrator；够不够格由设备判断
        int level = request.getLevel() == null ? 2 : request.getLevel();
        if (level < 1 || level > 3) {
            return List.of();
        }
        int reenter = request.getReenter() == null ? 0 : request.getReenter();
        if (reenter < 0 || reenter > 4) {
            return List.of();
        }
        String profile = request.getProfile() == null ? "" : request.getProfile().trim();
        String info = request.getAppendedInfo() == null ? "" : request.getAppendedInfo().trim();
        return List.of(
                "terminal user name",
                OltCliSession.ANSWER_PREFIX + username,
                OltCliSession.ANSWER_PREFIX + password,
                OltCliSession.ANSWER_PREFIX + password,  // 确认密码，必须和上面那条一致
                OltCliSession.ANSWER_PREFIX + profile,  // 留空表示直接回车，用设备默认的 root
                OltCliSession.ANSWER_PREFIX + level,
                OltCliSession.ANSWER_PREFIX + reenter,
                OltCliSession.ANSWER_PREFIX + info,
                OltCliSession.ANSWER_PREFIX + "n"        // 只建这一个，不再接着建
        );
    }

    // ---------------------------------------------------------------- 上行口 VLAN 透传

    /** 上行口放通 VLAN：先确保 VLAN 存在，再 {@code port vlan 41,45-46 0/7 0} */
    @Override
    public List<String> addPortVlanCommands(String iface, List<Integer> vlanIds, boolean nativeVlan) {
        List<String> targets = uplinkTargets(iface);
        if (targets.isEmpty() || vlanIds == null || vlanIds.isEmpty()) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        // 先建 VLAN：省略这一步，设备回「Failure: VLAN does not exist」，端口上一次也放不通
        vlanIds.stream().filter(id -> id != null).distinct().sorted()
                .forEach(id -> commands.add("vlan " + id + " smart"));
        for (String target : targets) {
            commands.add("port vlan " + joinVlans(vlanIds) + " " + target + (nativeVlan ? " native" : ""));
        }
        return commands;
    }

    /** 上行口解除 VLAN 绑定：{@code no port vlan 41,45-46 0/7 0}，多个口逐口各一条 */
    @Override
    public List<String> removePortVlanCommands(String iface, List<Integer> vlanIds) {
        List<String> targets = uplinkTargets(iface);
        if (targets.isEmpty() || vlanIds == null || vlanIds.isEmpty()) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        for (String target : targets) {
            commands.add("no port vlan " + joinVlans(vlanIds) + " " + target);
        }
        return commands;
    }

    /** 一个或多个上行口：{@code 0/7/0}、{@code 0/7/0,0/7/1}、{@code 0/7/0 0/17/0} 都认 */
    private static List<String> uplinkTargets(String iface) {
        if (iface == null || iface.isBlank()) {
            return List.of();
        }
        List<String> targets = new ArrayList<>();
        for (String piece : iface.trim().split("[,，;；\\s]+")) {
            String target = uplinkTarget(piece);
            if (target != null && !targets.contains(target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    /** {@code 0/7/0} → {@code 0/7 0}：把端口号从路径里拆出来 */
    private static String uplinkTarget(String iface) {
        if (iface == null || iface.isBlank()) {
            return null;
        }
        String[] pieces = iface.trim().split("/");
        if (pieces.length < 3) {
            return null;
        }
        String port = pieces[pieces.length - 1];
        String path = String.join("/", java.util.Arrays.copyOf(pieces, pieces.length - 1));
        return path + " " + port;
    }

    /** {@code [41, 46, 45]} → {@code 41,45,46}：命令里的 VLAN 列表，升序去重 */
    private static String joinVlans(List<Integer> vlanIds) {
        return vlanIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }
}
