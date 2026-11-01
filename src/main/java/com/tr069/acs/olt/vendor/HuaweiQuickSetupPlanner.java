package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.model.HuaweiQuickSetupRequest;
import com.tr069.acs.olt.model.VlanIdExpression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 华为 MA56XXT 一键开局的模板编排。
 *
 * <p><b>这里唯一要紧的就是顺序。</b>四套模板之间是引用关系，不是并列关系：</p>
 *
 * <pre>
 *   DBA 模板（1000 / 1000M，type4）
 *        ↑ 被引用
 *   ONT 线路模板（1000 / FTTH-GPON-1000）
 *        └─ T-CONT 1 ──引用── DBA 1000
 *        └─ GEM 1 ──挂在── T-CONT 1
 *        └─ GEM 映射 0,1,2 ──绑── 各业务 VLAN
 *        ↑ 被引用
 *   ONT 业务模板（1000 / FTTH-GPON-1000）
 *
 *   TR-069 管理模板（1000 / TR069_ACS）
 *        └─ 独立命名，但在注册 ONT 时与线路模板一起指定
 * </pre>
 *
 * <p>线路模板里 {@code tcont 1 dba-profile-id 1000} 必须能查到那条 DBA，
 * 顺序反了设备直接回语法/参数错误——这也是为什么整段命令由这里一次编好，
 * 而不是按表单字段的顺序想到哪拼到哪。</p>
 *
 * <p><b>命名从带宽推导，ID 压到设备认的区间</b>（页面上只填带宽）：1000 Mbps →
 * DBA 名 {@code 1000M}、线路与业务模板名 {@code FTTH-GPON-1000}、TR-069 模板名
 * {@code TR069_ACS}，四套模板共用一个 ID 便于对照。ID 不直接取带宽——DBA 的
 * profile-id 只认 10~512，1000M 照抄成 1000 会被设备顶回来。</p>
 *
 * <p>这个类不碰设备、不做会话，只负责把请求编成命令，交给
 * {@code OltService} 下发；同样的方法也用于预览，所以页面看到的必然是要下发的。</p>
 */
public final class HuaweiQuickSetupPlanner {

    /**
     * 模板 ID 区间：设备上 DBA 的 profile-id 只认 10~512。
     *
     * <p>在 MA5680T 上问 {@code dba-profile add profile-id 1000 ?}，设备回的原文是
     * {@code profile-id<U><10,512>}；填 1000 就是
     * {@code % Parameter error, the error locates at '^'}。</p>
     */
    private static final int MIN_PROFILE_ID = 10;
    private static final int MAX_PROFILE_ID = 512;

    /** 带宽上限（Mbps）：GPON 上行 1.25G，1024M 已经贴到边 */
    private static final int MAX_BANDWIDTH_MBPS = 1024;

    /** TR-069 模板固定叫这个名字，不跟带宽变 */
    private static final String TR069_PROFILE_NAME = "TR069_ACS";

    /**
     * TR-069 模板 ID 固定用 1。
     *
     * <p>设备只认 1~32（问 {@code ont tr069-server-profile add profile-id 512 ?}，
     * 回来的是 {@code profile-id<U><1,32>}），比 DBA 的 10~512 窄得多。而且这个模板
     * 的内容只有 ACS 地址一项，跟带宽没关系，没必要跟着带宽变。</p>
     */
    private static final int TR069_PROFILE_ID = 1;

    /**
     * DBA 带宽单位是 kbit/s，1 Mbps 按 1024 kbit/s 折算。
     *
     * <p>按局点样例来：{@code UPINTERNET1024M} 写的是
     * {@code type3 assure 100960 max 1024896}，和 1024×1024 同一量级，
     * 所以换算走 1024 进制——1000M 就是 {@code max 1024000}。</p>
     */
    private static final long KBPS_PER_MBPS = 1024L;

    private HuaweiQuickSetupPlanner() {
    }

    /**
     * 编出这次开局要下发的全部命令。
     *
     * @throws IllegalArgumentException 参数不全或不合法，消息直接显示给用户
     */
    public static List<String> plan(HuaweiQuickSetupRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请把开局参数填完整");
        }

        int bandwidth = requireBandwidth(request);
        // 模板 ID 不等于带宽：DBA 只认 10~512，1000M 落到 1000 上会被拒
        int profileId = profileIdOf(bandwidth);
        String ponView = ponView(request.getPonType());
        String lineName = "FTTH-" + ponView.toUpperCase(Locale.ROOT) + "-" + bandwidth;
        List<Integer> gemVlans = gemVlanOrder(request);

        List<String> commands = new ArrayList<>();
        // 顺序就是依赖顺序：先有 VLAN 与 DBA，线路模板才引用得到
        addSwitchingCommands(commands, request, gemVlans);
        addDbaProfile(commands, profileId, bandwidth);
        addLineProfile(commands, ponView, profileId, lineName, gemVlans);
        addServiceProfile(commands, ponView, profileId, lineName);
        addTr069Profile(commands, request);
        // 华为只写运行配置，不存盘掉电就回到开局前
        commands.add("save");
        return commands;
    }

    // ------------------------------------------------------------------ 一、二层与三层
    /**
     * VLAN、上联口放通、管理 VLAN 三层地址与缺省路由。
     *
     * <p>VLAN 要在 GEM 映射之前存在，所以排在模板前面。</p>
     */
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
    /**
     * DBA 模板：Type4（best-effort）只给 max，上行限速就是页面上那个带宽。
     *
     * <p>必须第一个建——线路模板的 T-CONT 要引用它的 ID。</p>
     */
    private static void addDbaProfile(List<String> commands, int profileId, int bandwidth) {
        // ID 走 profileId（10~512），名字和带宽值仍按实际 Mbps 写
        commands.add("dba-profile add profile-id " + profileId
                + " profile-name \"" + bandwidth + "M\" type4 max " + bandwidth * KBPS_PER_MBPS);
    }

    // ------------------------------------------------------------------ 三、线路模板
    /**
     * ONT 线路模板：T-CONT → DBA，GEM → T-CONT，GEM 映射 → VLAN，全部在这个视图里配完。
     *
     * <p>映射索引从 0 开始递增，一个 GEM 承载多个 VLAN 的映射是常见做法，
     * 不用为每条业务流单开一个 GEM。</p>
     */
    private static void addLineProfile(List<String> commands, String ponView, int profileId,
                                       String lineName, List<Integer> gemVlans) {
        commands.add("ont-lineprofile " + ponView + " profile-id " + profileId
                + " profile-name \"" + lineName + "\"");
        // T-CONT 挂到刚才那条 DBA 模板上
        commands.add("tcont 1 dba-profile-id " + profileId);
        commands.add("gem add 1 eth tcont 1 encrypt on");
        int index = 0;
        for (Integer vlan : gemVlans) {
            commands.add("gem mapping 1 " + index++ + " vlan " + vlan);
        }
        // OMCI 侧打开 TR-069 管理，ONT 才会按三层接口去连 ACS
        commands.add("tr069-management enable");
        commands.add("tr069-management ip-index 0");
        commands.add("commit");
        commands.add("quit");
    }

    // ------------------------------------------------------------------ 四、业务模板
    /**
     * ONT 业务模板：端口数自适应，ETH1 透传，用户 VLAN 由上层 BRAS 认。
     *
     * <p>注册 ONT 时线路模板和业务模板要成对指定，两者 ID/名称在这里保持一致。</p>
     */
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
    /**
     * TR-069 管理模板：只有 ACS 地址。
     *
     * <p><b>模板里放不进账号密码</b>：这台 MA5680T 的 {@code ont tr069-server-profile add}
     * 只认 profile-id / profile-name / url 三个参数，多给 username、password 会回
     * {@code % Too many parameters}；全局也没有 {@code tr069-server} 这类配置 ACS
     * 凭据的命令。ACS 侧的认证只能由 ACS 服务器按设备序列号放行。</p>
     *
     * <p>放在最后：它本身不被前面引用，但注册 ONT 时要和线路模板一起拿出来用，
     * 编在一批命令里一次下发，省得配一半少一块。</p>
     */
    private static void addTr069Profile(List<String> commands, HuaweiQuickSetupRequest request) {
        String url = trimToNull(request.getAcsUrl());
        if (url == null) {
            throw new IllegalArgumentException("请填写 TR069 ACS 地址");
        }
        // 只有这三个参数。多给 username / password 会被回 % Too many parameters，
        // 也就是页面上那句 "% Parameter error, the error locates at '^'" 的来源
        commands.add("ont tr069-server-profile add profile-id " + TR069_PROFILE_ID
                + " profile-name \"" + TR069_PROFILE_NAME + "\""
                + " url \"" + url + "\"");
    }

    // ------------------------------------------------------------------ 取值辅助
    /**
     * GEM 映射要收哪些 VLAN：管理/TR-069 VLAN 排第一（这是 ONT 的管理通道），
     * 后面跟着业务 VLAN，最后是组播 VLAN。去重后保持这个顺序。
     */
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
        if (bandwidth < 1 || bandwidth > MAX_BANDWIDTH_MBPS) {
            throw new IllegalArgumentException(
                    "DBA 模版带宽要在 1-" + MAX_BANDWIDTH_MBPS + " Mbps 之间：" + bandwidth);
        }
        return bandwidth;
    }

    /**
     * 由带宽推出模板 ID。
     *
     * <p>带宽（Mbps）直接当 ID 用过一段时间，1000M 就落到 1000 上——DBA 只认 10~512，
     * 当场被拒。所以统一压到合法区间：10~512 的原样用，超出的按 512 走。
     * 命名仍然带带宽（{@code 1000M}、{@code FTTH-GPON-1000}），现场看名字就知道规格。</p>
     */
    private static int profileIdOf(int bandwidth) {
        return Math.max(MIN_PROFILE_ID, Math.min(MAX_PROFILE_ID, bandwidth));
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

    /**
     * {@code GPON → gpon}、{@code EPON → epon}，命令里用的是小写，识别不了就按 GPON 走。
     */
    private static String ponView(String ponType) {
        return ponType != null && ponType.trim().equalsIgnoreCase("EPON") ? "epon" : "gpon";
    }

    /**
     * 上联端口拆成「框/槽（+子槽）」和「端口号」：最后一段是端口，前面是槽位路径。
     *
     * <p>{@code 0/0/6/1} → 路径 {@code 0/0/6}、端口 {@code 1}；
     * {@code 0/6/2} → 路径 {@code 0/6}、端口 {@code 2}。
     * 只写了框和槽（{@code 0/6}）的没法知道端口号，直接报错让人补全，
     * 不要替他猜一个——猜错就是把整机的上联接到别的口上去了。</p>
     */
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

    /**
     * 管理 VLAN IP 支持 {@code 10.172.10.254/255.255.255.0} 和 {@code 10.172.10.254/24} 两种写法。
     */
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
