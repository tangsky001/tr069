package com.PilotACS.template.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 配置意图模板的类型清单与字段定义（静态数据）。 */
public final class TemplateFieldDefs {

    private TemplateFieldDefs() {
    }

    /** 模板类型：代码 -> 展示名 */
    public static final List<Map<String, String>> CATEGORIES = List.of(
            cat("WAN_DHCP", "WAN DHCP"),
            cat("WAN_PPPOE", "WAN PPPoE"),
            cat("WAN_STATIC", "WAN 静态IP"),
            cat("WAN_BRIDGE", "WAN 桥接"),
            cat("WIFI_24G", "2.4G WiFi"),
            cat("WIFI_58G", "5.8G WiFi"),
            cat("SIP", "SIP 语音"),
            cat("LAN", "LAN 网口"),
            cat("OTHER", "其他"));

    /** 绑定端口候选，供页面渲染勾选组 */
    public static final List<Map<String, Object>> BIND_PORT_GROUPS = List.of(
            portGroup("lan", "LAN", "LAN1", "LAN2", "LAN3", "LAN4", "LAN5", "LAN6", "LAN7", "LAN8"),
            portGroup("wifi24", "2.4G", "SSID1", "SSID2", "SSID3", "SSID4"),
            portGroup("wifi58", "5.8G", "SSID5", "SSID6", "SSID7", "SSID8"),
            portGroup("pon", "PON", "PON"));

    /** 各类型的意图字段定义 */
    public static final List<Map<String, Object>> FIELD_DEFS = buildFieldDefs();

    private static List<Map<String, Object>> buildFieldDefs() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (String wan : List.of("WAN_DHCP", "WAN_PPPOE", "WAN_STATIC", "WAN_BRIDGE")) {
            defs.addAll(wanFieldDefs(wan));
        }
        defs.add(def("WAN_PPPOE", "username", "拨号账号", "string", "", null, null));
        defs.add(def("WAN_PPPOE", "password", "拨号口令", "string", "", null,
                Map.of("inputType", "password")));
        defs.add(def("WAN_STATIC", "external_ip", "静态 IP", "string", "", null, null));
        defs.add(def("WAN_STATIC", "subnet_mask", "子网掩码", "string", "", null, null));
        defs.add(def("WAN_STATIC", "default_gateway", "默认网关", "string", "", null, null));

        defs.addAll(wifiFieldDefs("WIFI_24G", "2.4G WiFi 信道",
                List.of(opt("auto", "自动"),
                        opt("1", "1"), opt("2", "2"), opt("3", "3"), opt("4", "4"), opt("5", "5"),
                        opt("6", "6"), opt("7", "7"), opt("8", "8"), opt("9", "9"), opt("10", "10"),
                        opt("11", "11"), opt("12", "12"), opt("13", "13")),
                List.of(opt("auto", "自动"), opt("20MHz", "20MHz"), opt("40MHz", "40MHz"))));
        defs.addAll(wifiFieldDefs("WIFI_58G", "5.8G WiFi 信道",
                List.of(opt("auto", "自动"),
                        opt("36", "36"), opt("40", "40"), opt("44", "44"), opt("48", "48"),
                        opt("149", "149"), opt("153", "153"), opt("157", "157"), opt("161", "161")),
                List.of(opt("auto", "自动"), opt("20MHz", "20MHz"),
                        opt("40MHz", "40MHz"), opt("80MHz", "80MHz"))));
        defs.addAll(sipFieldDefs());
        defs.addAll(lanFieldDefs());
        defs.addAll(otherFieldDefs());
        return List.copyOf(defs);
    }

    /** LAN 网口模板字段：勾端口 + 启停/双工/速率（渲染逻辑参考 master lan()） */
    private static List<Map<String, Object>> lanFieldDefs() {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(def("LAN", "lan_ports", "目标网口", "ports", List.of(), null,
                Map.of("tip", "勾选要配置的 LAN 网口")));
        defs.add(def("LAN", "lan_enable", "网口启停", "enum", "true",
                List.of(opt("true", "启用"), opt("false", "禁用")), null));
        defs.add(def("LAN", "lan_duplex", "双工模式", "enum", "Auto",
                List.of(opt("Auto", "自协商"), opt("Full", "全双工"), opt("Half", "半双工")), null));
        defs.add(def("LAN", "lan_speed", "端口速率", "enum", "Auto",
                List.of(opt("Auto", "自协商"), opt("10", "10 Mbps"), opt("100", "100 Mbps"),
                        opt("1000", "1000 Mbps"), opt("2500", "2500 Mbps")), null));
        return defs;
    }

    /** WiFi 模板字段，对照开通界面：SSID / 信道带宽 / 信道 / 隐藏 / WPS / 发射功率 */
    private static List<Map<String, Object>> wifiFieldDefs(String category, String channelLabel,
                                                           List<Map<String, String>> channels,
                                                           List<Map<String, String>> bandwidths) {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(def(category, "radio_enable", "启用无线 SSID", "enum", "true",
                List.of(opt("true", "是"), opt("false", "否")), null));
        defs.add(def(category, "ssid", "SSID 名称", "string", "", null,
                Map.of("tip", "支持变量：%room% 表示房间号（台账未登记时兜底 0000）")));
        defs.add(def(category, "wifi_instance", "SSID 实例号", "number", null, null,
                Map.of("tip", "机型适配用：明确指定该频段对应的 WLANConfiguration 实例号（2.4G 通常 1、5.8G 通常 5）；留空自动识别",
                        "min", 1, "max", 64)));
        defs.add(def(category, "bandwidth", "信道带宽", "enum", "auto", bandwidths, null));
        defs.add(def(category, "channel", channelLabel, "enum", "auto", channels, null));
        defs.add(def(category, "hidden", "隐藏 SSID", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def(category, "wps_disable", "禁用 WPS 按键功能", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def(category, "tx_power", "发射功率", "enum", "auto",
                List.of(opt("auto", "自动"), opt("100", "100%"), opt("75", "75%"),
                        opt("50", "50%"), opt("25", "25%")), null));
        defs.add(def(category, "radio_hardware_enable", "无线模块硬件开关", "enum", "true",
                List.of(opt("true", "是"), opt("false", "否")), null));
        defs.add(def(category, "encryption", "加密方式", "enum", "11i",
                List.of(opt("Basic", "开放（无密码）"), opt("WPA", "WPA-PSK"),
                        opt("11i", "WPA2-PSK"), opt("WPAand11i", "WPA/WPA2 混合")), null));
        defs.add(def(category, "wpa_algorithm", "WPA 加密算法", "enum", "AES+TKIP",
                List.of(opt("AES+TKIP", "AES+TKIP (默认)"), opt("AES", "AES"),
                        opt("TKIP", "TKIP")), null));
        defs.add(def(category, "wpa_key", "WPA 预共享密钥", "string", "", null,
                Map.of("inputType", "password",
                        "tip", "8-63 位，加密方式为开放时无效；支持变量 %wifi_pwd%，未填写时按 88888888 下发")));
        return defs;
    }

    /** SIP 语音模板字段：服务器三件套 + 数图 + 拨号约束 */
    private static List<Map<String, Object>> sipFieldDefs() {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(def("SIP", "protocol", "语音协议", "enum", "SIP",
                List.of(opt("SIP", "SIP"), opt("H248", "H.248")), null));
        defs.add(def("SIP", "registrar", "SIP 注册服务器地址", "string", "", null,
                Map.of("tip", "IP 或域名")));
        defs.add(def("SIP", "registrar_port", "SIP 注册服务器端口", "number", 5060, null,
                Map.of("min", 1, "max", 65535)));
        defs.add(def("SIP", "proxy", "SIP 代理服务器地址", "string", "", null,
                Map.of("tip", "可选，默认和 SIP 服务器相同")));
        defs.add(def("SIP", "proxy_port", "SIP 代理服务器端口", "number", null, null,
                Map.of("min", 1, "max", 65535)));
        defs.add(def("SIP", "outbound_proxy", "Outbound 服务器地址", "string", "", null,
                Map.of("tip", "IP 或域名，可选")));
        defs.add(def("SIP", "outbound_proxy_port", "Outbound 服务器端口", "number", null, null,
                Map.of("min", 1, "max", 65535)));
        defs.add(def("SIP", "backup_registrar", "备用 SIP 注册服务器地址", "string", "", null,
                Map.of("tip", "IP 或域名，可选")));
        defs.add(def("SIP", "backup_registrar_port", "备用 SIP 注册服务器端口", "number", null, null,
                Map.of("min", 1, "max", 65535)));
        defs.add(def("SIP", "min_digits", "最小拨号长度", "number", null, null,
                Map.of("tip", "可选，1-3", "min", 1, "max", 3)));
        defs.add(def("SIP", "user_agent_domain", "用户域名 (UserAgentDomain)", "string", "", null,
                Map.of("tip", "可选，一般为空")));
        defs.add(def("SIP", "digitmap_enable", "数图 (DigitMap) 开关", "enum", "ignore",
                List.of(opt("ignore", "= 忽略 ="), opt("enable", "启用"), opt("disable", "禁用")), null));
        defs.add(def("SIP", "digitmap_rule", "数图 (DigitMap) 规则", "string", "", null,
                Map.of("tip", "可选，如 [1-9]xxxxxxx")));
        defs.add(def("SIP", "disable_special_dial", "禁用特殊号码拨号 (1xx)", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("SIP", "sip_user_info", "SIP 用户信息", "textarea", "", null,
                Map.of("tip", "每条记录占一行，每行格式：SIP账号名 密码；变量：%room% 表示房间号，"
                        + "%desc% 表示光猫备注，将备注设为房间号时可用；"
                        + "例1: 8601 123456；例2: %room% %room% 表示账号和密码均为房间号；"
                        + "例3: %room% 123456 账号为房间号，密码为123456")));
        defs.add(def("SIP", "prefer_existing_account", "优先使用已存在的SIP账号信息", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        return defs;
    }

    /** 「其他」类型：设备管理类意图，多为厂商私有节点，映射后才会产生报文 */
    private static List<Map<String, Object>> otherFieldDefs() {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(def("OTHER", "unlock_limit", "解除上网限制", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "telnet_enable", "开启 Telnet", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "telnet_username", "修改 Telnet 用户名", "string", "", null, null));
        defs.add(def("OTHER", "telnet_password", "修改 Telnet 密码", "string", "", null,
                Map.of("inputType", "password", "tip", "为空不修改")));
        defs.add(def("OTHER", "reboot_after", "下发业务完成后重启光猫", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "wan_access", "开启 WAN 侧访问", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "igmp_support", "IGMP 组播支持", "enum", "ignore",
                List.of(opt("ignore", "= 忽略 ="), opt("enable", "开启"), opt("disable", "关闭")), null));
        defs.add(def("OTHER", "multicast_mode", "组播工作模式", "enum", "auto",
                List.of(opt("auto", "自动 (默认)"), opt("proxy", "代理"), opt("snooping", "侦听")), null));
        defs.add(def("OTHER", "clear_loid", "清除逻辑ID (LOID)", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "super_account", "超管账号", "string", "", null,
                Map.of("tip", "为空不修改")));
        defs.add(def("OTHER", "super_password", "超管密码", "string", "", null,
                Map.of("inputType", "password", "tip", "如 Admin@123，为空表示不修改")));
        defs.add(def("OTHER", "firewall_disable", "关闭防火墙及攻击防御", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "log_disable", "禁用光猫日志功能", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "wifi_isolation_disable", "关闭WiFi共享隔离 (端口&STA)", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "led_control", "光猫面板 LED 灯控制", "enum", "ignore",
                List.of(opt("ignore", "= 忽略 ="), opt("on", "开启"), opt("off", "关闭")), null));
        defs.add(def("OTHER", "wifi_roaming", "WiFi 漫游支持", "enum", "ignore",
                List.of(opt("ignore", "= 忽略 ="), opt("enable", "开启"), opt("disable", "关闭")), null));
        defs.add(def("OTHER", "roaming_threshold_24g", "2.4G 漫游阈值", "number", null, null,
                Map.of("tip", "-60~-75，默认为 -70", "min", -90, "max", -40)));
        defs.add(def("OTHER", "roaming_threshold_58g", "5.8G 漫游阈值", "number", null, null,
                Map.of("tip", "-60~-75，默认为 -70", "min", -90, "max", -40)));
        return defs;
    }

    /** WAN 类共用的意图字段，对照开通界面：业务模式 / VLAN / 绑定端口 / MTU / 组播 */
    private static List<Map<String, Object>> wanFieldDefs(String category) {
        List<Map<String, Object>> defs = new ArrayList<>();
        boolean bridge = "WAN_BRIDGE".equalsIgnoreCase(category);
        defs.add(def(category, "ip_protocol", "IP 协议", "enum", "IPv4",
                List.of(opt("IPv4", "IPv4 (默认)"), opt("IPv6", "IPv6"),
                        opt("IPv4+IPv6", "IPv4 + IPv6")), null));
        if (List.of("WAN_DHCP", "WAN_STATIC").contains(category)) {
            defs.add(def(category, "addr_type", "WAN IP 模式(寻址方式)", "enum", "",
                    List.of(opt("", "按模板类别（DHCP→DHCP / STATIC→Static）"),
                            opt("DHCP", "DHCP / IPoE"), opt("Static", "静态 IP")),
                    Map.of("tip", "留空即按类别：WAN_DHCP 下发 IPAddressingType=DHCP，WAN_STATIC 下发 Static；显式选择可覆盖")));
        }
        defs.add(def(category, "vlan_id", "WAN VLAN ID", "number", null, null,
                Map.of("tip", "如 201，为空表示无", "min", 0, "max", 4094)));
        defs.add(def(category, "vlan_priority", "802.1p QoS 优先级", "number", null, null,
                Map.of("tip", "0-7，为空表示不下发", "min", 0, "max", 7)));
        // 选项值用设备口径（大写）：设备上 X_CMCC_ServiceList 实测只有
        if (bridge) {
            // 桥接的业务模式是「这条桥给谁用」，与路由业务不是一套取值：
            defs.add(def(category, "service_list", "桥接业务模式", "enum", "OTHER",
                    List.of(opt("OTHER", "其他 / Other（默认）"),
                            opt("INTERNET", "上网 / Internet"),
                            opt("IPTV", "电视 / IPTV"),
                            opt("OTT", "OTT")), null));
            // 桥接类型决定建哪个对象：IP→WANIPConnection(IP_Bridged)，
            defs.add(def(category, "bridge_type", "桥接类型", "enum", "IP",
                    List.of(opt("IP", "IP（默认）"), opt("PPPoE", "PPPoE")), null));
        } else {
            defs.add(def(category, "service_list", "业务模式", "enum", "INTERNET",
                    List.of(opt("INTERNET", "Internet 上网"), opt("TR069", "TR069 管理"),
                            opt("VOIP", "VOIP 语音"), opt("IPTV", "IPTV 组播"),
                            opt("INTERNET,TR069", "上网 + 管理"),
                            opt("INTERNET,TR069,VOIP", "上网 + 管理 + 语音"),
                            opt("INTERNET,TR069,VOIP,IPTV", "全业务")), null));
        }
        defs.add(def(category, "bind_ports", "绑定端口", "ports", List.of(), null,
                Map.of("tip", "勾选该 WAN 绑定的端口 / SSID，留空表示不修改")));
        defs.add(def(category, "mtu", "MTU", "number", null, null,
                Map.of("tip", "1280-1500，PPPoE 最大 1492，空表示不修改", "min", 1280, "max", 1500)));
        defs.add(def(category, "multicast_vlan", "组播 VLAN ID", "number", null, null,
                Map.of("tip", "0 或空表示不启用组播上行通道", "min", 0, "max", 4094)));
        return defs;
    }

    private static Map<String, Object> portGroup(String code, String label, String... ports) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("code", code);
        group.put("label", label);
        List<Map<String, String>> list = new ArrayList<>();
        for (String p : ports) {
            Map<String, String> port = new LinkedHashMap<>();
            port.put("v", p);
            // PON 口是从光猫下行来的，页面上要标清楚，不然会被当成普通网口乱勾
            port.put("t", "PON".equals(p) ? "PON（FFTR从光猫下行PON口）" : p);
            list.add(port);
        }
        group.put("ports", list);
        return group;
    }

    private static Map<String, Object> def(String category, String key, String label,
                                           String type, Object defaultValue,
                                           List<Map<String, String>> options,
                                           Map<String, Object> extra) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("category", category);
        item.put("key", key);
        item.put("label", label);
        item.put("type", type);
        item.put("defaultValue", defaultValue);
        item.put("options", options);
        if (extra != null) {
            item.putAll(extra);
        }
        return item;
    }

    private static Map<String, String> opt(String value, String label) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        return item;
    }

    private static Map<String, String> cat(String code, String label) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        return item;
    }

    /** 类型展示名：未知类型原样返回 */
    public static String labelOf(String category) {
        if (category == null) {
            return "";
        }
        for (Map<String, String> c : CATEGORIES) {
            if (Objects.equals(c.get("code"), category)) {
                return c.get("label");
            }
        }
        return category;
    }

    /** 类型标签色：WAN 绿、WiFi 红、语音橙、其它灰 */
    public static String categoryLevelOf(String category) {
        String code = category == null ? "" : category.toUpperCase(Locale.ROOT);
        if (code.startsWith("WAN_")) {
            return "success";
        }
        if (code.startsWith("WIFI_")) {
            return "danger";
        }
        if ("SIP".equals(code)) {
            return "warning";
        }
        return "info";
    }
}
