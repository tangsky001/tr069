package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltOntAutofind;
import com.tr069.acs.olt.model.OltQuickSetupRequest;
import com.tr069.acs.olt.model.OltTerminalUserRequest;
import com.tr069.acs.olt.telnet.OltCliSession;
import com.tr069.acs.olt.telnet.OltInfoParser;
import com.tr069.acs.olt.telnet.OltVendor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 华为 MA5600T / MA5683T / MA5800 / EA5800。
 *
 * <p>命令是 {@code display} 那一套，关分页是 {@code scroll}（{@code screen-length}
 * 是 VRP 交换机的命令，OLT 不认）；只跑带机框号的 {@code display board 0}——
 * 不带参数时设备会回 "Parameter: FrameID/SlotID" 停下来等输入，命令就一直等到超时。</p>
 */
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
        // undo smart 在部分版本会弹交互确认，反而拖慢采集，不发
        return List.of("scroll", "screen-length 0 temporary");
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
        // scroll 在特权视图才生效（登录后的用户视图发了也不认），进 config 后补一次，
        // 后面 display ont info / service-port 这类长回显就不分页了
        cli.run("scroll", timeoutMs);
        return true;
    }

    // ---------------------------------------------------------------- 一键开局

    /**
     * 华为已经接入一键开局：DBA → 线路模板 → 业务模板 → TR-069 模板。
     */
    @Override
    public boolean supportsQuickSetup() {
        return true;
    }

    /**
     * 华为开局建的是模板而不是改端口模式，所以只认 {@link OltQuickSetupRequest#getHuawei()}
     * 那一段参数；走端口模式那套（鸿升光用的）在这里编不出命令，返回空表由上层报错。
     */
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

    /**
     * 只看 {@code all}：不带端口参数能一次拿到所有 PON 口上报上来的 ONT。
     * 带参数的写法（{@code display ont autofind 0}）在部分版本里会停在交互提示上等输入。
     */
    @Override
    public List<String> ontAutofindCommands() {
        return List.of("display ont autofind all");
    }

    /**
     * 这条命令刷的是 GPON 侧的待注册表，所以标记成 gpon；
     * EPON 那套（{@code display ont autofind epon}）还没接，接了再按命令区分。
     */
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

    /**
     * 回显一般长这样：
     *
     * <pre>
     *   Port ID       : 0
     *   Ont-auto-find : enable
     * </pre>
     *
     * <p>版本之间措辞有出入，所以只认整段里出现的关键字，不强求格式。回 Failure 说明
     * 这个版本没这条查询，返回 null 让页面显示「查不到」——<b>不默认当成关闭</b>，
     * 否则会把「查不了」误导成「没开」，用户就会去开一个其实已经开着的口。</p>
     */
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

    /**
     * 一个口一条 {@code port N ont-auto-find enable}，按板分组，免得上百条命令里反复进出视图。
     *
     * <p>这条命令是幂等的：已经开着的口再开一次设备也照收，所以不用先查状态挑着开。</p>
     */
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

    /**
     * {@code ont confirm <port> <认证方式> <认证串> omci [ont-lineprofile-id X] [ont-srvprofile-id Y]}
     *
     * <p>语法是设备上问出来的（{@code ont confirm ?} 逐级问到的就是下面这套），
     * 每一样都不是猜的：</p>
     *
     * <pre>
     * ont confirm 0
     *   { all | loid-auth | ontid | password-auth | sn-auth | sn-or-password-auth }: sn-auth
     *   { sn-value&lt;S&gt;&lt;Length 12-16&gt; }:                                   43494F5429ABE539
     *   { omci | password-auth | snmp }:                                  omci
     *   { &lt;cr&gt; | desc | ont-lineprofile-id | ont-srvprofile-id | ont-type }
     * </pre>
     *
     * <p><b>端口后面不是 ONT ID</b>：第二位直接是认证方式关键字，ONT ID 由设备自动分配。
     * 早先按「端口 + 自动发现编号 + sn-auth」拼的命令，编号被当成关键字，
     * 设备回 {@code % Unknown command, the error locates at '^'}，一台都注册不上。</p>
     */
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

    /**
     * {@code board confirm 0}：把 auto-find 状态的板卡一次确认加载。
     *
     * <p>0 是机框号（两台都是单机框）。刚连接时业务板/上联板若不是正常状态，
     * 多半是插上了还没确认——确认后重新采集，状态就变 Active_normal。</p>
     */
    @Override
    public String boardConfirmCommand() {
        return "board confirm 0";
    }

    /**
     * 线路模板详情：{@code display ont-lineprofile gpon profile-id 512}。
     *
     * <p>回显里有 T-CONT 挂的 DBA、{@code <Gem Index 1>} 和它下面那张
     * {@code Mapping VLAN} 表（一键开局建的那条就是 46 / 41 / 45 三条）。
     * 注册后下发业务虚口时，VLAN 和 GEM 号都从这儿读。</p>
     */
    @Override
    public String lineProfileDetailCommand(String ponType, int profileId) {
        return "display " + lineProfileView(ponType, profileId);
    }

    /**
     * 加 T-CONT：{@code tcont 1 dba-profile-id 1000}，必须先有那条 DBA 模板。
     *
     * <pre>
     * ont-lineprofile gpon profile-id 100
     * tcont 1 dba-profile-id 1000
     * commit
     * quit
     * </pre>
     */
    @Override
    public List<String> lineTcontAddCommands(String ponType, int profileId, int tcontId, int dbaProfileId) {
        return lineProfileCommands(lineProfileView(ponType, profileId),
                "tcont " + tcontId + " dba-profile-id " + dbaProfileId);
    }

    /**
     * 加 GEM Port：{@code gem add 1 eth tcont 1 encrypt on}。
     * 第二个参数是它挂的 T-CONT，设备上那条 T-CONT 得先存在。
     */
    @Override
    public List<String> lineGemportAddCommands(String ponType, int profileId, int gemportId, int tcontId) {
        return lineProfileCommands(lineProfileView(ponType, profileId),
                "gem add " + gemportId + " eth tcont " + tcontId + " encrypt on");
    }

    /** 加 GEM 映射：{@code gem mapping <GEM Port> <映射序号> vlan <VLAN>}，一条映射一条命令 */
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

    /**
     * 业务虚口：一条 VLAN 一条流，{@code translate} 表示用户侧 VLAN 原样透传到上联。
     *
     * <p>语法是设备上问出来的：{@code service-port ?} 给出 {@code index<U><0,32767>}，
     * {@code multi-service user-vlan <v> ?} 里有 {@code tag-transform}。
     * 没有这层虚口，光猫注册上线了也不通业务——页面「业务流」一列会是空的。</p>
     *
     * <p>GEM Port 用线路模板里的那一个（一键开局建的就是 {@code gem add 1}）。</p>
     */
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

    /**
     * 整块板所有口的 ONT 明细：{@code display ont info 0 <槽位> all}。
     *
     * <p>回显按端口分段，每行带 ONT ID、SN 和 Run state，正好用来判断这台光猫
     * 是不是已经在别的口上注册过（自动移机），以及哪些是离线的（注册表满时清掉）。</p>
     */
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

    /**
     * 建本地用户：设备这一串提问顺序是固定的，应答必须一条对一条。
     *
     * <p>在 MA5683T 上实测的提问依次是：</p>
     * <ol>
     *   <li>{@code User Name(length<6,15>)} 用户名，6~15 位</li>
     *   <li>{@code User Password(length<6,15>)} 密码，6~15 位</li>
     *   <li>{@code Confirm Password(length<6,15>)} 再输一遍；两次不一致设备回
     *       {@code Failure: Error Password} 并重新要一次</li>
     *   <li>{@code User profile name(<=15 chars)[root]} 用户模板，回车用默认 root</li>
     *   <li>{@code User's Level: 1. Common User  2. Operator} 级别</li>
     *   <li>{@code Permitted Reenter Number(0--4)} 允许重复登录次数</li>
     *   <li>{@code User's Appended Info(<=30 chars)} 备注</li>
     *   <li>{@code Repeat this operation? (y/n)[n]} 还要不要再建一个</li>
     * </ol>
     *
     * <p>第 5 步要留意：<b>实际能列出几个选项，取决于当前登录账号自己的级别</b>。
     * 华为不允许建比自己级别更高的账号——在 MA5680T 上用 root（Administrator 级）登，
     * 设备给的是 <code>1. Common User  2. Operator  3. Administrator</code>；
     * 而在 MA5683T 上用 oltadmin（Operator 级）登，就只剩 <code>1. Common User
     * 2. Operator</code>，发 3 会被回一句
     * <code>Failure: Error choice or the input level higher than owns</code>。</p>
     *
     * <p>这里不做拦截，三档都让选：权限够不够设备说了算，拒绝原因原样带回给页面，
     * 比后端自己猜要准。</p>
     */
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

    /**
     * 上行口放通 VLAN：先确保 VLAN 存在，再 {@code port vlan 41,45-46 0/7 0}。
     *
     * <p>设备上 VLAN 不存在时 {@code port vlan 104 0/7 0} 直接回
     * {@code Failure: VLAN does not exist}——光放通不建 VLAN 是配不上的，所以每条
     * VLAN 前面补一条 {@code vlan <ID> smart}：已经存在的会回 {@code VLAN has existed}，
     * 下发时按「已有配置」跳过，不影响后面的放通。</p>
     *
     * <p>命令把「框/槽」和「端口号」当两个参数给（{@code 0/7} + {@code 0}），
     * 页面上的 {@code 0/7/0} 得拆开；尾巴上带 {@code native} 表示这个 VLAN 在口上不打标签。
     * 一条 {@code port vlan} 能带一串 VLAN（逗号/区间都认），所以多个 VLAN 一次放通；
     * 一条命令只能带一个端口，页面填了多个口（{@code 0/7/0,0/7/1}）就逐口各发一条。</p>
     */
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

    /**
     * 一个或多个上行口：{@code 0/7/0}、{@code 0/7/0,0/7/1}、{@code 0/7/0 0/17/0} 都认，
     * 逐个转成命令里的 {@code 0/7 0} 写法，去重保序。
     */
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

    /**
     * {@code 0/7/0} → {@code 0/7 0}：把端口号从路径里拆出来。
     * 只写到框和槽的（{@code 0/7}）不知道具体哪个口，返回 null 由上层提示，不替用户猜。
     */
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
