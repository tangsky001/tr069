/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.412
 *
 */

package com.PilotACS.olt.vendor.hw;

import com.PilotACS.olt.model.HuaweiQuickSetupRequest;
import com.PilotACS.olt.model.VlanIdExpression;

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

    /** 带宽上限（Mbps） */
    public static final int MAX_BANDWIDTH_MBPS = 1024;

    /** 三套模板（DBA / 线路 / 业务）的默认 ID */
    public static final int DEFAULT_PROFILE_ID = 100;

    /** TR-069 模板固定叫这个名字，不跟带宽变 */
    private static final String TR069_PROFILE_NAME = "TR069_ACS";

    /** TR-069 模板 ID 区间 */
    public static final int MIN_TR069_PROFILE_ID = 1;
    public static final int MAX_TR069_PROFILE_ID = 32;

    /** TR-069 模板默认 ID */
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
        int dbaId = profileIdOf(request.getDbaProfileId(), "DBA 模板 ID");
        int lineId = profileIdOf(request.getLineProfileId(), "ONT 线路模板 ID");
        int srvId = profileIdOf(request.getSrvProfileId(), "ONT 业务模板 ID");
        int tr069Id = tr069ProfileIdOf(request.getTr069ProfileId());
        String ponView = ponView(request.getPonType());
        String lineName = "FTTH-" + ponView.toUpperCase(Locale.ROOT) + "-" + bandwidth;
        List<Integer> gemVlans = gemVlanOrder(request);

        List<String> commands = new ArrayList<>();
        addSwitchingCommands(commands, request, gemVlans);
        addDbaProfile(commands, dbaId, bandwidth);
        addLineProfile(commands, ponView, lineId, dbaId, lineName, gemVlans);
        addServiceProfile(commands, ponView, srvId, lineName);
        addTr069Profile(commands, request, tr069Id);
        commands.add("save");
        return commands;
    }

    /** VLAN、上联口放通、管理 VLAN 三层地址与缺省路由 */
    private static void addSwitchingCommands(List<String> commands, HuaweiQuickSetupRequest request,
                                             List<Integer> vlans) {
        requireInt(request.getManageVlan(), "管理 VLAN");
        for (Integer vlan : vlans) {
            commands.add("vlan " + vlan + " smart");
        }

        List<String[]> uplinks = parseUplinkPorts(request.getUplinkPorts());
        for (String[] uplink : uplinks) {
            for (Integer vlan : vlans) {
                commands.add("port vlan " + vlan + " " + uplink[0] + " " + uplink[1]);
            }
        }

        String[] ipMask = parseManageIp(request.getManageVlanIp());
        commands.add("interface vlanif " + request.getManageVlan());
        commands.add("ip address " + ipMask[0] + " " + ipMask[1]);
        commands.add("quit");

        String gateway = trimToNull(request.getDefaultRoute());
        if (gateway != null) {
            commands.add("ip route-static 0.0.0.0 0.0.0.0 " + gateway);
        }
    }

    /** DBA 模板命令 */
    private static void addDbaProfile(List<String> commands, int profileId, int bandwidth) {
        commands.add("dba-profile add profile-id " + profileId
                + " profile-name \"" + bandwidth + "M\" type4 max " + bandwidth * KBPS_PER_MBPS);
    }

    /** ONT 线路模板命令 */
    private static void addLineProfile(List<String> commands, String ponView, int profileId,
                                       int dbaProfileId, String lineName, List<Integer> gemVlans) {
        commands.add("ont-lineprofile " + ponView + " profile-id " + profileId
                + " profile-name \"" + lineName + "\"");
        commands.add("tcont 1 dba-profile-id " + dbaProfileId);
        int gem = 0;
        for (Integer vlan : gemVlans) {
            commands.add("gem add " + gem + " eth tcont 1 encrypt on");
            commands.add("gem mapping " + gem + " 0 vlan " + vlan);
            gem++;
        }
        commands.add("tr069-management enable");
        commands.add("tr069-management ip-index 0");
        commands.add("commit");
        commands.add("quit");
    }

    /** ONT 业务模板命令 */
    private static void addServiceProfile(List<String> commands, String ponView, int profileId,
                                          String lineName) {
        commands.add("ont-srvprofile " + ponView + " profile-id " + profileId
                + " profile-name \"" + lineName + "\"");
        commands.add("ont-port eth adaptive");
        commands.add("port vlan eth 1 transparent");
        commands.add("commit");
        commands.add("quit");
    }

    /** TR-069 模板命令 */
    private static void addTr069Profile(List<String> commands, HuaweiQuickSetupRequest request, int profileId) {
        String url = trimToNull(request.getAcsUrl());
        if (url == null) {
            throw new IllegalArgumentException("请填写 TR069 ACS 地址");
        }
        commands.add("ont tr069-server-profile add profile-id " + profileId
                + " profile-name \"" + TR069_PROFILE_NAME + "\""
                + " url \"" + url + "\"");
    }

    /** 计算 GEM 映射的 VLAN 列表 */
    private static List<Integer> gemVlanOrder(HuaweiQuickSetupRequest request) {
        LinkedHashSet<Integer> vlans = new LinkedHashSet<>();
        vlans.add(requireInt(request.getTr069Vlan(), "TR069 VLAN"));
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

    /** 模板 ID 校验 */
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

    /** TR-069 模板 ID 校验 */
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

    /** PON 类型命令关键字 */
    private static String ponView(String ponType) {
        return ponType != null && ponType.trim().equalsIgnoreCase("EPON") ? "epon" : "gpon";
    }

    /** 解析上联端口 */
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

    /** 解析管理 VLAN IP */
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

    /** 掩码位数转点分十进制 */
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
