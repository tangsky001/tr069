package com.tr069.acs.olt.vendor.hw;

import com.tr069.acs.olt.model.HuaweiQuickSetupRequest;
import com.tr069.acs.olt.model.VlanIdExpression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 华为 MA56XXT 一键开局的模板编排 */
public final class HuaweiQuickSetupPlanner {

    /** 模板 ID 区间：设备上 DBA 的 profile-id 只认 10~512 */
    public static final int MIN_PROFILE_ID = 10;
    public static final int MAX_PROFILE_ID = 512;

    /** 带宽下限（Mbps） */
    public static final int MIN_BANDWIDTH_MBPS = 1;

    /** 带宽上限（Mbps）：GPON 上行 1.25G，1024M 已经贴到边。页面上的输入框上限也取它，别再抄一份 */
    public static final int MAX_BANDWIDTH_MBPS = 1024;

    /** 三套模板（DBA / 线路 / 业务）的默认 ID */
    public static final int DEFAULT_PROFILE_ID = 100;

    /** TR-069 模板固定叫这个名字，不跟带宽变 */
    private static final String TR069_PROFILE_NAME = "TR069_ACS";

    /** TR-069 模板 ID 区间是 1~32（问 {@code ont tr069-server-profile add profile-id 512 ?} */
    public static final int MIN_TR069_PROFILE_ID = 1;
    public static final int MAX_TR069_PROFILE_ID = 32;

    /** TR-069 模板的默认 ID：内容只有 ACS 地址一项，跟带宽没关系，默认就占 1 号 */
    public static final int DEFAULT_TR069_PROFILE_ID = 1;

    /** DBA 带宽单位是 kbit/s，1 Mbps 按 1024 kbit/s 折算 */
    private static final long KBPS_PER_MBPS = 1024L;

    private HuaweiQuickSetupPlanner() {
    }

    /** 编出这次开局要下发的全部命令 */
    public static List<String> plan(HuaweiQuickSetupRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请把开局参数填完整");
        }

        int bandwidth = requireBandwidth(request);
        // ID 就是 ID，不再从带宽推：带宽只决定名字和 DBA 的限速值
        int dbaId = profileIdOf(request.getDbaProfileId(), "DBA 模板 ID");
        int lineId = profileIdOf(request.getLineProfileId(), "ONT 线路模板 ID");
        int srvId = profileIdOf(request.getSrvProfileId(), "ONT 业务模板 ID");
        int tr069Id = tr069ProfileIdOf(request.getTr069ProfileId());
        String ponView = ponView(request.getPonType());
        String lineName = "FTTH-" + ponView.toUpperCase(Locale.ROOT) + "-" + bandwidth;
        List<Integer> gemVlans = gemVlanOrder(request);

        List<String> commands = new ArrayList<>();
        // 顺序就是依赖顺序：先有 VLAN 与 DBA，线路模板才引用得到
        addSwitchingCommands(commands, request, gemVlans);
        addDbaProfile(commands, dbaId, bandwidth);
        // 线路模板里 tcont 引用的是 DBA 的 ID，不是自己的
        addLineProfile(commands, ponView, lineId, dbaId, lineName, gemVlans);
        addServiceProfile(commands, ponView, srvId, lineName);
        addTr069Profile(commands, request, tr069Id);
        // 华为只写运行配置，不存盘掉电就回到开局前
        commands.add("save");
        return commands;
    }

    // ------------------------------------------------------------------ 一、二层与三层
    /** VLAN、上联口放通、管理 VLAN 三层地址与缺省路由 */
    private static void addSwitchingCommands(List<String> commands, HuaweiQuickSetupRequest request,
                                             List<Integer> vlans) {
        // 管理 VLAN 要有值才建得了三层接口
        requireInt(request.getManageVlan(), "管理 VLAN");
        for (Integer vlan : vlans) {
            commands.add("vlan " + vlan + " smart");
        }

        // 上联口把这些 VLAN 放通：多个端口 × 多个 VLAN 逐条下发
        List<String[]> uplinks = parseUplinkPorts(request.getUplinkPorts());
        for (String[] uplink : uplinks) {
            for (Integer vlan : vlans) {
                commands.add("port vlan " + vlan + " " + uplink[0] + " " + uplink[1]);
            }
        }

        // 管理 VLAN 的三层接口：这条 ONT 管理通道和 ACS 互通都靠它
        String[] ipMask = parseManageIp(request.getManageVlanIp());
        commands.add("interface vlanif " + request.getManageVlan());
        commands.add("ip address " + ipMask[0] + " " + ipMask[1]);
        commands.add("quit");

        String gateway = trimToNull(request.getDefaultRoute());
        if (gateway != null) {
            commands.add("ip route-static 0.0.0.0 0.0.0.0 " + gateway);
        }
    }

    // ------------------------------------------------------------------ 二、DBA 模板
    /** DBA 模板：Type4（best-effort）只给 max，上行限速就是页面上那个带宽 */
    private static void addDbaProfile(List<String> commands, int profileId, int bandwidth) {
        // ID 走 profileId（10~512），名字和带宽值仍按实际 Mbps 写
        commands.add("dba-profile add profile-id " + profileId
                + " profile-name \"" + bandwidth + "M\" type4 max " + bandwidth * KBPS_PER_MBPS);
    }

    // ------------------------------------------------------------------ 三、线路模板
    /** ONT 线路模板：T-CONT → DBA，GEM → T-CONT，GEM 映射 → VLAN，全部在这个视图里配完 */
    private static void addLineProfile(List<String> commands, String ponView, int profileId,
                                       int dbaProfileId, String lineName, List<Integer> gemVlans) {
        commands.add("ont-lineprofile " + ponView + " profile-id " + profileId
                + " profile-name \"" + lineName + "\"");
        // T-CONT 挂到刚才那条 DBA 模板上（按 DBA 的 ID 引用，两个 ID 可以不同）
        commands.add("tcont 1 dba-profile-id " + dbaProfileId);
        int gem = 0;
        for (Integer vlan : gemVlans) {
            commands.add("gem add " + gem + " eth tcont 1 encrypt on");
            commands.add("gem mapping " + gem + " 0 vlan " + vlan);
            gem++;
        }
        // OMCI 侧打开 TR-069 管理，ONT 才会按三层接口去连 ACS
        commands.add("tr069-management enable");
        commands.add("tr069-management ip-index 0");
        commands.add("commit");
        commands.add("quit");
    }

    // ------------------------------------------------------------------ 四、业务模板
    /** ONT 业务模板：端口数自适应，ETH1 透传，用户 VLAN 由上层 BRAS 认 */
    private static void addServiceProfile(List<String> commands, String ponView, int profileId,
                                          String lineName) {
        commands.add("ont-srvprofile " + ponView + " profile-id " + profileId
                + " profile-name \"" + lineName + "\"");
        commands.add("ont-port eth adaptive");
        commands.add("port vlan eth 1 transparent");
        commands.add("commit");
        commands.add("quit");
    }

    // ------------------------------------------------------------------ 五、TR-069 模板
    /** TR-069 管理模板：只有 ACS 地址 */
    private static void addTr069Profile(List<String> commands, HuaweiQuickSetupRequest request, int profileId) {
        String url = trimToNull(request.getAcsUrl());
        if (url == null) {
            throw new IllegalArgumentException("请填写 TR069 ACS 地址");
        }
        // 只有这三个参数。多给 username / password 会被回 % Too many parameters，
        // 也就是页面上那句 "% Parameter error, the error locates at '^'" 的来源
        commands.add("ont tr069-server-profile add profile-id " + profileId
                + " profile-name \"" + TR069_PROFILE_NAME + "\""
                + " url \"" + url + "\"");
    }

    // ------------------------------------------------------------------ 取值辅助
    /** GEM 映射要收哪些 VLAN：管理/TR-069 VLAN 排第一（这是 ONT 的管理通道） */
    private static List<Integer> gemVlanOrder(HuaweiQuickSetupRequest request) {
        LinkedHashSet<Integer> vlans = new LinkedHashSet<>();
        vlans.add(requireInt(request.getTr069Vlan(), "TR069 VLAN"));
        // 业务 VLAN 是表达式写法，这里展开成一个个 ID
        vlans.addAll(VlanIdExpression.expand(trimToNull(request.getServiceVlan()) == null
                ? "" : request.getServiceVlan()));
        if (request.getIptvMulticastVlan() != null) {
            vlans.add(request.getIptvMulticastVlan());
        }
        return new ArrayList<>(vlans);
    }

    private static int requireBandwidth(HuaweiQuickSetupRequest request) {
        Integer bandwidth = request.getDbaBandwidthMbps();
        if (bandwidth == null) {
            throw new IllegalArgumentException("请填写 DBA 模版带宽");
        }
        if (bandwidth < MIN_BANDWIDTH_MBPS || bandwidth > MAX_BANDWIDTH_MBPS) {
            throw new IllegalArgumentException(
                    "DBA 模版带宽要在 " + MIN_BANDWIDTH_MBPS + "-" + MAX_BANDWIDTH_MBPS + " Mbps 之间：" + bandwidth);
        }
        return bandwidth;
    }

    /** 三套模板的 ID：没填就用默认值，填了必须在设备认的区间里 */
    private static int profileIdOf(Integer value, String label) {
        if (value == null) {
            return DEFAULT_PROFILE_ID;
        }
        if (value < MIN_PROFILE_ID || value > MAX_PROFILE_ID) {
            throw new IllegalArgumentException(
                    label + "要在 " + MIN_PROFILE_ID + "-" + MAX_PROFILE_ID + " 之间：" + value);
        }
        return value;
    }

    /** TR-069 模板的 ID：区间比三套模板窄（1~32），没填就用默认 */
    private static int tr069ProfileIdOf(Integer value) {
        if (value == null) {
            return DEFAULT_TR069_PROFILE_ID;
        }
        if (value < MIN_TR069_PROFILE_ID || value > MAX_TR069_PROFILE_ID) {
            throw new IllegalArgumentException("TR069 模板 ID 要在 " + MIN_TR069_PROFILE_ID + "-"
                    + MAX_TR069_PROFILE_ID + " 之间：" + value);
        }
        return value;
    }

    private static int requireInt(Integer value, String label) {
        if (value == null) {
            throw new IllegalArgumentException("请填写" + label);
        }
        if (value < 1 || value > VlanIdExpression.MAX_VLAN_ID) {
            throw new IllegalArgumentException(label + "要在 1-" + VlanIdExpression.MAX_VLAN_ID + " 之间：" + value);
        }
        return value;
    }

    /** {@code GPON → gpon}、{@code EPON → epon}，命令里用的是小写，识别不了就按 GPON 走 */
    private static String ponView(String ponType) {
        return ponType != null && ponType.trim().equalsIgnoreCase("EPON") ? "epon" : "gpon";
    }

    /** 上联端口拆成「框/槽（+子槽）」和「端口号」：最后一段是端口，前面是槽位路径 */
    private static List<String[]> parseUplinkPorts(String text) {
        if (trimToNull(text) == null) {
            return List.of();
        }
        List<String[]> uplinkPorts = new ArrayList<>();
        for (String part : text.split("[,，]")) {
            String port = part.trim();
            if (port.isEmpty()) {
                continue;
            }
            String[] pieces = port.split("/");
            if (pieces.length < 3) {
                throw new IllegalArgumentException(
                        "上联端口要写到端口号：" + port + "，应该像 0/6/2 或 0/0/6/1");
            }
            String portNo = pieces[pieces.length - 1];
            StringBuilder path = new StringBuilder(pieces[0]);
            for (int i = 1; i < pieces.length - 1; i++) {
                path.append('/').append(pieces[i]);
            }
            uplinkPorts.add(new String[]{path.toString(), portNo});
        }
        return uplinkPorts;
    }

    /** 管理 VLAN IP 支持 {@code 10.172.10.254/255.255.255.0} 和 {@code 10.172.10.254/24} 两种写法 */
    private static String[] parseManageIp(String text) {
        String value = trimToNull(text);
        if (value == null) {
            throw new IllegalArgumentException("请填写管理 VLAN IP 地址");
        }
        int slash = value.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("管理 VLAN IP 地址要带掩码，例如 10.172.10.254/255.255.255.0");
        }
        String ip = value.substring(0, slash).trim();
        String mask = value.substring(slash + 1).trim();
        if (ip.isEmpty()) {
            throw new IllegalArgumentException("管理 VLAN IP 地址不完整：" + value);
        }
        return new String[]{ip, toMask(mask)};
    }

    /** 掩码位数转点分十进制；本来就是掩码的直接用 */
    private static String toMask(String mask) {
        if (mask.indexOf('.') >= 0) {
            return mask;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(mask);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("掩码写法不对：" + mask + "，用 24 或 255.255.255.0");
        }
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("掩码位数要在 0-32 之间：" + mask);
        }
        long bits = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return ((bits >> 24) & 0xFF) + "." + ((bits >> 16) & 0xFF) + "." + ((bits >> 8) & 0xFF) + "." + (bits & 0xFF);
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
