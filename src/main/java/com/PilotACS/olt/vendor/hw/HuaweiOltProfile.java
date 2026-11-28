/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.832
 *
 */

package com.PilotACS.olt.vendor.hw;

import com.PilotACS.olt.model.OltOnt;
import com.PilotACS.olt.model.OltOntAutofind;
import com.PilotACS.olt.model.OltQuickSetupRequest;
import com.PilotACS.olt.model.OltTerminalUserRequest;
import com.PilotACS.olt.telnet.OltCliSession;
import com.PilotACS.olt.telnet.OltInfoParser;
import com.PilotACS.olt.telnet.OltVendor;
import com.PilotACS.olt.vendor.AbstractOltVendorProfile;
import com.PilotACS.olt.vendor.OltCli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 华为 MA5600T / MA5683T / MA5800 / EA5800 */
public class HuaweiOltProfile extends AbstractOltVendorProfile {

    /** 型号 */
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
        cli.run("enable", timeoutMs);
        String out = cli.run("config", timeoutMs);
        if (out == null || !out.contains("(config)#")) {
            return false;
        }
        cli.run("scroll", timeoutMs);
        return true;
    }

    /** 是否支持一键开局 */
    @Override
    public boolean supportsQuickSetup() {
        return true;
    }

    /** 一键开局命令序列 */
    @Override
    public List<String> quickSetupCommands(OltQuickSetupRequest request) {
        if (request == null || request.getHuawei() == null) {
            return List.of();
        }
        return HuaweiQuickSetupPlanner.plan(request.getHuawei());
    }

    @Override
    public boolean supportsOntAutofind() {
        return true;
    }

    @Override
    public List<String> ontAutofindCommands() {
        return List.of("display ont autofind all");
    }

    /** 解析待注册 ONT */
    @Override
    public List<OltOntAutofind> parseOntAutofind(String text) {
        List<OltOntAutofind> items = OltInfoParser.parseOntAutofind(text);
        items.forEach(item -> item.setPonType("gpon"));
        return items;
    }

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

    /** 解析自动发现开关状态 */
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
        if (lower.contains("disable")) {
            return false;
        }
        if (lower.contains("enable")) {
            return true;
        }
        return null;
    }

    /** 开启 PON 口自动发现命令 */
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

    @Override
    public boolean supportsOntConfirm() {
        return true;
    }

    /** 生成 ONT 确认命令 */
    @Override
    public String ontConfirmCommand(int port, String authMode, String authValue,
                                    Integer lineProfileId, Integer srvProfileId) {
        String keyword = ontAuthKeyword(authMode);
        if (keyword == null) {
            return null;
        }
        if (authValue == null || authValue.isBlank()) {
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

    /** 生成改 ONT 描述命令 */
    @Override
    public String ontDescriptionCommand(int port, int ontId, String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String text = description.trim().replace("\"", "");
        return "ont modify " + port + ' ' + ontId + " desc \"" + text + "\"";
    }

    /** 是否支持页面读写下发 TR-069 模板 */
    @Override
    public boolean supportsTr069Profile() {
        return true;
    }

    @Override
    public List<String> tr069ProfileQueryCommands() {
        return List.of("display ont tr069-server-profile all");
    }

    /** 拼接 TR-069 模板新增命令：ont tr069-server-profile add profile-id 10 profile-name acs url http://x user acs acs123 */
    @Override
    public List<String> createTr069ProfileCommands(com.PilotACS.olt.model.OltTr069ProfileRequest request) {
        if (request == null || request.getProfileId() == null) {
            return List.of();
        }
        StringBuilder command = new StringBuilder("ont tr069-server-profile add profile-id ")
                .append(request.getProfileId());
        appendTr069Arg(command, "profile-name", request.getProfileName());
        appendTr069Arg(command, "url", request.getUrl());
        String user = trimToNull(request.getAcsUsername());
        String password = trimToNull(request.getAcsPassword());
        if (user != null) {
            command.append(" user ").append(user);
            if (password != null) {
                command.append(' ').append(password);
            }
        }
        return List.of(command.toString());
    }

    /** 解析 TR-069 模板列表（display 表格 / 配置脚本两种回显都认） */
    @Override
    public List<com.PilotACS.olt.model.OltTr069Profile> parseTr069Profiles(String text) {
        return OltInfoParser.parseTr069Profiles(text);
    }

    /** display all 只有 ID/名称/绑定数，ACS 地址与账号要逐条查详情 */
    @Override
    public String tr069ProfileDetailCommand(int profileId) {
        return "display ont tr069-server-profile profile-id " + profileId;
    }

    /**
     * 新建 ONT 线路模板命令序列。
     *
     * <p>GPON：进模板视图 → tcont 1 绑 DBA → 每个 VLAN 一个 GEM Port + 映射 →
     * 可选 tr069-management enable → commit；EPON：进模板视图 → llid 绑 DBA → commit。
     * 与一键开局里 {@code HuaweiQuickSetupPlanner} 那套命令同款。</p>
     */
    @Override
    public List<String> createLineProfileCommands(com.PilotACS.olt.model.OltLineProfileCreateRequest request) {
        if (request == null || request.getProfileId() == null || request.getDbaProfileId() == null) {
            return List.of();
        }
        boolean epon = "EPON".equalsIgnoreCase(request.getPonType());
        String pon = epon ? "epon" : "gpon";
        List<String> commands = new ArrayList<>();
        commands.add("ont-lineprofile " + pon + " profile-id " + request.getProfileId()
                + " profile-name \"" + request.getName().trim() + "\"");
        if (epon) {
            commands.add("llid dba-profile-id " + request.getDbaProfileId());
        } else {
            commands.add("tcont 1 dba-profile-id " + request.getDbaProfileId());
            int gem = 0;
            for (Integer vlan : request.vlanIds()) {
                commands.add("gem add " + gem + " eth tcont 1 encrypt on");
                commands.add("gem mapping " + gem + " 0 vlan " + vlan);
                gem++;
            }
            if (Boolean.TRUE.equals(request.getTr069Enable())) {
                commands.add("tr069-management enable");
                commands.add("tr069-management ip-index 0");
            }
        }
        commands.add("commit");
        commands.add("quit");
        return commands;
    }

    /**
     * 删除 ONT 线路模板命令序列（MA5680T 实测）。GPON：进模板视图 → 逐个 undo GEM 映射 →
     * commit（视图里的 undo 不 commit 不生效）→ quit → 删模板本身，残留的 GEM/T-CONT
     * 随模板一起删掉，不用逐个 undo；EPON 没有 GEM/T-CONT，不用进视图清理，直接删模板。
     */
    @Override
    public List<String> deleteLineProfileCommands(String ponType, int profileId,
                                                  com.PilotACS.olt.model.OltLineProfile line) {
        if ("epon".equalsIgnoreCase(ponType)) {
            return List.of("undo ont-lineprofile epon profile-id " + profileId);
        }
        List<String> commands = new ArrayList<>();
        commands.add("ont-lineprofile gpon profile-id " + profileId);
        if (line != null && line.getGemports() != null) {
            for (com.PilotACS.olt.model.OltGemport gem : line.getGemports()) {
                if (gem.getGemportId() == null || gem.getMappings() == null) {
                    continue;
                }
                for (com.PilotACS.olt.model.OltGemMapping mapping : gem.getMappings()) {
                    if (mapping.getIndex() != null) {
                        commands.add("undo gem mapping " + gem.getGemportId()
                                + " " + mapping.getIndex());
                    }
                }
            }
        }
        commands.add("commit");
        commands.add("quit");
        commands.add("undo ont-lineprofile gpon profile-id " + profileId);
        return commands;
    }

    private static void appendTr069Arg(StringBuilder command, String key, String value) {
        String text = trimToNull(value);
        if (text != null) {
            command.append(' ').append(key).append(' ').append(text);
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** 是否支持 ONT 绑定 TR-069 服务器模板 */
    @Override
    public boolean supportsTr069ServerConfig() {
        return true;
    }

    @Override
    public String tr069ServerConfigCommand(int port, int ontId, int tr069ProfileId) {
        return "ont tr069-server-config " + port + ' ' + ontId + " profile-id " + tr069ProfileId;
    }

    /** 是否支持 ONT 以太口 native-vlan */
    @Override
    public boolean supportsOntPortNativeVlan() {
        return true;
    }

    @Override
    public String ontPortNativeVlanCommand(int port, int ontId, int eth, int userVlan) {
        return "ont port native-vlan " + port + ' ' + ontId + " eth " + eth
                + " vlan " + userVlan + " priority 0";
    }

    /** 认证方式 → 设备关键字 */
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
    public String boardConfirmCommand() {
        return "board confirm 0";
    }

    /** 线路模板详情命令 */
    @Override
    public String lineProfileDetailCommand(String ponType, int profileId) {
        return "display " + lineProfileView(ponType, profileId);
    }

    /** 加 T-CONT 命令 */
    @Override
    public List<String> lineTcontAddCommands(String ponType, int profileId, int tcontId, int dbaProfileId) {
        return lineProfileCommands(lineProfileView(ponType, profileId),
                "tcont " + tcontId + " dba-profile-id " + dbaProfileId);
    }

    /** 加 GEM Port 命令 */
    @Override
    public List<String> lineGemportAddCommands(String ponType, int profileId, int gemportId, int tcontId) {
        return lineProfileCommands(lineProfileView(ponType, profileId),
                "gem add " + gemportId + " eth tcont " + tcontId + " encrypt on");
    }

    /** 加 GEM 映射命令 */
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

    /** 生成线路模板视图命令 */
    private static String lineProfileView(String ponType, int profileId) {
        String view = ponType == null || ponType.isBlank()
                ? "gpon" : ponType.trim().toLowerCase(Locale.ROOT);
        return "ont-lineprofile " + view + " profile-id " + profileId;
    }

    /** 模板视图内的命令块 */
    private static List<String> lineProfileCommands(String view, String... body) {
        List<String> commands = new ArrayList<>();
        commands.add(view);
        commands.addAll(java.util.Arrays.asList(body));
        commands.add("commit");
        commands.add("quit");
        return commands;
    }

    /** 是否支持业务虚口 */
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

    /** 内外 VLAN 翻译业务虚口命令 */
    @Override
    public String servicePortTranslateCommand(int vlan, int frame, int slot, int port,
                                              int ontId, int gemPort, int userVlan) {
        return "service-port vlan " + vlan + " gpon "
                + frame + "/" + slot + "/" + port + " ont " + ontId + " gemport " + gemPort
                + " multi-service user-vlan " + userVlan + " tag-transform translate";
    }

    @Override
    public String ontDeleteCommand(int port, int ontId) {
        return "ont delete " + port + " " + ontId;
    }

    /** 复位单个 ONT 命令 */
    @Override
    public String ontResetCommand(int port, int ontId) {
        return "ont reset " + port + " " + ontId;
    }

    /** 复位整条 PON 口 ONT 命令 */
    @Override
    public String ontResetPortCommand(int port) {
        return "ont reset " + port + " all";
    }

    /** 查 ONT 业务虚口命令 */
    @Override
    public String servicePortListCommand(int frame, int slot, int port, Integer ontId) {
        String base = "display service-port port " + frame + "/" + slot + "/" + port;
        return ontId == null ? base : base + " ont " + ontId;
    }

    /** 删业务虚口命令 */
    @Override
    public String servicePortDeleteCommand(int index) {
        return "undo service-port " + index;
    }

    @Override
    public String rebootCommand() {
        return "reboot system";
    }

    /** 链路聚合查询命令 */
    @Override
    public String linkAggregationQueryCommand(String target) {
        return "display link-aggregation " + (target == null || target.isBlank() ? "all" : target.trim());
    }

    /** 创建链路聚合组命令 */
    @Override
    public String linkAggregationCreateCommand(String masterFs, String ports,
                                               String direction, String workmode) {
        return "link-aggregation " + masterFs + " " + ports + " " + direction
                + " workmode " + workmode;
    }

    /** 删除链路聚合组命令 */
    @Override
    public String linkAggregationDeleteCommand(String masterFs) {
        return "undo link-aggregation " + masterFs;
    }

    /** 按端口删业务虚口命令 */
    @Override
    public String servicePortDeleteByPortCommand(int frame, int slot, int port,
                                                 int ontId, int gemport, int userVlan) {
        return "undo service-port port " + frame + "/" + slot + "/" + port
                + " ont " + ontId + " gemport " + gemport + " user-vlan " + userVlan;
    }

    /** 板卡详情命令 */
    @Override
    public String boardDetailCommand(int frame, int slot) {
        return "display board " + frame + "/" + slot;
    }

    /** 清空 PON 口全部 ONT 命令 */
    @Override
    public String ontDeleteAllCommand(int port) {
        return "ont delete " + port + " all";
    }

    /** 按 PON 类型生成板视图命令 */
    @Override
    public String ponViewCommand(int frame, int slot, String ponType) {
        return "interface " + ("epon".equalsIgnoreCase(ponType) ? "epon" : "gpon") + " " + frame + "/" + slot;
    }

    /** 整块板 ONT 明细命令 */
    @Override
    public String ontListCommand(int frame, int slot) {
        return "display ont info " + frame + " " + slot + " all";
    }

    @Override
    public Map<Integer, List<OltOnt>> parseOntList(String text) {
        return OltInfoParser.parseOntDetails(text);
    }

    @Override
    public boolean supportsTerminalUser() {
        return true;
    }

    /** 建本地用户命令序列 */
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
                OltCliSession.ANSWER_PREFIX + password,
                OltCliSession.ANSWER_PREFIX + profile,
                OltCliSession.ANSWER_PREFIX + level,
                OltCliSession.ANSWER_PREFIX + reenter,
                OltCliSession.ANSWER_PREFIX + info,
                OltCliSession.ANSWER_PREFIX + "n"
        );
    }

    /** 上行口放通 VLAN 命令 */
    @Override
    public List<String> addPortVlanCommands(String iface, List<Integer> vlanIds, boolean nativeVlan) {
        List<String> targets = uplinkTargets(iface);
        if (targets.isEmpty() || vlanIds == null || vlanIds.isEmpty()) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        vlanIds.stream().filter(id -> id != null).distinct().sorted()
                .forEach(id -> commands.add("vlan " + id + " smart"));
        for (String target : targets) {
            commands.add("port vlan " + joinVlans(vlanIds) + " " + target + (nativeVlan ? " native" : ""));
        }
        return commands;
    }

    /** 上行口解除 VLAN 绑定命令 */
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

    /** 解析上行口清单 */
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

    /** 拆分上行口路径与端口号 */
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

    /** VLAN 列表格式化，升序去重 */
    private static String joinVlans(List<Integer> vlanIds) {
        return vlanIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }
}
