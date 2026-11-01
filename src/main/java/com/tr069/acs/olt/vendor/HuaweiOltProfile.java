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
     * {@code ont confirm <port> <number> <认证方式> omci ont-lineprofile-id X ont-srvprofile-id Y}
     *
     * <p>用自动发现表里那个 Number 直接确认，ONT ID 由设备分配。认证方式只给关键字
     * （{@code sn-auth} / {@code loid-auth} 这种），值设备从那条待注册记录里自己取——
     * 带上值反而容易被不同版本挑语法错误。</p>
     *
     * <p>线路/业务模板 ID 可选：模板里没填就不带这两个参数，设备按默认模板走。</p>
     */
    @Override
    public String ontConfirmCommand(int port, int autofindNo, String authMode,
                                    Integer lineProfileId, Integer srvProfileId) {
        String keyword = ontAuthKeyword(authMode);
        if (keyword == null) {
            return null;
        }
        StringBuilder command = new StringBuilder("ont confirm ")
                .append(port).append(' ').append(autofindNo).append(' ').append(keyword).append(" omci");
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

    @Override
    public String ontDeleteCommand(int port, int ontId) {
        return "ont delete " + port + " " + ontId;
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
}
