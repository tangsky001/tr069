package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltActionParam;

import java.util.ArrayList;
import java.util.List;

import static com.tr069.acs.olt.model.OltAction.config;
import static com.tr069.acs.olt.model.OltAction.in;
import static com.tr069.acs.olt.model.OltAction.query;
import static com.tr069.acs.olt.model.OltAction.queryIn;
import static com.tr069.acs.olt.model.OltAction.queryWith;

/**
 * 鸿升光 GPON 一体化 OLT（4/8/16 口）的命令动作目录，对应《GPON 光纤接入局端设备
 * 命令行操作手册 V1.0.0》（157 页，15 章）。
 *
 * <p>与 EPON 手册（{@code HsgqActionCatalog}）的关系：同厂商同 CLI 家族，第 2/3/4/7/10/11/12/13/14 章
 * 命令写法一致，差别在</p>
 * <ul>
 *   <li>PON 口关键字是 {@code gpon} 而不是 {@code epon}，且 PON 口不做二层成员端口——
 *       业务靠 {@code service-port} 虚端口绑定 {@code gemport}；</li>
 *   <li>多了第 5 章模板（DBA / 线路模板 / 业务模板）和第 6 章虚端口；</li>
 *   <li>第 8 章用 SN/LOID 注册 ONT，第 9 章 ONT 命令全在 GPON 口视图下（没有单独的 ONT 视图）；</li>
 *   <li>第 3 章多一节风扇管理，用户权限多一级 {@code operator}。</li>
 * </ul>
 *
 * <p>页码对照：图片 {@code GPON-OL..V1_NN.png} = 书页 NN-10。</p>
 */
public final class HsgqGponActionCatalog {

    // ---------------------------------------------------------------- 章节

    private static final String CH_MGMT_OUT = "2.2 带外管理接口（NMS）";

    private static final String CH_MGMT_IN = "2.3 带内管理接口（上联口）";

    private static final String CH_SYS_NAME = "3.1 设备名称配置";

    private static final String CH_SYS_VERSION = "3.2 查看设备版本信息";

    private static final String CH_SYS_CPU = "3.3 查看 CPU 利用率";

    private static final String CH_SYS_MEM = "3.4 查看内存利用率";

    private static final String CH_SYS_HISTORY = "3.5 查看历史命令";

    private static final String CH_SYS_CONFIG = "3.6 配置管理";

    private static final String CH_CONFIG_FTP = "3.6.4 配置文件导入导出";

    private static final String CH_UPGRADE = "3.7 系统升级";

    private static final String CH_DIAG = "3.8 系统诊断";

    private static final String CH_TIME = "3.9 时间管理";

    private static final String CH_USER = "3.10 用户管理";

    private static final String CH_FAN = "3.11 风扇管理";

    private static final String CH_SERVICE = "3.12 系统服务";

    private static final String CH_ROUTE_DEFAULT = "3.13 缺省路由";

    private static final String CH_DNS = "3.14 DNS 配置";

    private static final String CH_ACCESS = "3.15 设备访问控制";

    private static final String CH_VLAN_ALL = "4.1 全局 VLAN 配置";

    private static final String CH_VLAN_PORT = "4.2 端口 VLAN 配置";

    private static final String CH_DBA = "5.1 DBA 模板";

    private static final String CH_LINE = "5.2 线路模板";

    private static final String CH_SRV = "5.3 业务模板";

    private static final String CH_SP_AUTOFIND = "6.1 虚端口自动发现列表";

    private static final String CH_SP = "6.2 虚端口配置";

    private static final String CH_SP_PROFILE = "6.3 虚端口自动配置模板";

    private static final String CH_PORT_STATE = "7.1 端口状态管理";

    private static final String CH_PORT_DESC = "7.2 端口描述信息配置";

    private static final String CH_PORT_DUPLEX = "7.3 端口双工模式配置";

    private static final String CH_PORT_SPEED = "7.4 端口速率配置";

    private static final String CH_PORT_MTU = "7.5 端口 MTU 配置";

    private static final String CH_PORT_STORM = "7.6 广播/组播/未知单播风暴抑制";

    private static final String CH_PORT_RATE = "7.7 端口限速及流量控制";

    private static final String CH_PORT_STAT = "7.8 流量统计";

    private static final String CH_PORT_MIRROR = "7.9 端口镜像";

    private static final String CH_LOOP = "7.10 上联口环路检测";

    private static final String CH_PORT_GROUP = "7.11 端口组";

    private static final String CH_PON_LASER = "8.1 PON 口发光配置";

    private static final String CH_PON_AUTOFIND = "8.2 ONT 自动发现";

    private static final String CH_PON_AUTH = "8.3 ONT 注册";

    private static final String CH_PON_INFO = "8.4-8.7 PON 信息查询";

    private static final String CH_PON_MODULE = "8.8 光模块类型配置";

    private static final String CH_PON_BLACK = "8.9 ONT 黑名单";

    private static final String CH_ONT = "9.1-9.6 ONT 管理";

    private static final String CH_ONT_PORT = "9.7 ONT 端口管理";

    private static final String CH_ONT_IP = "9.8 ONT IPhost 配置";

    private static final String CH_MAC = "10 MAC 地址管理";

    private static final String CH_STP = "11.1 RSTP";

    private static final String CH_MSTP = "11.2 MSTP";

    private static final String CH_IGMP = "11.3.1 IGMP 全局配置";

    private static final String CH_MCAST = "11.3.2 组播 VLAN 配置";

    private static final String CH_IGMP_ENTRY = "11.3.3 组播表项";

    private static final String CH_DHCP = "11.4 DHCP 配置";

    private static final String CH_TIMERANGE = "12.1 Time-range";

    private static final String CH_ACL = "12.2 ACL 管理";

    private static final String CH_FILTER = "12.3 包过滤（Packet Filter）";

    private static final String CH_QOS = "12.4 QOS";

    private static final String CH_ROUTE = "13.1 路由管理";

    private static final String CH_ARP = "13.2 ARP 表";

    private static final String CH_LAG = "14 链路聚合";

    // ---------------------------------------------------------------- 参数

    /** GPON 的端口类型：gpon / ge / xge */
    private static OltActionParam portType() {
        return OltActionParam.choice("portType", "端口类型", List.of("gpon", "ge", "xge"), "gpon",
                "PON 口选 gpon，上联电口 ge，万兆口 xge");
    }

    /** 只给上联口的参数（VLAN 成员、镜像目的口、MSTP 端口这些不认 gpon） */
    private static OltActionParam uplinkType() {
        return OltActionParam.choice("portType", "端口类型", List.of("ge", "xge"), "ge",
                "上联电口 ge，万兆口 xge");
    }

    private static OltActionParam ponId() {
        return OltActionParam.number("ponId", "PON 口号", 1, 16, "4/8/16 口机型分别为 1-4 / 1-8 / 1-16");
    }

    private static OltActionParam ontId() {
        return OltActionParam.number("ontId", "ONT ID", 0, 127, "0-127");
    }

    private static OltActionParam profileId() {
        return OltActionParam.number("profileId", "模板 ID", 0, 2047, "0-2047");
    }

    private static OltActionParam state() {
        return OltActionParam.choice("state", "状态", List.of("enable", "disable"), "enable", null);
    }

    private HsgqGponActionCatalog() {
    }

    public static List<OltAction> actions() {
        List<OltAction> actions = new ArrayList<>();
        actions.addAll(system());
        actions.addAll(vlan());
        actions.addAll(templates());
        actions.addAll(servicePort());
        actions.addAll(port());
        actions.addAll(pon());
        actions.addAll(ont());
        actions.addAll(mac());
        actions.addAll(protocol());
        actions.addAll(aclQos());
        actions.addAll(routeAgg());
        return actions;
    }

    /** —— 第 2/3 章：管理与系统（手册 p3-33） —— */
    private static List<OltAction> system() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam ip = OltActionParam.text("ip", "IP/掩码", "CIDR 写法，如 192.168.103.1/24");

        // 2.2 带外管理（系统管理视图）
        list.add(in(OltAction.View.MANAGE, "gpon.mgmt.ip", CH_MGMT_OUT, "配置带外管理口 IPv4 地址",
                "默认 IP 192.168.100.1，必须用 /掩码 写法", List.of(ip), "ifconfig {ip}"));
        list.add(queryIn(OltAction.View.MANAGE, "gpon.mgmt.show", CH_MGMT_OUT, "查看带外管理口信息",
                "含 IP、MAC、收发报文统计", List.of(), "ifconfig"));
        list.add(in(OltAction.View.MANAGE, "gpon.mgmt.ip6", CH_MGMT_OUT, "配置带外管理口 IPv6 地址",
                "如 1000::1/64", List.of(OltActionParam.text("ipv6", "IPv6/前缀", "如 1000::1/64")),
                "ifconfig ipv6 {ipv6}"));
        list.add(in(OltAction.View.MANAGE, "gpon.mgmt.ip6.del", CH_MGMT_OUT, "删除带外管理口 IPv6 地址",
                null, List.of(OltActionParam.text("ipv6", "IPv6/前缀", null)), "no ipv6-address {ipv6}"));
        list.add(in(OltAction.View.MANAGE, "gpon.mgmt.telnet", CH_MGMT_OUT, "开启/关闭带外管理口 telnet",
                null, List.of(state()), "telnet {state}"));

        // 2.3 带内管理（三层接口视图）
        list.add(in(OltAction.View.VLANIF, "gpon.inband.ip", CH_MGMT_IN, "配置带内管理口 IPv4 地址",
                "先建 VLAN 与 VLANIF，再配地址",
                List.of(OltActionParam.number("vlanId", "VLAN ID", 1, 4094, "先 vlan standard 建好"),
                        OltActionParam.text("ip", "IP 地址", "如 192.168.10.1"),
                        OltActionParam.text("mask", "子网掩码", "如 255.255.255.0")),
                "ifconfig {ip} netmask {mask}"));
        list.add(in(OltAction.View.VLANIF, "gpon.inband.ip6", CH_MGMT_IN, "配置带内管理口 IPv6 地址", null,
                List.of(OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null),
                        OltActionParam.text("ipv6", "IPv6/前缀", "如 3000::1/64")),
                "ifconfig ipv6 {ipv6}"));
        list.add(in(OltAction.View.VLANIF, "gpon.inband.ip6.del", CH_MGMT_IN,
                "删除带内管理口 IPv6 地址", null,
                List.of(OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null),
                        OltActionParam.text("ipv6", "IPv6/前缀", null)), "no ipv6-address {ipv6}"));
        list.add(in(OltAction.View.VLANIF, "gpon.inband.telnet", CH_MGMT_IN,
                "开启/关闭带内管理口 telnet", "默认开启",
                List.of(OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null), state()),
                "telnet {state}"));
        list.add(config("gpon.inband.vlanif.del", CH_MGMT_IN, "删除带内管理接口 vlanif", "接口上带内地址一并删除",
                List.of(OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null)),
                "no interface vlanif {vlanId}").dangerous());

        // 3.1 ~ 3.5
        list.add(config("gpon.hostname", CH_SYS_NAME, "配置设备名称",
                "提示符会变成 OLT(config)# 之外的主机名标识",
                List.of(OltActionParam.text("name", "设备名称", "如 OLT-1F")), "hostname {name}"));
        list.add(config("gpon.hostname.reset", CH_SYS_NAME, "恢复默认设备名称", null, List.of(),
                "no hostname"));
        list.add(query("gpon.version", CH_SYS_VERSION, "查看设备版本信息",
                "Product / Software Version / SerialNumber / Mac", "show version"));
        list.add(query("gpon.cpu", CH_SYS_CPU, "查看 CPU 利用率", null, "show cpu-usage"));
        list.add(query("gpon.memory", CH_SYS_MEM, "查看内存利用率", null, "show memory statistics"));
        list.add(query("gpon.history", CH_SYS_HISTORY, "查看历史命令", null, "show history"));

        // 3.6 配置管理
        list.add(config("gpon.save", CH_SYS_CONFIG, "保存当前配置", "掉电前必须保存，否则重启丢失",
                List.of(), "save"));
        list.add(query("gpon.running", CH_SYS_CONFIG, "查看当前生效配置", null, "show running-config"));
        list.add(query("gpon.saved", CH_SYS_CONFIG, "查看已保存配置", null, "show save-config"));
        list.add(config("gpon.erase", CH_SYS_CONFIG, "擦除保存的配置文件",
                "擦除后重启恢复出厂配置", List.of(), "erase config-file").dangerous());
        list.add(config("gpon.reboot", CH_SYS_CONFIG, "重启设备", "业务会中断，务必先保存",
                List.of(), "reboot").dangerous());
        list.add(in(OltAction.View.MANAGE, "gpon.config.push", CH_CONFIG_FTP, "导出配置到 FTP 服务器",
                "填 FTP 服务器 IP/掩码，文件放根目录",
                List.of(OltActionParam.text("server", "FTP IP/掩码", "如 192.168.100.123/24")),
                "config push {server}"));
        list.add(in(OltAction.View.MANAGE, "gpon.config.pull", CH_CONFIG_FTP, "从 FTP 服务器导入配置",
                "导入后建议重启", List.of(OltActionParam.text("server", "FTP IP/掩码", null)),
                "config pull {server}"));

        // 3.7 系统升级（系统管理视图）
        list.add(in(OltAction.View.MANAGE, "gpon.update.system.ftp", CH_UPGRADE, "FTP 升级系统文件", null,
                List.of(OltActionParam.text("ip", "FTP 服务器 IP", null),
                        OltActionParam.text("user", "用户名", null),
                        OltActionParam.text("password", "密码", null),
                        OltActionParam.text("file", "系统文件名", "如 system.bin")),
                "update system ftp {ip} {user} {password} {file}"));
        list.add(in(OltAction.View.MANAGE, "gpon.update.firmware.ftp", CH_UPGRADE, "FTP 升级固件", null,
                List.of(OltActionParam.text("ip", "FTP 服务器 IP", null),
                        OltActionParam.text("user", "用户名", null),
                        OltActionParam.text("password", "密码", null),
                        OltActionParam.text("file", "固件名", "如 firmware.bin")),
                "update firmware ftp {ip} {user} {password} {file}"));
        list.add(in(OltAction.View.MANAGE, "gpon.update.system.tftp", CH_UPGRADE, "TFTP 升级系统文件",
                null, List.of(OltActionParam.text("ip", "TFTP 服务器 IP", null),
                        OltActionParam.text("file", "系统文件名", null)),
                "update system tftp {ip} {file}"));
        list.add(in(OltAction.View.MANAGE, "gpon.update.firmware.tftp", CH_UPGRADE, "TFTP 升级固件",
                null, List.of(OltActionParam.text("ip", "TFTP 服务器 IP", null),
                        OltActionParam.text("file", "固件名", null)),
                "update firmware tftp {ip} {file}"));

        // 3.8 诊断
        list.add(queryWith("gpon.ping", CH_DIAG, "Ping 诊断", "连发 4 个包",
                List.of(OltActionParam.text("target", "目标 IP/域名", "如 192.168.10.175")),
                "ping {target}"));

        // 3.9 时间
        list.add(config("gpon.timezone", CH_TIME, "配置时区",
                "偏移取值 -12 ~ +13，分钟为 00-59；也可用城市名",
                List.of(OltActionParam.choice("direction", "方向", List.of("add", "minus"), "add",
                                "add 东时区，minus 西时区"),
                        OltActionParam.number("zone", "小时", -12, 13, null),
                        OltActionParam.number("offset", "分钟", 0, 59, null)),
                "timezone {direction} {zone} {offset}"));
        list.add(query("gpon.timezone.show", CH_TIME, "查看时区", null, "show timezone"));
        list.add(config("gpon.time", CH_TIME, "配置系统时间",
                "日期 YYYY-MM-DD，时间 HH:MM:SS",
                List.of(OltActionParam.text("date", "日期", "如 2019-04-29"),
                        OltActionParam.text("time", "时间", "如 15:30:00")),
                "time date {date} time {time}"));
        list.add(query("gpon.time.show", CH_TIME, "查看系统时间", null, "show time"));
        list.add(config("gpon.ntp", CH_TIME, "开启 NTP 并配置服务器",
                "主服务器必填，备服务器可选",
                List.of(OltActionParam.text("serverIp", "主服务器", "如 10.1.1.1"),
                        OltActionParam.optionalText("backupIp", "备服务器", "可选")),
                "ntp enable ntp-server-ip {serverIp}[ backup-server-ip {backupIp}]"));
        list.add(config("gpon.ntp.disable", CH_TIME, "关闭 NTP", null, List.of(), "ntp disable"));
        list.add(query("gpon.ntp.show", CH_TIME, "查看 NTP 同步状态", null, "show ntp-status"));

        // 3.10 用户
        list.add(query("gpon.user.show", CH_USER, "查看用户列表", null, "show user"));
        list.add(config("gpon.user.add", CH_USER, "增加用户",
                "admin 最高权限，operator 次之，common 只读；reenter 为同时登录数，append 为描述",
                List.of(OltActionParam.text("userName", "用户名", "如 test"),
                        OltActionParam.text("password", "密码", null),
                        OltActionParam.choice("level", "权限", List.of("admin", "common", "operator"),
                                "common", null),
                        OltActionParam.optionalNumber("reenter", "同时登录数", 1, 16, "可选"),
                        OltActionParam.optionalText("info", "描述", "可选")),
                "user add {userName} {password} {level}[ reenter {reenter}][ append {info}]"));
        list.add(config("gpon.user.delete", CH_USER, "删除用户", "用户名 a-z 开头，长度 5-20",
                List.of(OltActionParam.text("userName", "用户名", null)), "user delete {userName}"));
        list.add(config("gpon.user.offline", CH_USER, "强制用户下线", null,
                List.of(OltActionParam.text("userName", "用户名", null)), "user offline {userName}"));
        list.add(config("gpon.user.save", CH_USER, "保存用户配置", null, List.of(), "user save"));
        list.add(config("gpon.user.password", CH_USER, "修改用户密码", null,
                List.of(OltActionParam.text("userName", "用户名", null),
                        OltActionParam.text("password", "新密码", null)),
                "user password {userName} {password}"));
        list.add(config("gpon.user.default", CH_USER, "恢复该用户默认密码",
                "默认密码是账号本身，注意安全", List.of(OltActionParam.text("userName", "用户名", null)),
                "user default {userName}").dangerous());

        // 3.11 风扇
        list.add(config("gpon.fan.mode", CH_FAN, "配置风扇转速模式",
                "auto 自动 / fullspeed 全速(固定 255) / manual 手动；auto、manual 可指定风扇号 1-3",
                List.of(OltActionParam.choice("mode", "模式",
                                List.of("auto", "fullspeed", "manual"), "auto", null),
                        OltActionParam.optionalNumber("fanIndex", "风扇号", 1, 3, "可选，不填为全部")),
                "fan mode {mode}[ {fanIndex}]"));
        list.add(config("gpon.fan.speed.all", CH_FAN, "配置所有风扇转速", "0-255，manual 模式生效",
                List.of(OltActionParam.number("speed", "转速", 0, 255, null)),
                "fan speed all {speed}"));
        list.add(config("gpon.fan.speed", CH_FAN, "配置指定风扇转速", "0-255，manual 模式生效",
                List.of(OltActionParam.number("fanIndex", "风扇号", 1, 3, null),
                        OltActionParam.number("speed", "转速", 0, 255, null)),
                "fan speed {fanIndex} {speed}"));
        list.add(config("gpon.fan.auto", CH_FAN, "配置自动调速的转速与温度拐点",
                "转速 0-255，温度 0-100",
                List.of(OltActionParam.number("speed1", "转速拐点 1", 0, 255, null),
                        OltActionParam.number("speed2", "转速拐点 2", 0, 255, null),
                        OltActionParam.number("temp1", "温度拐点 1", 0, 100, null),
                        OltActionParam.number("temp2", "温度拐点 2", 0, 100, null),
                        OltActionParam.number("fanIndex", "风扇号", 1, 3, null)),
                "fan auto-point {speed1} {speed2} {temp1} {temp2} {fanIndex}"));
        list.add(query("gpon.fan.show", CH_FAN, "查看风扇信息", "转速 / 温度 / 状态", "show fan info"));

        // 3.12 系统服务
        list.add(query("gpon.service.port", CH_SERVICE, "查看服务端口", "WEB / Telnet / SSH 当前端口",
                "show service-user port"));
        list.add(config("gpon.web.port", CH_SERVICE, "配置 WEB 端口", "端口 1-65535",
                List.of(OltActionParam.choice("protocol", "协议", List.of("http", "https"), "http", null),
                        OltActionParam.number("port", "端口号", 1, 65535, "如 8080")),
                "web-port {protocol} {port}"));
        list.add(config("gpon.telnet.port", CH_SERVICE, "配置 Telnet 端口", null,
                List.of(OltActionParam.number("port", "端口号", 1, 65535, "如 2323")),
                "telnet port-modify {port}"));
        list.add(config("gpon.telnet.switch", CH_SERVICE, "开启/关闭 Telnet", null, List.of(state()),
                "telnet {state}"));
        list.add(config("gpon.ssh.enable", CH_SERVICE, "开启/关闭 SSH", null, List.of(state()),
                "ssh-server {state}"));
        list.add(config("gpon.ssh.session", CH_SERVICE, "配置 SSH 最大会话数", "1-5 的整数",
                List.of(OltActionParam.number("session", "最大会话数", 1, 5, null)),
                "ssh-server session {session}"));
        list.add(config("gpon.ssh.port", CH_SERVICE, "配置 SSH 端口", "1-65535",
                List.of(OltActionParam.number("port", "端口号", 1, 65535, "如 22")),
                "ssh-server port {port}"));
        list.add(config("gpon.ssh.key", CH_SERVICE, "配置 SSH 密钥模数", "768-2048，默认 1024",
                List.of(OltActionParam.number("bits", "密钥模数", 768, 2048, "如 1024")),
                "ssh-server key-modulus {bits}"));
        list.add(config("gpon.snmp.read", CH_SERVICE, "配置 SNMP 读团体名", null,
                List.of(OltActionParam.text("community", "读团体名", "如 public")),
                "snmp community-cfg read {community}"));
        list.add(config("gpon.snmp.write", CH_SERVICE, "配置 SNMP 写团体名", null,
                List.of(OltActionParam.text("community", "写团体名", "如 private")),
                "snmp community-cfg write {community}"));
        list.add(config("gpon.snmp.trap", CH_SERVICE, "配置 SNMP Trap 目的地址",
                "先开启 SNMP Trap", List.of(OltActionParam.text("ip", "Trap 服务器 IP", null)),
                "snmp trap-cfg serverip {ip}"));
        list.add(query("gpon.snmp.show", CH_SERVICE, "查看 SNMP Trap 配置", null, "show snmp-config"));
        list.add(query("gpon.snmp.community", CH_SERVICE, "查看 SNMP 团体名", null,
                "show snmp community-cfg"));

        // 3.13 缺省路由
        list.add(config("gpon.route.default", CH_ROUTE_DEFAULT, "配置 IPv4 缺省路由", "先开 ip route enable",
                List.of(OltActionParam.text("gateway", "网关", "如 192.168.10.1")),
                "route default gw {gateway}"));
        list.add(config("gpon.route.default.del", CH_ROUTE_DEFAULT, "删除 IPv4 缺省路由", null, List.of(),
                "no route default"));
        list.add(config("gpon.route.default6", CH_ROUTE_DEFAULT, "配置 IPv6 缺省路由", null,
                List.of(OltActionParam.text("gateway", "IPv6 网关", "如 1000::1")),
                "ipv6 route default gw {gateway}"));
        list.add(config("gpon.route.default6.del", CH_ROUTE_DEFAULT, "删除 IPv6 缺省路由", null, List.of(),
                "no ipv6 route default"));
        list.add(query("gpon.route.default.show", CH_ROUTE_DEFAULT, "查看缺省路由", null,
                "show route default"));

        // 3.14 DNS
        list.add(config("gpon.dns", CH_DNS, "配置 DNS 服务器",
                "首选必填，备用可选",
                List.of(OltActionParam.text("master", "首选 DNS", "如 114.114.114.114"),
                        OltActionParam.optionalText("backup", "备用 DNS", "可选")),
                "dns primary {master}[ secondary {backup}]"));
        list.add(config("gpon.dns.del", CH_DNS, "删除 DNS 配置", null, List.of(), "no dns"));
        list.add(config("gpon.dns6", CH_DNS, "配置 IPv6 DNS 服务器", null,
                List.of(OltActionParam.text("master", "首选 IPv6 DNS", "如 2001:4860:4860::8888"),
                        OltActionParam.optionalText("backup", "备用 IPv6 DNS", "可选")),
                "ipv6 dns primary {master}[ secondary {backup}]"));
        list.add(config("gpon.dns6.del", CH_DNS, "删除 IPv6 DNS 配置", null, List.of(), "no ipv6 dns"));
        list.add(query("gpon.dns.show", CH_DNS, "查看 DNS 配置", null, "show dns"));

        // 3.15 访问控制
        list.add(config("gpon.access.add", CH_ACCESS, "增加设备访问控制规则",
                "限制哪些 IP 能以什么方式登录设备",
                List.of(OltActionParam.text("name", "规则名", "如 office"),
                        OltActionParam.text("mac", "MAC 地址", "如 00:11:22:33:44:55"),
                        OltActionParam.choice("scope", "访问方式",
                                List.of("all", "ssh", "telnet", "web"), "all", null),
                        OltActionParam.choice("family", "协议族", List.of("ipv4", "ipv6"), "ipv4", null),
                        OltActionParam.text("ip", "IP/前缀", "如 192.168.1.0/24")),
                "access-control add {name} mac {mac} access {scope} {family} {ip}"));
        list.add(query("gpon.access.show", CH_ACCESS, "查看访问控制规则", null, "show access-control"));
        list.add(config("gpon.access.del", CH_ACCESS, "删除访问控制规则", null,
                List.of(OltActionParam.text("name", "规则名", null)), "no access-control {name}"));
        return list;
    }

    /** —— 第 4 章 VLAN（手册 p34-37） —— */
    private static List<OltAction> vlan() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, "取值 1-4094");
        OltActionParam start = OltActionParam.number("startVlanId", "起始 VLAN", 1, 4094, null);
        OltActionParam end = OltActionParam.number("endVlanId", "结束 VLAN", 1, 4094, null);
        OltActionParam portList = OltActionParam.ports("ports", "端口列表", "如 1,3,4-8");

        list.add(config("gpon.vlan.create", CH_VLAN_ALL, "创建单个 VLAN",
                "进入 VLAN 视图（提示符变 OLT(config-vlan-100)#）",
                List.of(vlanId), "vlan standard {vlanId}"));
        list.add(config("gpon.vlan.create.batch", CH_VLAN_ALL, "批量创建连续 VLAN", null,
                List.of(start, end), "vlan standard {startVlanId} {endVlanId}"));
        list.add(config("gpon.vlan.delete", CH_VLAN_ALL, "删除单个 VLAN", null, List.of(vlanId),
                "no vlan {vlanId}"));
        list.add(config("gpon.vlan.delete.batch", CH_VLAN_ALL, "批量删除连续 VLAN", null,
                List.of(start, end), "no vlan {startVlanId} {endVlanId}"));
        list.add(config("gpon.vlan.delete.all", CH_VLAN_ALL, "删除所有 VLAN",
                "所有 VLAN 与成员端口配置一起清空", List.of(), "no vlan all").dangerous());
        list.add(queryIn(OltAction.View.VLAN, "gpon.vlan.show", CH_VLAN_ALL, "查看本 VLAN 属性",
                "只在 VLAN 视图里可用，看单个 VLAN", List.of(vlanId), "show vlan"));
        list.add(in(OltAction.View.VLAN, "gpon.vlan.port.tagged", CH_VLAN_ALL, "VLAN 加 Tagged 成员端口",
                "只能加上联口（ge/xge），PON 口走业务模板与虚端口",
                List.of(vlanId, uplinkType(), portList), "port {portType} {ports} tagged"));
        list.add(in(OltAction.View.VLAN, "gpon.vlan.port.untagged", CH_VLAN_ALL,
                "VLAN 加 Untagged 成员端口", null,
                List.of(vlanId, uplinkType(), portList), "port {portType} {ports} untagged"));
        list.add(in(OltAction.View.VLAN, "gpon.vlan.port.default", CH_VLAN_ALL,
                "VLAN 加 Default VLAN 成员端口", null,
                List.of(vlanId, uplinkType(), portList), "port {portType} {ports} default-vlan"));
        list.add(in(OltAction.View.VLAN, "gpon.vlan.port.del", CH_VLAN_ALL,
                "从 VLAN 删除成员端口", null,
                List.of(vlanId, uplinkType(), portList), "no port {portType} {ports}"));

        // 4.2 端口 VLAN（端口视图）
        OltActionParam pType = uplinkType();
        OltActionParam portNo = OltActionParam.portNo("端口号");
        list.add(in(OltAction.View.PORT, "gpon.port.vlan.mode", CH_VLAN_PORT, "配置端口 VLAN 模式",
                "access 只属于一个 VLAN；trunk 可多 VLAN；hybrid 可 tagged/untagged 混合",
                List.of(pType, portNo,
                        OltActionParam.choice("mode", "模式",
                                List.of("access", "hybrid", "trunk"), "access", null)),
                "vlan mode {mode}"));
        list.add(in(OltAction.View.PORT, "gpon.port.vlan.access", CH_VLAN_PORT,
                "配置 Access 模式 VLAN", "先把端口模式设为 access",
                List.of(pType, portNo, vlanId), "vlan access {vlanId}"));
        list.add(in(OltAction.View.PORT, "gpon.port.vlan.trunk", CH_VLAN_PORT,
                "配置 Trunk 模式允许的 VLAN", "VLAN 列表写法 100-200,300",
                List.of(pType, portNo, OltActionParam.ports("vlanList", "VLAN 列表", "如 100-200,300")),
                "vlan trunk {vlanList}"));
        list.add(in(OltAction.View.PORT, "gpon.port.vlan.hybrid", CH_VLAN_PORT,
                "配置 Hybrid 模式 VLAN", null,
                List.of(pType, portNo, OltActionParam.ports("vlanList", "VLAN 列表", "如 100,200"),
                        OltActionParam.choice("tag", "标签方式", List.of("tagged", "untagged"),
                                "untagged", null)),
                "vlan hybrid {vlanList} {tag}"));
        list.add(in(OltAction.View.PORT, "gpon.port.vlan.trunk.del", CH_VLAN_PORT,
                "删除 Trunk 模式 VLAN", null,
                List.of(pType, portNo, OltActionParam.ports("vlanList", "VLAN 列表", null)),
                "no vlan trunk {vlanList}"));
        list.add(in(OltAction.View.PORT, "gpon.port.vlan.hybrid.del", CH_VLAN_PORT,
                "删除 Hybrid 模式 VLAN", null,
                List.of(pType, portNo, OltActionParam.ports("vlanList", "VLAN 列表", null)),
                "no vlan hybrid {vlanList}"));
        list.add(in(OltAction.View.PORT, "gpon.port.defaultvlan", CH_VLAN_PORT, "配置端口缺省 VLAN",
                "打上该端口的 untagged 报文归到缺省 VLAN",
                List.of(pType, portNo, vlanId), "port default-vlan {vlanId}"));
        list.add(queryIn(OltAction.View.PORT, "gpon.port.vlan.show", CH_VLAN_PORT, "查看端口 VLAN 配置",
                null, List.of(pType, portNo), "show vlan"));
        return list;
    }

    /** —— 第 5 章 模板管理（手册 p38-53）：DBA / 线路模板 / 业务模板 —— */
    private static List<OltAction> templates() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam profileId = OltActionParam.number("profileId", "DBA 模板 ID", 10, 255,
                "10-255 为全局模板，0-9 为系统模板（只读）");
        OltActionParam profileName = OltActionParam.text("profileName", "模板名", "如 dba-100M");
        OltActionParam fix = OltActionParam.number("fix", "固定带宽(kbps)", 128, 10000000, null);
        OltActionParam assure = OltActionParam.number("assure", "保证带宽(kbps)", 128, 10000000, null);
        OltActionParam max = OltActionParam.number("max", "最大带宽(kbps)", 128, 10000000, null);

        // 5.1 DBA 模板
        List<OltActionParam> dbaBase = List.of(profileId, profileName);
        list.add(config("gpon.dba.add.type1", CH_DBA, "增加 DBA 模板（Type1 固定带宽）",
                "模板 ID 10-255，带宽 128-10000000 kbps",
                concat(dbaBase, fix), "dba-profile add profile-id {profileId} profile-name {profileName}"
                        + " type1 fix {fix}"));
        list.add(config("gpon.dba.add.type2", CH_DBA, "增加 DBA 模板（Type2 保证带宽）", null,
                concat(dbaBase, assure), "dba-profile add profile-id {profileId} profile-name {profileName}"
                        + " type2 assure {assure}"));
        list.add(config("gpon.dba.add.type3", CH_DBA, "增加 DBA 模板（Type3 保证+最大带宽）", null,
                concat(dbaBase, assure, max), "dba-profile add profile-id {profileId}"
                        + " profile-name {profileName} type3 assure {assure} max {max}"));
        list.add(config("gpon.dba.add.type4", CH_DBA, "增加 DBA 模板（Type4 最大带宽）", null,
                concat(dbaBase, max), "dba-profile add profile-id {profileId} profile-name {profileName}"
                        + " type4 max {max}"));
        list.add(config("gpon.dba.add.type5", CH_DBA, "增加 DBA 模板（Type5 固定+保证+最大）", null,
                concat(List.of(profileId, profileName, fix, assure, max)),
                "dba-profile add profile-id {profileId} profile-name {profileName}"
                        + " type5 fix {fix} assure {assure} max {max}"));
        list.add(config("gpon.dba.mod.type1", CH_DBA, "修改 DBA 模板（Type1）", null,
                concat(dbaBase, fix), "dba-profile modify profile-id {profileId} profile-name {profileName}"
                        + " type1 fix {fix}"));
        list.add(config("gpon.dba.mod.type2", CH_DBA, "修改 DBA 模板（Type2）", null,
                concat(dbaBase, assure), "dba-profile modify profile-id {profileId}"
                        + " profile-name {profileName} type2 assure {assure}"));
        list.add(config("gpon.dba.mod.type3", CH_DBA, "修改 DBA 模板（Type3）", null,
                concat(dbaBase, assure, max), "dba-profile modify profile-id {profileId}"
                        + " profile-name {profileName} type3 assure {assure} max {max}"));
        list.add(config("gpon.dba.mod.type4", CH_DBA, "修改 DBA 模板（Type4）", null,
                concat(dbaBase, max), "dba-profile modify profile-id {profileId}"
                        + " profile-name {profileName} type4 max {max}"));
        list.add(config("gpon.dba.mod.type5", CH_DBA, "修改 DBA 模板（Type5）", null,
                concat(List.of(profileId, profileName, fix, assure, max)),
                "dba-profile modify profile-id {profileId} profile-name {profileName}"
                        + " type5 fix {fix} assure {assure} max {max}"));
        list.add(query("gpon.dba.show", CH_DBA, "查看所有 DBA 模板",
                "ID 10-255 为全局模板，0-9 为系统模板", "show dba-profile all"));
        list.add(queryWith("gpon.dba.bound", CH_DBA, "查看 DBA 模板绑定信息",
                "看这个模板被哪些 T-CONT 引用",
                List.of(OltActionParam.number("profileId", "模板 ID", 0, 255, null)),
                "show dba-profile bound-info profile-id {profileId}"));
        list.add(config("gpon.dba.delete", CH_DBA, "删除 DBA 模板", "被引用的模板删不掉",
                List.of(OltActionParam.number("profileId", "模板 ID", 0, 255, null)),
                "dba-profile delete profile-id {profileId}"));
        list.add(config("gpon.dba.delete.all", CH_DBA, "删除所有 DBA 模板", null, List.of(),
                "dba-profile delete profile-id all").dangerous());

        // 5.2 线路模板
        OltActionParam lineId = OltActionParam.number("profileId", "线路模板 ID", 0, 2047, null);
        OltActionParam tcontId = OltActionParam.number("tcontId", "T-CONT ID", 1, 4, "1-4");
        OltActionParam gemId = OltActionParam.number("gemId", "GEM ID", 1, 32, "1-32");
        OltActionParam mappingId = OltActionParam.number("mappingId", "Mapping ID", 0, 7, "0-7");
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null);
        OltActionParam priority = OltActionParam.number("priority", "优先级", 0, 7, "0-7");

        list.add(config("gpon.line.create", CH_LINE, "创建线路模板",
                "创建时提示符会切进模板视图，这里配完自动 exit；T-CONT / GEM 用下面的动作单独配",
                List.of(lineId), "ont-lineprofile gpon profile-id {profileId}", "exit"));
        list.add(query("gpon.line.show", CH_LINE, "查看所有线路模板", null, "show ont-lineprofile all"));
        list.add(queryWith("gpon.line.show.one", CH_LINE, "查看指定线路模板", null,
                List.of(OltActionParam.number("profileId", "线路模板 ID", 0, 2047, null)),
                "show ont-lineprofile profile-id {profileId}"));
        list.add(queryWith("gpon.line.bound", CH_LINE, "查看线路模板绑定信息", null,
                List.of(OltActionParam.number("profileId", "线路模板 ID", 0, 2047, null)),
                "show ont-lineprofile boundinfo profile-id {profileId}"));
        list.add(config("gpon.line.delete", CH_LINE, "删除线路模板", "已被 ONT 使用的模板删不掉",
                List.of(OltActionParam.number("profileId", "线路模板 ID", 0, 2047, null)),
                "no ont-lineprofile gpon profile-id {profileId}").dangerous());

        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.tcont", CH_LINE, "配置 T-CONT",
                "T-CONT 1-4，绑定 DBA 模板（ID 或名字二选一）",
                List.of(lineId, tcontId,
                        OltActionParam.optionalNumber("dbaProfileId", "DBA 模板 ID", 10, 255, "可选"),
                        OltActionParam.optionalText("dbaProfileName", "DBA 模板名", "可选")),
                "tcont {tcontId}[ dba-profile-id {dbaProfileId}][ dba-profile-name {dbaProfileName}]"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.tcont.del", CH_LINE, "删除 T-CONT", null,
                List.of(lineId, tcontId), "no tcont {tcontId}"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.mapping", CH_LINE, "配置映射模式",
                "vlan：按 VLAN 映射；port：按端口映射", List.of(lineId,
                        OltActionParam.choice("mode", "映射模式", List.of("vlan", "port"), "vlan", null)),
                "mapping-mode {mode}"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.gem.add", CH_LINE, "增加 GEM Port", null,
                List.of(lineId, gemId, tcontId), "gem add {gemId} tcont {tcontId}"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.gem.mod", CH_LINE, "修改 GEM Port 绑定的 T-CONT",
                null, List.of(lineId, gemId, tcontId), "gem modify {gemId} tcont {tcontId}"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.gem.del", CH_LINE, "删除 GEM Port", null,
                List.of(lineId, gemId), "gem delete {gemId}"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.gem.map.vlan", CH_LINE,
                "配置 GEM Port 的 VLAN 映射", "GEM 与 VLAN 一一对应，可带优先级",
                List.of(lineId, gemId, mappingId, vlanId, priority),
                "gem mapping {gemId} {mappingId} vlan {vlanId} priority {priority}"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.gem.map.untag", CH_LINE,
                "配置 GEM Port 映射 untag 报文", "不带 VLAN 的报文走这个 GEM", List.of(lineId, gemId, mappingId),
                "gem mapping {gemId} {mappingId} vlan untag"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.gem.map.priority", CH_LINE,
                "配置 GEM Port 只映射优先级", null, List.of(lineId, gemId, mappingId, priority),
                "gem mapping {gemId} {mappingId} priority {priority}"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.gem.map.del", CH_LINE, "删除 GEM 映射", null,
                List.of(lineId, gemId, mappingId), "no gem mapping {gemId} {mappingId}"));
        list.add(queryIn(OltAction.View.LINE_PROFILE, "gpon.line.current", CH_LINE, "查看当前模板配置",
                "在模板视图里看刚配的内容", List.of(lineId), "show ont-lineprofile current"));
        list.add(in(OltAction.View.LINE_PROFILE, "gpon.line.commit", CH_LINE, "提交线路模板",
                "模板命令必须先 commit 才生效", List.of(lineId), "commit"));

        // 5.3 业务模板
        OltActionParam srvId = OltActionParam.number("profileId", "业务模板 ID", 0, 2047, null);
        OltActionParam ethPorts = OltActionParam.ports("ethPorts", "ETH 端口列表", "ONT 的以太口，如 1,2");
        OltActionParam vlanPri = OltActionParam.number("vlanPriority", "VLAN 优先级", 0, 7, null);
        list.add(config("gpon.srv.create", CH_SRV, "创建业务模板",
                "创建时提示符会切进模板视图，这里配完自动 exit", List.of(srvId),
                "ont-srvprofile gpon profile-id {profileId}", "exit"));
        list.add(query("gpon.srv.show", CH_SRV, "查看所有业务模板", null, "show ont-srvprofile all"));
        list.add(queryWith("gpon.srv.show.one", CH_SRV, "查看指定业务模板", null,
                List.of(OltActionParam.number("profileId", "业务模板 ID", 0, 2047, null)),
                "show ont-srvprofile profile-id {profileId}"));
        list.add(queryWith("gpon.srv.bound", CH_SRV, "查看业务模板绑定信息", null,
                List.of(OltActionParam.number("profileId", "业务模板 ID", 0, 2047, null)),
                "show ont-srvprofile boundinfo profile-id {profileId}"));
        list.add(config("gpon.srv.delete", CH_SRV, "删除业务模板", null,
                List.of(OltActionParam.number("profileId", "业务模板 ID", 0, 2047, null)),
                "no ont-srvprofile gpon profile-id {profileId}").dangerous());
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.port.eth", CH_SRV, "配置 ONT 以太口数量",
                "eth 0-8 个，可带 adaptive", List.of(srvId,
                        OltActionParam.number("count", "端口数量", 0, 8, null)),
                "ont-port eth {count}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.port.pots", CH_SRV, "配置 ONT 语音口数量",
                "pots 0-2 个", List.of(srvId, OltActionParam.number("count", "端口数量", 0, 2, null)),
                "ont-port pots {count}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.port.catv", CH_SRV, "配置 ONT CATV 口数量",
                "catv 0-1 个", List.of(srvId, OltActionParam.number("count", "端口数量", 0, 1, null)),
                "ont-port catv {count}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.port.veip", CH_SRV, "配置 VEIP 口",
                "normal 普通 / only 仅 VEIP / unbind 解绑", List.of(srvId,
                        OltActionParam.choice("mode", "模式",
                                List.of("normal", "only", "unbind"), "normal", null)),
                "ont-port veip {mode}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.native", CH_SRV, "配置以太口 Native VLAN",
                "该口 untagged 报文上送的业务 VLAN", List.of(srvId, ethPorts, vlanId, vlanPri),
                "port vlan eth {ethPorts} native {vlanId} {vlanPriority}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.native.del", CH_SRV,
                "删除以太口 Native VLAN", null, List.of(srvId, ethPorts, vlanId),
                "no port native-vlan eth {ethPorts} {vlanId}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.vlan", CH_SRV, "配置以太口透传 VLAN",
                "该 VLAN 的报文从该口上下", List.of(srvId, ethPorts, vlanId, vlanPri),
                "port vlan eth {ethPorts} {vlanId} {vlanPriority}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.translation", CH_SRV, "配置以太口 VLAN 转换",
                "接入侧 CVLAN 换成网络侧 SVLAN",
                List.of(srvId, ethPorts, vlanId, vlanPri,
                        OltActionParam.number("cvlanId", "用户 VLAN", 1, 4094, null),
                        OltActionParam.number("cvlanPriority", "用户 VLAN 优先级", 0, 7, null)),
                "port vlan eth {ethPorts} translation {vlanId} {vlanPriority}"
                        + " user-vlan {cvlanId} {cvlanPriority}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.qinq", CH_SRV, "配置以太口 QinQ",
                "接入侧报文外层加 SVLAN",
                List.of(srvId, ethPorts, vlanId, vlanPri,
                        OltActionParam.number("cvlanId", "用户 VLAN", 1, 4094, null),
                        OltActionParam.number("cvlanPriority", "用户 VLAN 优先级", 0, 7, null)),
                "port vlan eth {ethPorts} qinq {vlanId} {vlanPriority}"
                        + " user-vlan {cvlanId} {cvlanPriority}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.igmp.function", CH_SRV, "配置 ONT 组播模式",
                "snooping / snooping-spr / proxy", List.of(srvId,
                        OltActionParam.choice("mode", "组播模式",
                                List.of("snooping", "snooping-spr", "proxy"), "snooping", null)),
                "igmp-function {mode}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.igmp.leave", CH_SRV, "配置 ONT 快速离开", null,
                List.of(srvId, state()), "igmp-immediate-leave {state}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.igmp.version", CH_SRV, "配置 IGMP 版本", null,
                List.of(srvId, OltActionParam.choice("version", "版本",
                        List.of("v2", "v3", "mld_v1", "mld_v2"), "v2", null)),
                "igmp-version {version}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.igmp.up.transparent", CH_SRV,
                "组播上行透传", null, List.of(srvId), "igmp-upstream transparent"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.igmp.up.tag", CH_SRV,
                "组播上行打上 VLAN", null, List.of(srvId, vlanId, vlanPri),
                "igmp-upstream tag {vlanId} {vlanPriority}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.igmp.up.translate", CH_SRV,
                "组播上行 VLAN 转换", null, List.of(srvId, vlanId, vlanPri),
                "igmp-upstream translate {vlanId} {vlanPriority}"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.igmp.down.transparent", CH_SRV,
                "组播下行透传", null, List.of(srvId), "igmp multicast-downstream transparent"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.igmp.down.tag", CH_SRV,
                "组播下行打上 VLAN", null, List.of(srvId, vlanId, vlanPri),
                "igmp multicast-downstream tag {vlanId} {vlanPriority}"));
        list.add(queryIn(OltAction.View.SRV_PROFILE, "gpon.srv.current", CH_SRV, "查看当前业务模板配置",
                null, List.of(srvId), "show ont-srvprofile current"));
        list.add(in(OltAction.View.SRV_PROFILE, "gpon.srv.commit", CH_SRV, "提交业务模板",
                "模板命令必须先 commit 才生效", List.of(srvId), "commit"));
        return list;
    }

    /** —— 第 6 章 虚端口（手册 p54-60） —— */
    private static List<OltAction> servicePort() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam index = OltActionParam.number("index", "虚端口索引", 0, 8190, "0-8190");
        OltActionParam svlan = OltActionParam.number("svlan", "SVLAN ID", 1, 4094, "网络侧 VLAN");
        OltActionParam cvlan = OltActionParam.number("cvlan", "CVLAN ID", 1, 4094, "用户侧 VLAN");
        OltActionParam pon = ponId();
        OltActionParam ont = ontId();
        OltActionParam gem = OltActionParam.number("gemId", "GEM Port", 1, 32, null);
        OltActionParam inner = OltActionParam.optionalNumber("innerVlan", "内层 VLAN", 1, 4094, "可选");
        OltActionParam transform = OltActionParam.choice("transform", "标签处理方式",
                List.of("default", "translate", "transparent"), "default",
                "default 保持 / translate 转换 / transparent 透传");

        list.add(query("gpon.sp.autofind", CH_SP_AUTOFIND, "查看虚端口自动发现列表",
                "ONT 上线后未配置虚端口时会出现在这里", "show service-port auto-find all"));
        list.add(queryWith("gpon.sp.autofind.one", CH_SP_AUTOFIND, "查看指定自动发现条目", null,
                List.of(index), "show service-port auto-find {index}"));

        list.add(config("gpon.sp.multi", CH_SP, "创建多业务虚端口（按 VLAN 分流）",
                "一个 GEM 里按用户 VLAN 区分多条业务",
                List.of(index, svlan, pon, ont, gem, cvlan, transform, inner),
                "service-port {index} vlan {svlan} gpon {ponId} ont {ontId} gemport {gemId}"
                        + " multi-service user-vlan {cvlan} tag-transform {transform}"
                        + "[ translate-and-add inner-vlan {innerVlan}]"));
        list.add(config("gpon.sp.single", CH_SP, "创建单业务虚端口",
                "add-double 时给报文加内外两层 VLAN",
                List.of(index, svlan, pon, ont, gem,
                        OltActionParam.choice("transform", "标签处理方式",
                                List.of("default", "add-double"), "default", null), inner),
                "service-port {index} vlan {svlan} gpon {ponId} ont {ontId} gemport {gemId}"
                        + " tag-transform {transform}[ add-double inner-vlan {innerVlan}]"));
        list.add(config("gpon.sp.admin", CH_SP, "开启/关闭虚端口", null,
                List.of(index, state()), "service-port {index} admin-status {state}"));
        list.add(config("gpon.sp.desc", CH_SP, "配置虚端口描述", null,
                List.of(index, OltActionParam.text("desc", "描述", "如 3栋2单元宽带")),
                "service-port {index} desc {desc}"));
        list.add(config("gpon.sp.desc.del", CH_SP, "删除虚端口描述", null, List.of(index),
                "no service-port {index} desc"));
        list.add(config("gpon.sp.modify", CH_SP, "修改虚端口的 VLAN 与标签处理", null,
                List.of(index, svlan, transform, inner),
                "service-port {index} modify vlan {svlan} tag-transform {transform}"
                        + "[ translate-and-add inner-vlan {innerVlan}]"));
        list.add(queryWith("gpon.sp.show.one", CH_SP, "查看指定虚端口", null, List.of(index),
                "show service-port {index}"));
        list.add(query("gpon.sp.show.all", CH_SP, "查看所有虚端口", null, "show service-port all"));
        list.add(queryWith("gpon.sp.show.type", CH_SP, "按来源查看虚端口",
                "auto 自动创建 / manual 手工创建",
                List.of(OltActionParam.choice("type", "来源", List.of("auto", "manual"), "manual", null)),
                "show service-port {type}"));
        list.add(queryWith("gpon.sp.show.state", CH_SP, "按状态查看虚端口", null,
                List.of(OltActionParam.choice("state", "状态", List.of("down", "up"), "up", null)),
                "show service-port {state}"));
        list.add(queryWith("gpon.sp.show.pon", CH_SP, "查看某 PON 口下的虚端口", null, List.of(pon),
                "show service-port port {ponId}"));
        list.add(queryWith("gpon.sp.show.ont", CH_SP, "查看某 ONT 下的虚端口", null, List.of(pon, ont),
                "show service-port ont {ponId} {ontId}"));
        list.add(queryWith("gpon.sp.show.gem", CH_SP, "按 GEM 查虚端口", null, List.of(pon, ont, gem),
                "show service-port gem-index {ponId} {ontId} {gemId}"));
        list.add(queryWith("gpon.sp.show.svlan", CH_SP, "按 SVLAN 查虚端口", null,
                List.of(OltActionParam.number("svlan", "SVLAN ID", 1, 4094, null)),
                "show service-port svlan {svlan}"));
        list.add(config("gpon.sp.delete", CH_SP, "删除虚端口", null, List.of(index),
                "no service-port {index}"));
        list.add(config("gpon.sp.delete.all", CH_SP, "删除所有虚端口", "业务会全部中断", List.of(),
                "no service-port all").dangerous());
        list.add(config("gpon.sp.delete.gem", CH_SP, "按 GEM 删除虚端口", null,
                List.of(svlan, pon, ont, gem),
                "no service-port vlan {svlan} gpon {ponId} ont {ontId} gemport {gemId}"));

        // 6.3 自动配置模板
        list.add(config("gpon.sp.profile.add", CH_SP_PROFILE, "配置虚端口自动创建模板",
                "ONT 自动上线后按这个模板建虚端口；填 all 或 PON 口号",
                List.of(OltActionParam.text("target", "PON 口号/all", "如 1 或 all"),
                        svlan, transform, inner),
                "auto-service-port port {target} vlan {svlan} tag-transform {transform}"
                        + "[ translate-and-add inner-vlan {innerVlan}]"));
        list.add(queryWith("gpon.sp.profile.show", CH_SP_PROFILE, "查看自动配置模板",
                "填具体 PON 口号或 all",
                List.of(OltActionParam.text("target", "PON 口号/all", "如 1 或 all")),
                "show auto-profile port {target}"));
        list.add(config("gpon.sp.profile.del", CH_SP_PROFILE, "删除自动配置模板", null,
                List.of(OltActionParam.text("target", "PON 口号/all", "如 1 或 all")),
                "no auto-service-port port {target}"));
        return list;
    }

    /** —— 第 7 章 端口管理（手册 p61-72） —— */
    private static List<OltAction> port() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam pType = portType();
        OltActionParam portNo = OltActionParam.portNo("端口号");
        OltActionParam pps = OltActionParam.number("pps", "限制包数(pps)", 0, 10000000,
                "每秒允许的包数");

        list.add(in(OltAction.View.PORT, "gpon.port.state", CH_PORT_STATE, "端口状态配置",
                "disable 后该端口不再收发业务", List.of(pType, portNo, state()), "port {state}"));
        list.add(queryIn(OltAction.View.PORT, "gpon.port.state.one", CH_PORT_STATE, "查看本端口状态",
                null, List.of(pType, portNo), "show state"));
        list.add(query("gpon.port.state.all", CH_PORT_STATE, "查看所有端口状态",
                "含端口名 / 状态 / 速率 / MTU / 链路", "show port state all"));
        list.add(in(OltAction.View.PORT, "gpon.port.desc", CH_PORT_DESC, "配置端口描述",
                "描述可写中文", List.of(pType, portNo,
                        OltActionParam.text("desc", "描述", "如 上联核心交换机")), "port desc {desc}"));
        list.add(in(OltAction.View.PORT, "gpon.port.duplex", CH_PORT_DUPLEX, "配置端口双工模式", null,
                List.of(pType, portNo, OltActionParam.choice("mode", "双工模式",
                        List.of("full", "half"), "full", null)), "port duplex {mode}"));
        list.add(in(OltAction.View.PORT, "gpon.port.speed", CH_PORT_SPEED, "配置端口速率", null,
                List.of(pType, portNo, OltActionParam.choice("speed", "速率",
                        List.of("10", "100", "1000", "10g", "auto"), "auto", null)),
                "port speed {speed}"));
        list.add(in(OltAction.View.PORT, "gpon.port.mtu", CH_PORT_MTU, "配置端口 MTU", "取值 128-2000",
                List.of(pType, portNo, OltActionParam.number("mtu", "MTU", 128, 2000, "如 1522")),
                "port mtu {mtu}"));

        for (String kind : List.of("broadcast", "multicast", "unicast")) {
            String label = switch (kind) {
                case "broadcast" -> "广播";
                case "multicast" -> "组播";
                default -> "未知单播";
            };
            list.add(in(OltAction.View.PORT, "gpon.storm." + kind, CH_PORT_STORM,
                    "配置" + label + "风暴抑制", "超过阈值的报文被丢弃",
                    List.of(pType, portNo, pps), "port storm-control " + kind + " pps {pps}"));
            list.add(in(OltAction.View.PORT, "gpon.storm." + kind + ".reset", CH_PORT_STORM,
                    "恢复" + label + "风暴抑制默认值", null, List.of(pType, portNo),
                    "no port storm-control " + kind));
        }
        list.add(queryIn(OltAction.View.PORT, "gpon.storm.show", CH_PORT_STORM, "查看风暴抑制配置", null,
                List.of(pType, portNo), "show storm-control"));

        list.add(in(OltAction.View.PORT, "gpon.port.flowcontrol", CH_PORT_RATE, "使能/去使能端口流控",
                null, List.of(pType, portNo, state()), "port flow-control {state}"));
        OltActionParam direction = OltActionParam.choice("direction", "方向",
                List.of("egress", "ingress"), "egress", "egress 出方向，ingress 入方向");
        list.add(in(OltAction.View.PORT, "gpon.port.ratelimit", CH_PORT_RATE, "配置端口限速",
                "速率 64-1024000 kbps", List.of(pType, portNo, direction,
                        OltActionParam.number("rate", "限速值(kbps)", 64, 1024000, "如 1024")),
                "port rate-limit {direction} {rate}"));
        list.add(in(OltAction.View.PORT, "gpon.port.ratelimit.del", CH_PORT_RATE, "取消端口限速", null,
                List.of(pType, portNo, direction), "no port rate-limit {direction}"));

        list.add(queryIn(OltAction.View.PORT, "gpon.stat.show", CH_PORT_STAT, "查看本端口流量统计",
                "收发字节/帧数、各长度分档、错误帧", List.of(pType, portNo), "show statistic"));
        list.add(in(OltAction.View.PORT, "gpon.stat.clear", CH_PORT_STAT, "清除端口流量统计", null,
                List.of(pType, portNo), "no statistic"));
        list.add(query("gpon.stat.all", CH_PORT_STAT, "查看所有端口流量统计", null, "show statistic all"));

        list.add(in(OltAction.View.PORT, "gpon.mirror", CH_PORT_MIRROR, "配置端口镜像",
                "把本端口数据镜像到指定 ge 口",
                List.of(pType, portNo,
                        OltActionParam.number("dstPort", "目的 GE 口", 1, 128, "如 1"),
                        OltActionParam.choice("direction", "镜像方向",
                                List.of("all", "egress", "ingress"), "all", null)),
                "mirror dst-port ge {dstPort} {direction}"));
        list.add(in(OltAction.View.PORT, "gpon.mirror.del", CH_PORT_MIRROR, "删除端口镜像", null,
                List.of(pType, portNo), "no mirror"));

        OltActionParam interval = OltActionParam.number("interval", "报文间隔(秒)", 3, 3600, "默认 5");
        OltActionParam recoverTime = OltActionParam.number("seconds", "自动恢复时间(秒)", 3, 3600,
                "默认 30");
        list.add(config("gpon.loop.enable", CH_LOOP, "开启/关闭上联口环路检测",
                "生成树开启时该功能不生效", List.of(state()), "loop-detect uplink {state}"));
        list.add(config("gpon.loop.interval", CH_LOOP, "配置环路检测报文间隔", null,
                List.of(interval), "loop-detect uplink packet-send interval {interval}"));
        list.add(config("gpon.loop.mode", CH_LOOP, "配置环路恢复模式", null,
                List.of(OltActionParam.choice("mode", "恢复模式",
                        List.of("auto", "manual"), "auto", null)),
                "loop-detect uplink recover-mode {mode}"));
        list.add(config("gpon.loop.recover", CH_LOOP, "环路手动恢复", null, List.of(),
                "loop-detect uplink recover manual"));
        list.add(config("gpon.loop.time", CH_LOOP, "配置环路自动恢复时间", null,
                List.of(recoverTime), "loop-detect uplink recover-time {seconds}"));
        list.add(query("gpon.loop.show", CH_LOOP, "查看上联口环路配置", null,
                "show loop-detect uplink info"));

        OltActionParam groupPorts = OltActionParam.ports("ports", "端口列表", "如 1-4,5");
        list.add(config("gpon.portgroup.mode", CH_PORT_GROUP, "配置端口组模式",
                "isolate 隔离 / inter-working 互通；切换后回到该模式缺省配置",
                List.of(OltActionParam.choice("mode", "模式",
                        List.of("isolate", "inter-working"), "isolate", null)),
                "port-group mode {mode}"));
        list.add(config("gpon.portgroup.isolate", CH_PORT_GROUP, "配置端口隔离", null,
                List.of(pType, groupPorts), "port-group isolate {portType} {ports}"));
        list.add(config("gpon.portgroup.isolate.del", CH_PORT_GROUP, "删除端口隔离成员", null,
                List.of(pType, groupPorts), "no port-group isolate {portType} {ports}"));
        OltActionParam inType = OltActionParam.choice("inType", "入端口类型",
                List.of("gpon", "ge", "xge"), "gpon", null);
        OltActionParam outType = OltActionParam.choice("outType", "出端口类型",
                List.of("gpon", "ge", "xge"), "ge", null);
        list.add(config("gpon.portgroup.interwork", CH_PORT_GROUP, "配置互通组",
                "先选入端口，再选出端口，只有组成员之间互通",
                List.of(inType, OltActionParam.number("inPort", "入端口号", 1, 128, "如 1"),
                        outType, OltActionParam.number("outPort", "出端口号", 1, 128, "如 1")),
                "port-group inter-working ingress-port {inType} {inPort} egress {outType} {outPort}"));
        list.add(config("gpon.portgroup.interwork.reset", CH_PORT_GROUP, "恢复互通组默认配置", null,
                List.of(), "no port-group inter-working ingress-port all"));
        list.add(query("gpon.portgroup.show", CH_PORT_GROUP, "查看端口组配置", null, "show port-group"));
        return list;
    }

    /** —— 第 8 章 PON 管理（手册 p73-84）：发光 / 自动发现 / 注册 / 查询 / 光模块 / 黑名单 —— */
    private static List<OltAction> pon() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam pon = ponId();
        OltActionParam ont = ontId();

        list.add(in(OltAction.View.GPON, "gpon.pon.laser", CH_PON_LASER, "配置 PON 口发光",
                "off 后该 PON 口下所有 ONT 掉线", List.of(pon,
                        OltActionParam.choice("state", "发光状态", List.of("on", "off"), "on", null)),
                "port laser {state}"));

        list.add(in(OltAction.View.GPON, "gpon.pon.autofind", CH_PON_AUTOFIND,
                "开启/关闭 ONT 自动发现", "默认开启", List.of(pon, state()),
                "port ont-autofind {state}"));
        list.add(queryIn(OltAction.View.GPON, "gpon.pon.autofind.show", CH_PON_AUTOFIND,
                "查看自动发现的 ONT", "含 SN / 上线时间 / 是否已注册", List.of(pon),
                "show ont-autofind"));
        list.add(in(OltAction.View.GPON, "gpon.pon.autofind.del", CH_PON_AUTOFIND,
                "删除自动发现的 ONT", "填 all 或 ONT ID；不删已注册的 ONT",
                List.of(pon, OltActionParam.text("target", "ONT ID/all", "如 1 或 all")),
                "ont-autofind delete {target}"));

        OltActionParam lineId = OltActionParam.number("lineProfileId", "线路模板 ID", 0, 2047, null);
        OltActionParam srvId = OltActionParam.number("srvProfileId", "业务模板 ID", 0, 2047, null);
        OltActionParam sn = OltActionParam.text("sn", "ONT SN", "12 位，如 48575443A1B2C3D4");
        OltActionParam loid = OltActionParam.text("loid", "LOID", "1-24 位字符");
        OltActionParam password = OltActionParam.text("password", "LOID 密码", "1-12 位字符");

        list.add(in(OltAction.View.GPON, "gpon.ont.authorize.auto", CH_PON_AUTH, "自动注册 ONT",
                "本 PON 口新上线的 ONT 自动下发这两个模板",
                List.of(pon, lineId, srvId),
                "ont authorize auto ont-lineprofile-id {lineProfileId}"
                        + " ont-srvprofile-id {srvProfileId}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.authorize.manual", CH_PON_AUTH, "切换为手动注册", null,
                List.of(pon), "ont authorize manual"));
        list.add(in(OltAction.View.GPON, "gpon.ont.add.sn", CH_PON_AUTH, "按 SN 注册 ONT",
                "SN 认证最常用；模板 ID 与模板名二选一，可只给线路模板或只给业务模板",
                List.of(pon, ont, sn,
                        OltActionParam.optionalNumber("lineProfileId", "线路模板 ID", 0, 2047, "可选"),
                        OltActionParam.optionalText("lineProfileName", "线路模板名", "可选"),
                        OltActionParam.optionalNumber("srvProfileId", "业务模板 ID", 0, 2047, "可选"),
                        OltActionParam.optionalText("srvProfileName", "业务模板名", "可选")),
                "ont add {ontId} sn-auth {sn} omci"
                        + "[ ont-lineprofile-id {lineProfileId}][ ont-lineprofile-name {lineProfileName}]"
                        + "[ ont-srvprofile-id {srvProfileId}][ ont-srvprofile-name {srvProfileName}]"));
        list.add(in(OltAction.View.GPON, "gpon.ont.add.loid", CH_PON_AUTH, "按 LOID 注册 ONT", null,
                List.of(pon, ont, loid,
                        OltActionParam.optionalNumber("lineProfileId", "线路模板 ID", 0, 2047, "可选"),
                        OltActionParam.optionalNumber("srvProfileId", "业务模板 ID", 0, 2047, "可选")),
                "ont add {ontId} loid-auth {loid} omci"
                        + "[ ont-lineprofile-id {lineProfileId}][ ont-srvprofile-id {srvProfileId}]"));
        list.add(in(OltAction.View.GPON, "gpon.ont.add.loidpass", CH_PON_AUTH,
                "按 LOID + 密码注册 ONT", null,
                List.of(pon, ont, loid, password,
                        OltActionParam.optionalNumber("lineProfileId", "线路模板 ID", 0, 2047, "可选"),
                        OltActionParam.optionalNumber("srvProfileId", "业务模板 ID", 0, 2047, "可选")),
                "ont add {ontId} loid-pass {loid} {password} omci"
                        + "[ ont-lineprofile-id {lineProfileId}][ ont-srvprofile-id {srvProfileId}]"));

        list.add(queryIn(OltAction.View.GPON, "gpon.ont.info.all", CH_PON_INFO, "查看 PON 口下所有 ONT",
                "ONT ID / SN / 类型 / 状态 / 上线时间", List.of(pon), "show ont-info all"));
        list.add(queryIn(OltAction.View.GPON, "gpon.ont.info.one", CH_PON_INFO, "查看指定 ONT 信息",
                null, List.of(pon, ont), "show ont-info {ontId}"));
        list.add(queryIn(OltAction.View.GPON, "gpon.ont.optical", CH_PON_INFO, "查看 ONT 光功率",
                null, List.of(pon, ont), "show ont-optical {ontId}"));
        list.add(queryIn(OltAction.View.GPON, "gpon.pon.optical.state", CH_PON_INFO,
                "查看 PON 光模块状态", "发光/收光/温度等", List.of(pon), "show optical-state"));
        list.add(queryIn(OltAction.View.GPON, "gpon.ont.version", CH_PON_INFO, "查看 ONT 版本", null,
                List.of(pon, ont), "show ont-version {ontId}"));

        list.add(queryIn(OltAction.View.GPON, "gpon.pon.transceiver", CH_PON_MODULE,
                "查看 PON 光模块信息", "型号/温度/电压/收发功率", List.of(pon), "show port transceiver"));
        list.add(in(OltAction.View.GPON, "gpon.pon.transceiver.type", CH_PON_MODULE,
                "配置光模块类型", "按实际插入的光模块选择 0-25", List.of(pon,
                        OltActionParam.number("type", "模块类型", 0, 25, "0-25")),
                "port transceiver {type}"));

        list.add(queryIn(OltAction.View.GPON, "gpon.black.show", CH_PON_BLACK, "查看 ONT 黑名单", null,
                List.of(pon), "show black-ont"));
        list.add(in(OltAction.View.GPON, "gpon.black.add", CH_PON_BLACK, "添加黑名单 SN",
                "黑名单里的 ONT 不能注册", List.of(pon, sn,
                        OltActionParam.text("reason", "原因", "如 非法ONT")),
                "black-ont add {sn} {reason}"));
        list.add(in(OltAction.View.GPON, "gpon.black.del", CH_PON_BLACK, "删除黑名单 SN", null,
                List.of(pon, sn), "black-ont del {sn}"));
        return list;
    }

    /** —— 第 9 章 ONT 管理（手册 p85-94）：命令全在 GPON 口视图 OLT(config-gpon-1)# —— */
    private static List<OltAction> ont() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam pon = ponId();
        OltActionParam ont = ontId();
        OltActionParam ethPort = OltActionParam.number("ethPort", "ONT 以太口", 1, 8, "ONT 的 LAN 口");
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null);
        OltActionParam priority = OltActionParam.number("priority", "优先级", 0, 7, null);

        list.add(in(OltAction.View.GPON, "gpon.ont.desc", CH_ONT, "配置 ONT 描述",
                "命令里用双引号包起来，可写中文", List.of(pon, ont,
                        OltActionParam.text("desc", "描述", "如 3栋2单元")),
                "ont setting {ontId} desc \"{desc}\""));
        list.add(in(OltAction.View.GPON, "gpon.ont.name", CH_ONT, "配置 ONT 名称", null,
                List.of(pon, ont, OltActionParam.text("name", "名称", "如 ONT-101")),
                "ont setting {ontId} name {name}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.deactivate", CH_ONT, "去激活指定 ONT",
                "去激活后 ONT 业务中断但配置保留", List.of(pon, ont),
                "ont deactivate {ontId}").dangerous());
        list.add(in(OltAction.View.GPON, "gpon.ont.deactivate.all", CH_ONT, "去激活所有 ONT",
                "整个 PON 口业务中断", List.of(pon), "ont deactivate all").dangerous());
        list.add(in(OltAction.View.GPON, "gpon.ont.delete", CH_ONT, "删除指定 ONT", null,
                List.of(pon, ont), "ont delete {ontId}").dangerous());
        list.add(in(OltAction.View.GPON, "gpon.ont.delete.all", CH_ONT, "删除所有 ONT", null,
                List.of(pon), "ont delete all").dangerous());
        list.add(in(OltAction.View.GPON, "gpon.ont.reset", CH_ONT, "复位指定 ONT", null,
                List.of(pon, ont), "ont reset {ontId}").dangerous());
        list.add(in(OltAction.View.GPON, "gpon.ont.reset.all", CH_ONT, "复位所有 ONT", null,
                List.of(pon), "ont reset all").dangerous());
        list.add(queryIn(OltAction.View.GPON, "gpon.ont.capability", CH_ONT, "查看 ONT 能力集",
                "支持几个以太口/语音口/CATV 口", List.of(pon, ont), "show ont-capability {ontId}"));

        // 9.7 ONT 端口
        list.add(in(OltAction.View.GPON, "gpon.ont.port.admin", CH_ONT_PORT, "开启/关闭 ONT 以太口", null,
                List.of(pon, ont, ethPort, state()),
                "ont port {ontId} eth {ethPort} admin-state {state}"));
        list.add(queryIn(OltAction.View.GPON, "gpon.ont.port.state", CH_ONT_PORT,
                "查看 ONT 以太口状态", null, List.of(pon, ont, ethPort),
                "show ont eth-port-state {ontId} {ethPort}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.autoneg", CH_ONT_PORT,
                "配置 ONT 以太口自协商", "改为自协商后速率双工不用再单独配",
                List.of(pon, ont, ethPort), "ont port {ontId} eth {ethPort} auto-neg"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.speed", CH_ONT_PORT,
                "配置 ONT 以太口速率与双工", null,
                List.of(pon, ont, ethPort,
                        OltActionParam.choice("speed", "速率",
                                List.of("10", "100", "1000", "auto-neg"), "1000", null),
                        OltActionParam.choice("duplex", "双工", List.of("full", "half"), "full", null)),
                "ont port {ontId} eth {ethPort} speed {speed} duplex {duplex}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.flow", CH_ONT_PORT,
                "配置 ONT 以太口流控", null, List.of(pon, ont, ethPort, state()),
                "ont port attribute {ontId} eth {ethPort} flow-control {state}"));
        list.add(queryIn(OltAction.View.GPON, "gpon.ont.port.attr", CH_ONT_PORT,
                "查看 ONT 以太口属性", null, List.of(pon, ont, ethPort),
                "show ont port-attr {ontId} eth {ethPort}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.native", CH_ONT_PORT,
                "配置 ONT 以太口 Native VLAN", "该口 untagged 报文归到这个 VLAN",
                List.of(pon, ont, ethPort, vlanId),
                "ont port native-vlan {ontId} eth {ethPort} vlan {vlanId}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.native.del", CH_ONT_PORT,
                "删除 ONT 以太口 Native VLAN", null, List.of(pon, ont, ethPort, vlanId),
                "no ont port native-vlan {ontId} eth {ethPort} vlan {vlanId}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.vlan.transparent", CH_ONT_PORT,
                "ONT 以太口 VLAN 透传", null, List.of(pon, ont, ethPort),
                "ont port vlan {ontId} eth {ethPort} transparent"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.vlan.tag", CH_ONT_PORT,
                "ONT 以太口 VLAN 打标", null, List.of(pon, ont, ethPort, vlanId, priority),
                "ont port vlan {ontId} eth {ethPort} {vlanId} {priority}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.vlan.translation", CH_ONT_PORT,
                "ONT 以太口 VLAN 转换", null, List.of(pon, ont, ethPort, vlanId,
                        OltActionParam.number("cvlan", "用户 VLAN", 1, 4094, null), priority),
                "ont port vlan {ontId} eth {ethPort} translation {vlanId} {cvlan} {priority}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.vlan.qinq", CH_ONT_PORT,
                "ONT 以太口 QinQ", null, List.of(pon, ont, ethPort, vlanId,
                        OltActionParam.number("cvlan", "用户 VLAN", 1, 4094, null), priority),
                "ont port vlan {ontId} eth {ethPort} qinq {vlanId} {cvlan} {priority}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.port.vlan.del", CH_ONT_PORT,
                "删除 ONT 以太口 VLAN 映射", null, List.of(pon, ont, ethPort,
                        OltActionParam.number("cvlan", "用户 VLAN", 1, 4094, null), priority),
                "no ont port vlan {ontId} eth {ethPort} {cvlan} {priority}"));
        list.add(queryIn(OltAction.View.GPON, "gpon.ont.port.vlan.show", CH_ONT_PORT,
                "查看 ONT 以太口 VLAN 配置", null, List.of(pon, ont),
                "show ont-port-vlan {ontId}"));

        // 9.8 IPhost
        OltActionParam ipIndex = OltActionParam.number("ipIndex", "IP Host 索引", 0, 1, "0-1");
        list.add(in(OltAction.View.GPON, "gpon.ont.ip.dhcp", CH_ONT_IP, "配置 ONT 通过 DHCP 获取地址",
                "带 VLAN 或 novlan 二选一",
                List.of(pon, ont, ipIndex,
                        OltActionParam.choice("mode", "方式", List.of("novlan", "vlan"), "vlan", null),
                        OltActionParam.optionalNumber("vlanId", "VLAN ID", 1, 4094, "方式选 vlan 时填")),
                "ont ipconfig {ontId} ip-index {ipIndex} dhcp {mode}[ {vlanId}]"));
        list.add(in(OltAction.View.GPON, "gpon.ont.ip.static", CH_ONT_IP, "配置 ONT 静态 IP",
                "管理 ONT 用，含网关与 DNS",
                List.of(pon, ont, ipIndex,
                        OltActionParam.text("ip", "IP 地址", "如 192.168.2.3"),
                        OltActionParam.text("mask", "掩码", "如 255.255.255.0"),
                        OltActionParam.text("gateway", "网关", "如 192.168.2.1"),
                        OltActionParam.text("priDns", "首选 DNS", null),
                        OltActionParam.optionalText("secDns", "备用 DNS", "可选"),
                        vlanId),
                "ont ipconfig {ontId} ip-index {ipIndex} static ip-address {ip} mask {mask}"
                        + " gateway {gateway} pri-dns {priDns}[ sec-dns {secDns}] vlan {vlanId}"));
        list.add(in(OltAction.View.GPON, "gpon.ont.ip.del", CH_ONT_IP, "删除 ONT IPhost 配置", null,
                List.of(pon, ont, ipIndex), "no ont ipconfig {ontId} ip-index {ipIndex}"));
        list.add(queryIn(OltAction.View.GPON, "gpon.ont.ip.show", CH_ONT_IP, "查看 ONT IPhost 配置",
                null, List.of(pon, ont, ipIndex), "show ont ipconfig {ontId} {ipIndex}"));
        return list;
    }

    /** —— 第 10 章 MAC 地址管理（手册 p95-100） —— */
    private static List<OltAction> mac() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam mac = OltActionParam.text("mac", "MAC 地址", "格式 AA:BB:CC:DD:EE:FF");
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null);
        OltActionParam pType = portType();
        OltActionParam ports = OltActionParam.ports("ports", "端口列表", "如 1,3,4-8");

        list.add(query("gpon.mac.age.show", CH_MAC, "查看 MAC 老化时间", null, "show mac address-table age"));
        list.add(config("gpon.mac.age", CH_MAC, "配置 MAC 老化时间", "1-65535 秒，默认 300",
                List.of(OltActionParam.number("times", "老化时间(秒)", 1, 65535, null)),
                "mac address-table age {times}"));
        list.add(config("gpon.mac.noage", CH_MAC, "配置 MAC 不老化", null, List.of(),
                "mac address-table no-age"));
        list.add(query("gpon.mac.all", CH_MAC, "查看所有 MAC", null, "show mac-address all"));
        list.add(query("gpon.mac.dynamic", CH_MAC, "查看动态学习的 MAC", null, "show mac-address dynamic"));
        list.add(query("gpon.mac.static", CH_MAC, "查看静态 MAC", null, "show mac-address static"));
        list.add(query("gpon.mac.blackhole", CH_MAC, "查看黑洞 MAC", null, "show mac-address black-hole"));
        list.add(queryWith("gpon.mac.byMac", CH_MAC, "查看指定 MAC", null, List.of(mac),
                "show mac-address mac {mac}"));
        list.add(queryWith("gpon.mac.byPort", CH_MAC, "查看端口下的 MAC", null, List.of(pType, ports),
                "show mac-address port {portType} {ports}"));
        list.add(queryWith("gpon.mac.byVlan", CH_MAC, "查看 VLAN 下的 MAC", null, List.of(vlanId),
                "show mac-address vlan {vlanId}"));
        list.add(queryWith("gpon.mac.bySp", CH_MAC, "查看虚端口下的 MAC",
                "填 all 或虚端口索引", List.of(OltActionParam.text("target", "虚端口索引/all", "如 1 或 all")),
                "show mac-address service-port {target}"));
        list.add(config("gpon.mac.blackhole.add", CH_MAC, "添加黑洞 MAC", "该 MAC 的报文直接丢弃",
                List.of(mac, vlanId), "mac address-table black-hole {mac} vlan {vlanId}"));
        list.add(config("gpon.mac.static.add", CH_MAC, "添加静态 MAC", null,
                List.of(mac, vlanId, OltActionParam.choice("portType", "端口类型",
                        List.of("gpon", "ge", "xge"), "ge", null), ports),
                "mac address-table static {mac} vlan {vlanId} port {portType} {ports}"));
        list.add(config("gpon.mac.blackhole.del", CH_MAC, "删除黑洞 MAC", null, List.of(mac, vlanId),
                "no mac address-table black-hole {mac} vlan {vlanId}"));
        list.add(config("gpon.mac.dynamic.del", CH_MAC, "删除动态 MAC", null, List.of(mac, vlanId),
                "no mac address-table dynamic {mac} vlan {vlanId}"));
        list.add(config("gpon.mac.static.del", CH_MAC, "删除静态 MAC", null,
                List.of(mac, vlanId, OltActionParam.choice("portType", "端口类型",
                        List.of("gpon", "ge", "xge"), "ge", null), ports),
                "no mac address-table static {mac} vlan {vlanId} port {portType} {ports}"));

        OltActionParam flushType = OltActionParam.choice("type", "MAC 类型",
                List.of("all", "dynamic", "static"), "all", null);
        list.add(config("gpon.mac.flush.all", CH_MAC, "清除所有 MAC", null, List.of(),
                "mac address-table flush all").dangerous());
        list.add(config("gpon.mac.flush.dynamic", CH_MAC, "清除动态 MAC", null, List.of(),
                "mac address-table flush dynamic"));
        list.add(config("gpon.mac.flush.static", CH_MAC, "清除静态 MAC", null, List.of(),
                "mac address-table flush static"));
        list.add(config("gpon.mac.flush.blackhole", CH_MAC, "清除黑洞 MAC", null, List.of(),
                "mac address-table flush black-hole"));
        list.add(config("gpon.mac.flush.group", CH_MAC, "清除聚合组 MAC", "聚合组 ID 1-8",
                List.of(OltActionParam.number("groupId", "聚合组 ID", 1, 8, null)),
                "mac address-table flush link-aggregation group {groupId}"));
        list.add(config("gpon.mac.flush.port", CH_MAC, "清除指定端口的 MAC", null,
                List.of(OltActionParam.choice("portType", "端口类型",
                        List.of("gpon", "ge", "xge"), "ge", null), ports, flushType),
                "mac address-table flush port {portType} {ports} {type}"));
        list.add(config("gpon.mac.flush.vlan", CH_MAC, "清除指定 VLAN 的 MAC", null,
                List.of(vlanId, OltActionParam.choice("type", "MAC 类型",
                        List.of("all", "black-hole", "dynamic", "static"), "all", null)),
                "mac address-table flush vlan {vlanId} {type}"));
        return list;
    }

    /** —— 第 11 章 协议管理（手册 p101-129） —— */
    private static List<OltAction> protocol() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam pType = portType();
        OltActionParam upType = uplinkType();
        OltActionParam portNo = OltActionParam.portNo("端口号");
        OltActionParam ports = OltActionParam.ports("ports", "端口列表", "如 1,3,4-8");
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null);

        // 11.1 RSTP
        list.add(config("gpon.stp.enable", CH_STP, "开启/关闭 RSTP", null, List.of(state()),
                "spanning-tree {state}"));
        list.add(config("gpon.stp.priority", CH_STP, "配置桥优先级", "0-61440，步长 4096",
                List.of(OltActionParam.number("priority", "桥优先级", 0, 61440, "如 4096")),
                "spanning-tree priority {priority}"));
        list.add(config("gpon.stp.forward-delay", CH_STP, "配置 Forward Delay", "4-30 秒",
                List.of(OltActionParam.number("seconds", "Forward Delay(秒)", 4, 30, null)),
                "spanning-tree timer forward-delay {seconds}"));
        list.add(config("gpon.stp.hello", CH_STP, "配置 Hello 时间", "1-10 秒",
                List.of(OltActionParam.number("seconds", "Hello(秒)", 1, 10, null)),
                "spanning-tree timer hello {seconds}"));
        list.add(config("gpon.stp.max-age", CH_STP, "配置 max-age", "6-40 秒",
                List.of(OltActionParam.number("seconds", "max-age(秒)", 6, 40, null)),
                "spanning-tree timer max-age {seconds}"));
        list.add(query("gpon.stp.show", CH_STP, "查看 RSTP 配置", null, "show spanning-tree info"));
        list.add(in(OltAction.View.PORT, "gpon.stp.cost", CH_STP, "配置端口路径开销", "0-200000000",
                List.of(upType, portNo, OltActionParam.number("cost", "路径开销", 0, 200000000, null)),
                "spanning-tree cost {cost}"));
        list.add(in(OltAction.View.PORT, "gpon.stp.edged", CH_STP, "配置端口为边界端口", null,
                List.of(upType, portNo, state()), "spanning-tree edged-port {state}"));
        list.add(in(OltAction.View.PORT, "gpon.stp.p2p", CH_STP, "配置端口点到点模式", null,
                List.of(upType, portNo, OltActionParam.choice("mode", "模式",
                        List.of("auto", "force-true", "force-false"), "auto", null)),
                "spanning-tree point-to-point {mode}"));
        list.add(in(OltAction.View.PORT, "gpon.stp.port-priority", CH_STP, "配置端口优先级", "0-240",
                List.of(upType, portNo, OltActionParam.number("priority", "端口优先级", 0, 240, null)),
                "spanning-tree port-priority {priority}"));

        // 11.2 MSTP
        OltActionParam instance = OltActionParam.number("instance", "实例 ID", 0, 15, null);
        list.add(config("gpon.mstp.enable", CH_MSTP, "开启/关闭 MSTP", null, List.of(state()),
                "spanning-tree mstp {state}"));
        list.add(config("gpon.mstp.region", CH_MSTP, "配置 MST 域名", "最长 31 个字符",
                List.of(OltActionParam.text("name", "域名", "如 test")),
                "spanning-tree mstp region_name {name}"));
        list.add(config("gpon.mstp.region.reset", CH_MSTP, "恢复缺省 MST 域名", null, List.of(),
                "no spanning-tree mstp region_name"));
        list.add(config("gpon.mstp.revision", CH_MSTP, "配置 MSTP 修订级别", "0-65535",
                List.of(OltActionParam.number("revision", "修订级别", 0, 65535, null)),
                "spanning-tree mstp revision {revision}"));
        list.add(config("gpon.mstp.revision.reset", CH_MSTP, "恢复缺省修订级别", null, List.of(),
                "no spanning-tree mstp revision"));
        list.add(config("gpon.mstp.hello", CH_MSTP, "配置 MSTP Hello 时间", "1-10 秒",
                List.of(OltActionParam.number("seconds", "Hello(秒)", 1, 10, null)),
                "spanning-tree mstp hello-time {seconds}"));
        list.add(config("gpon.mstp.forward-delay", CH_MSTP, "配置 MSTP Forward Delay", "4-30 秒",
                List.of(OltActionParam.number("seconds", "Forward Delay(秒)", 4, 30, null)),
                "spanning-tree mstp forward-delay {seconds}"));
        list.add(config("gpon.mstp.max-age", CH_MSTP, "配置 MSTP max-age", "6-40 秒",
                List.of(OltActionParam.number("seconds", "max-age(秒)", 6, 40, null)),
                "spanning-tree mstp max-age {seconds}"));
        list.add(config("gpon.mstp.max-hop", CH_MSTP, "配置 MSTP max-hop", "1-40",
                List.of(OltActionParam.number("hop", "max-hop", 1, 40, null)),
                "spanning-tree mstp max-hop {hop}"));
        list.add(config("gpon.mstp.instance.vlan", CH_MSTP, "配置实例与 VLAN 映射", null,
                List.of(instance, OltActionParam.ports("vlanList", "VLAN 列表", "如 100-200,300")),
                "spanning-tree mstp instance {instance} vlan {vlanList}"));
        list.add(config("gpon.mstp.instance.vlan.del", CH_MSTP, "删除实例与 VLAN 映射", null,
                List.of(instance, OltActionParam.ports("vlanList", "VLAN 列表", null)),
                "no spanning-tree mstp instance {instance} vlan {vlanList}"));
        list.add(config("gpon.mstp.instance.priority", CH_MSTP, "配置实例优先级", "0-15",
                List.of(instance, OltActionParam.number("priority", "实例优先级", 0, 15, null)),
                "spanning-tree mstp instance {instance} priority {priority}"));
        list.add(config("gpon.mstp.instance.priority.reset", CH_MSTP, "恢复实例默认优先级", null,
                List.of(instance), "no spanning-tree mstp instance {instance} priority"));
        list.add(config("gpon.mstp.port.bpduguard", CH_MSTP, "配置端口 BPDU 保护",
                "收到 BPDU 就关端口，只建议用在边缘端口",
                List.of(upType, portNo, state()),
                "spanning-tree mstp {portType} {portNo} bpduguard {state}"));
        list.add(config("gpon.mstp.port.loopback", CH_MSTP, "配置端口环路保护", null,
                List.of(upType, portNo, state()),
                "spanning-tree mstp {portType} {portNo} loopback-detect {state}"));
        list.add(config("gpon.mstp.port.mcheck", CH_MSTP, "触发协议迁移（mcheck）", null,
                List.of(upType, portNo), "spanning-tree mstp {portType} {portNo} migration-check"));
        list.add(config("gpon.mstp.port.p2p", CH_MSTP, "配置端口 P2P", null,
                List.of(upType, portNo, OltActionParam.choice("mode", "P2P 模式",
                        List.of("auto", "force-true", "force-false"), "auto", null)),
                "spanning-tree mstp {portType} {portNo} point-to-point {mode}"));
        list.add(config("gpon.mstp.port.edge", CH_MSTP, "配置端口为边缘端口", null,
                List.of(upType, portNo, OltActionParam.choice("mode", "边缘模式",
                        List.of("auto", "force-true", "force-false"), "auto", null)),
                "spanning-tree mstp {portType} {portNo} edge {mode}"));
        list.add(config("gpon.mstp.port.cost", CH_MSTP, "配置端口实例开销", null,
                List.of(upType, portNo, instance,
                        OltActionParam.number("cost", "开销", 0, 200000000, null)),
                "spanning-tree mstp {portType} {portNo} {instance} cost {cost}"));
        list.add(config("gpon.mstp.port.priority", CH_MSTP, "配置端口实例优先级", "0-240，步长 16",
                List.of(upType, portNo, instance,
                        OltActionParam.number("priority", "端口优先级", 0, 240, null)),
                "spanning-tree mstp {portType} {portNo} {instance} port-priority {priority}"));

        // 11.3 IGMP
        list.add(config("gpon.igmp.mode", CH_IGMP, "配置组播模式", "缺省 disable",
                List.of(OltActionParam.choice("mode", "组播模式",
                        List.of("disable", "snooping", "proxy"), "snooping", null)),
                "igmp mode {mode}"));
        list.add(config("gpon.igmp.fast-leave", CH_IGMP, "配置快速离开", null, List.of(state()),
                "igmp fast-leave {state}"));
        list.add(config("gpon.igmp.policy", CH_IGMP, "配置组播上行协议报文策略",
                "discard 只放行节目库内节目，pass 允许未知组播",
                List.of(OltActionParam.choice("policy", "策略", List.of("discard", "pass"), "pass", null)),
                "igmp protocol-packets policy {policy}"));
        list.add(config("gpon.igmp.aging", CH_IGMP, "配置组播成员老化时间",
                "snooping 模式可配，1-3000 秒", List.of(OltActionParam.number("seconds", "老化时间(秒)",
                        1, 3000, null)), "igmp snooping group-aging-time {seconds}"));
        list.add(config("gpon.igmp.proxy.gen-interval", CH_IGMP, "proxy：通用组查询间隔",
                "2-3000 秒", List.of(OltActionParam.number("seconds", "查询间隔(秒)", 2, 3000, null)),
                "igmp proxy gen-query-interval {seconds}"));
        list.add(config("gpon.igmp.proxy.gen-response", CH_IGMP, "proxy：通用组最大响应时间",
                "1-25 秒", List.of(OltActionParam.number("seconds", "响应时间(秒)", 1, 25, null)),
                "igmp proxy gen-response-time {seconds}"));
        list.add(config("gpon.igmp.proxy.robustness", CH_IGMP, "proxy：系统健壮性系数", "1-10",
                List.of(OltActionParam.number("value", "健壮性系数", 1, 10, null)),
                "igmp proxy robustness {value}"));
        list.add(config("gpon.igmp.proxy.sp-interval", CH_IGMP, "proxy：特定组查询间隔",
                "100-10000 毫秒", List.of(OltActionParam.number("millis", "查询间隔(毫秒)", 100, 10000, null)),
                "igmp proxy sp-query-interval {millis}"));
        list.add(config("gpon.igmp.proxy.sp-number", CH_IGMP, "proxy：特定组查询次数", "1-10",
                List.of(OltActionParam.number("times", "查询次数", 1, 10, null)),
                "igmp proxy sp-query-number {times}"));
        list.add(config("gpon.igmp.proxy.sp-response", CH_IGMP, "proxy：特定组最大响应时间",
                "100-10000 毫秒", List.of(OltActionParam.number("millis", "响应时间(毫秒)", 100, 10000, null)),
                "igmp proxy sp-response-time {millis}"));
        list.add(config("gpon.igmp.proxy.src-ip", CH_IGMP, "proxy：查询报文源 IP", null,
                List.of(OltActionParam.text("ip", "源 IP", "如 192.168.1.1")),
                "igmp proxy src-ip {ip}"));
        list.add(config("gpon.igmp.proxy.src-mac", CH_IGMP, "proxy：查询报文源 MAC", null,
                List.of(OltActionParam.text("mac", "源 MAC", "如 00:01:02:03:04:05")),
                "igmp proxy src-mac {mac}"));
        list.add(query("gpon.igmp.show", CH_IGMP, "查看组播配置", null, "show igmp config"));

        // 11.3.2 组播 VLAN
        list.add(in(OltAction.View.MCAST, "gpon.mcast.unknown", CH_MCAST, "未知组播流抑制策略",
                "缺省 transparent", List.of(vlanId,
                        OltActionParam.choice("policy", "策略",
                                List.of("discard", "transparent"), "transparent", null)),
                "igmp multicast-unknown policy {policy}"));
        list.add(in(OltAction.View.MCAST, "gpon.mcast.program", CH_MCAST, "添加单个组播节目", null,
                List.of(vlanId, OltActionParam.text("ip", "组播 IP", "如 224.1.1.1"),
                        OltActionParam.optionalText("desc", "描述", "可选")),
                "igmp program ip {ip}[ {desc}]"));
        list.add(in(OltAction.View.MCAST, "gpon.mcast.program.batch", CH_MCAST, "批量添加组播节目", null,
                List.of(vlanId, OltActionParam.text("startIp", "起始 IP", "如 224.1.1.1"),
                        OltActionParam.text("endIp", "终止 IP", "如 234.1.1.1"),
                        OltActionParam.optionalText("desc", "描述", "可选")),
                "igmp program ip batch {startIp} {endIp}[ {desc}]"));
        list.add(in(OltAction.View.MCAST, "gpon.mcast.router-port", CH_MCAST, "配置组播路由端口",
                "只能配上联口", List.of(vlanId, upType, ports), "igmp router-port {portType} {ports}"));
        list.add(queryWith("gpon.mcast.show", CH_MCAST, "查看组播 VLAN 配置", "填 all 或具体 VLAN ID",
                List.of(OltActionParam.text("target", "查询对象", "all 或 100")),
                "show multicast-vlan {target}"));

        // 11.3.3 组播表项
        list.add(config("gpon.igmp.entry.add", CH_IGMP_ENTRY, "添加静态组播表项", null,
                List.of(OltActionParam.text("ip", "组播 IP", "如 224.1.1.1"), pType, ports, vlanId),
                "igmp static ip {ip} {portType} {ports} vlan {vlanId}"));
        list.add(config("gpon.igmp.entry.del", CH_IGMP_ENTRY, "删除静态组播表项", null,
                List.of(OltActionParam.text("ip", "组播 IP", null), pType, ports, vlanId),
                "no igmp static ip {ip} {portType} {ports} vlan {vlanId}"));
        list.add(queryWith("gpon.igmp.entry.show", CH_IGMP_ENTRY, "查询组播表项",
                "填 all / dynamic / static / multi-ip 224.1.1.1 / vlan 100",
                List.of(OltActionParam.text("target", "查询对象", "all")), "show igmp entry {target}"));

        // 11.4 DHCP
        list.add(config("gpon.dhcp.enable", CH_DHCP, "开启/关闭 DHCP 功能", "缺省关闭", List.of(state()),
                "ip dhcp {state}"));
        list.add(query("gpon.dhcp.show", CH_DHCP, "查看 DHCP 全局配置", null, "show ip dhcp config"));
        list.add(config("gpon.dhcp.snooping", CH_DHCP, "开启/关闭 DHCP Snooping",
                "要先开全局 DHCP", List.of(state()), "ip dhcp snooping {state}"));
        list.add(config("gpon.dhcp.snooping.trust", CH_DHCP, "配置 Snooping 信任端口", "只配上联口",
                List.of(upType, ports), "ip dhcp snooping trust {portType} {ports}"));
        list.add(config("gpon.dhcp.snooping.trust.del", CH_DHCP, "取消 Snooping 信任端口", null,
                List.of(upType, ports), "no ip dhcp snooping trust {portType} {ports}"));
        list.add(config("gpon.dhcp.snooping.chaddr", CH_DHCP, "配置客户端地址检查", null, List.of(state()),
                "ip dhcp snooping chaddr-check {state}"));
        list.add(config("gpon.dhcp.snooping.wait", CH_DHCP, "配置 Snooping 等待响应时间",
                "3-3600 秒，缺省 60", List.of(OltActionParam.number("seconds", "等待时间(秒)", 3, 3600, null)),
                "ip dhcp snooping table response-wait-time {seconds}"));
        list.add(query("gpon.dhcp.entry", CH_DHCP, "查看 DHCP 监听表", null, "show ip dhcp entry"));
        list.add(config("gpon.dhcp.relay", CH_DHCP, "开启/关闭 DHCP Relay",
                "要先开全局 DHCP 与路由", List.of(state()), "ip dhcp relay {state}"));
        list.add(config("gpon.dhcp.relay.policy", CH_DHCP, "配置 DHCP Relay 策略",
                "切换策略会清掉另一种策略的服务器表",
                List.of(OltActionParam.choice("policy", "策略",
                        List.of("standard", "option60"), "standard", null)),
                "ip dhcp relay policy {policy}"));
        list.add(config("gpon.dhcp.relay.server.vlanif", CH_DHCP, "配置 Relay 服务器（Standard 策略）",
                null, List.of(OltActionParam.text("ip", "服务器 IP", null), vlanId),
                "ip dhcp relay server {ip} vlanif {vlanId}"));
        list.add(config("gpon.dhcp.relay.server.option60", CH_DHCP, "配置 Relay 服务器（Option60 策略）",
                null, List.of(OltActionParam.text("ip", "服务器 IP", null),
                        OltActionParam.text("word", "Option60 字段", "如 test")),
                "ip dhcp relay server {ip} option60 {word}"));
        list.add(config("gpon.dhcp.option82", CH_DHCP, "开启/关闭 DHCP Option82", "缺省关闭",
                List.of(state()), "ip dhcp option82 {state}"));
        list.add(config("gpon.dhcp.option82.policy", CH_DHCP, "配置 Option82 策略",
                "drop 丢弃 / replace 替换 / reserve 保留",
                List.of(OltActionParam.choice("policy", "策略",
                        List.of("drop", "replace", "reserve"), "reserve", null)),
                "ip dhcp option82 policy {policy}"));
        list.add(config("gpon.dhcp.server", CH_DHCP, "开启/关闭 DHCP Server",
                "要先关掉 Snooping / Relay / Option82", List.of(state()), "ip dhcp server {state}"));
        list.add(config("gpon.dhcp.server.vlanif", CH_DHCP, "配置 DHCP Server 接口",
                "VLANIF 必须已存在", List.of(vlanId), "ip dhcp server interface vlanif {vlanId}"));
        list.add(config("gpon.dhcp.server.lease", CH_DHCP, "配置 DHCP Server 租约时长",
                "60-864000 秒", List.of(OltActionParam.number("seconds", "租约(秒)", 60, 864000, null)),
                "ip dhcp server lease-time {seconds}"));
        list.add(config("gpon.dhcp.server.pool", CH_DHCP, "配置 DHCP Server 地址池", null,
                List.of(OltActionParam.text("startIp", "起始 IP", "如 192.168.99.100"),
                        OltActionParam.text("endIp", "终止 IP", "如 192.168.99.200")),
                "ip dhcp server ip-pool {startIp} {endIp}"));
        list.add(config("gpon.dhcp.server.gateway", CH_DHCP, "配置 DHCP Server 网关", null,
                List.of(OltActionParam.text("gateway", "网关", null)),
                "ip dhcp server gateway {gateway}"));
        list.add(config("gpon.dhcp.server.netmask", CH_DHCP, "配置 DHCP Server 掩码", null,
                List.of(OltActionParam.text("netmask", "掩码", "如 255.255.255.0")),
                "ip dhcp server netmask {netmask}"));
        list.add(config("gpon.dhcp.server.dns", CH_DHCP, "配置 DHCP Server DNS",
                "首选必填，备用可选", List.of(OltActionParam.text("master", "首选 DNS", null),
                        OltActionParam.optionalText("backup", "备用 DNS", "可选")),
                "ip dhcp server dns {master}[ {backup}]"));
        list.add(config("gpon.dhcpv6.enable", CH_DHCP, "开启/关闭 DHCPv6", "缺省关闭", List.of(state()),
                "dhcpv6 {state}"));
        list.add(config("gpon.dhcpv6.option18", CH_DHCP, "开启/关闭 DHCPv6 Option18", null,
                List.of(state()), "dhcpv6 option18 {state}"));
        list.add(config("gpon.dhcpv6.option37", CH_DHCP, "开启/关闭 DHCPv6 Option37", null,
                List.of(state()), "dhcpv6 option37 {state}"));
        list.add(query("gpon.dhcpv6.show", CH_DHCP, "查看 DHCPv6 配置", null, "show ip dhcpv6 config"));
        return list;
    }

    /** —— 第 12 章 ACL/QOS（手册 p130-139） —— */
    private static List<OltAction> aclQos() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam aclId = OltActionParam.number("aclId", "ACL ID", 2000, 5999,
                "基本 2000-2999 / 高级 3000-4999 / 链路 5000-5999");
        OltActionParam ruleId = OltActionParam.number("ruleId", "规则 ID", 1, 16, "1-16，值越大优先级越高");
        OltActionParam action = OltActionParam.choice("action", "动作", List.of("permit", "deny"), "permit", null);
        OltActionParam timeRange = OltActionParam.optionalText("timeRange", "生效时间段", "可选");
        OltActionParam pType = portType();

        // 12.1 Time-range
        OltActionParam trName = OltActionParam.text("name", "时间段名", "如 test");
        list.add(config("gpon.tr.relative", CH_TIMERANGE, "增加相对时间段（周期）", "时间格式 HH:MM",
                List.of(trName, OltActionParam.text("start", "起始时间", "如 9:00"),
                        OltActionParam.text("end", "结束时间", "如 18:00"),
                        OltActionParam.choice("day", "生效日", List.of("daily", "fri", "mon", "sat", "sun",
                                "thu", "tue", "wed", "weekend", "working-day"), "daily", null)),
                "time-range {name} {start} to {end} {day}"));
        list.add(config("gpon.tr.relative.del", CH_TIMERANGE, "删除相对时间段", null,
                List.of(trName, OltActionParam.text("start", "起始时间", null)),
                "no time-range {name} relative {start}"));
        list.add(config("gpon.tr.absolute", CH_TIMERANGE, "增加绝对时间段",
                "日期 YYYY/MM/DD，时间 HH:MM",
                List.of(trName, OltActionParam.text("start", "起始时间", null),
                        OltActionParam.text("startDay", "起始日期", "如 2019/4/29"),
                        OltActionParam.text("end", "结束时间", null),
                        OltActionParam.text("endDay", "结束日期", "如 2019/4/29")),
                "time-range {name} from {start} {startDay} to {end} {endDay}"));
        list.add(config("gpon.tr.absolute.del", CH_TIMERANGE, "删除绝对时间段", null,
                List.of(trName, OltActionParam.text("start", "起始时间", null),
                        OltActionParam.text("startDay", "起始日期", null)),
                "no time-range {name} absolute {start} {startDay}"));
        list.add(config("gpon.tr.clear", CH_TIMERANGE, "删除所有时间段", null, List.of(),
                "no time-range all").dangerous());
        list.add(queryWith("gpon.tr.show", CH_TIMERANGE, "查看时间段", "填 all 或时间段名",
                List.of(OltActionParam.text("target", "查询对象", "all")), "show time-range {target}"));

        // 12.2 ACL
        list.add(queryWith("gpon.acl.show", CH_ACL, "查看 ACL", "填 all 或 ACL ID",
                List.of(OltActionParam.text("target", "查询对象", "all")), "show acl {target}"));
        list.add(queryIn(OltAction.View.ACL, "gpon.acl.rule.show", CH_ACL, "查看 ACL 下的规则", null,
                List.of(aclId), "show rule"));
        list.add(in(OltAction.View.ACL, "gpon.acl.basic.any", CH_ACL, "基本 ACL 规则（任意源）", null,
                List.of(aclId, ruleId, action, timeRange),
                "rule {ruleId} {action} source any[ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "gpon.acl.basic.src", CH_ACL, "基本 ACL 规则（指定源 IP）", null,
                List.of(aclId, ruleId, action,
                        OltActionParam.text("ip", "源 IP", "如 192.168.0.1"),
                        OltActionParam.text("wildcard", "反掩码", "如 0.0.0.255"), timeRange),
                "rule {ruleId} {action} source {ip} {wildcard}[ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "gpon.acl.adv.port", CH_ACL, "高级 ACL 规则（协议 + 端口）", null,
                List.of(aclId, ruleId, action,
                        OltActionParam.choice("protocol", "协议",
                                List.of("icmp", "igmp", "ip", "ipinip", "tcp", "udp"), "tcp", null),
                        OltActionParam.number("destPort", "目的端口", 0, 65535, null),
                        OltActionParam.optionalNumber("srcPort", "源端口", 0, 65535, "可选"), timeRange),
                "rule {ruleId} {action} {protocol} dest-port {destPort}[ src-port {srcPort}]"
                        + "[ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "gpon.acl.adv.dest", CH_ACL, "高级 ACL 规则（目的 IP）", null,
                List.of(aclId, ruleId, action,
                        OltActionParam.choice("protocol", "协议",
                                List.of("icmp", "igmp", "ip", "ipinip", "tcp", "udp"), "ip", null),
                        OltActionParam.text("destIp", "目的 IP", "如 192.168.0.1"),
                        OltActionParam.text("destWildcard", "反掩码", "如 0.0.0.255"),
                        OltActionParam.optionalNumber("destPort", "目的端口", 0, 65535, "可选"), timeRange),
                "rule {ruleId} {action} {protocol} dest-ip {destIp} {destWildcard}"
                        + "[ dest-port {destPort}][ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "gpon.acl.link.vlan", CH_ACL, "链路 ACL 规则（VLAN）", null,
                List.of(aclId, ruleId, action, OltActionParam.number("vlanId", "VLAN ID", 0, 4094, null),
                        timeRange), "rule {ruleId} {action} vlan {vlanId}[ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "gpon.acl.link.mac", CH_ACL, "链路 ACL 规则（MAC）", null,
                List.of(aclId, ruleId, action,
                        OltActionParam.optionalNumber("cos", "VLAN 优先级", 0, 7, "可选"),
                        OltActionParam.text("destMac", "目的 MAC", "如 AABB-CCDD-EEFF"),
                        OltActionParam.text("destWildcard", "目的 MAC 反掩码", "如 FFFF-FFFF-FFFF"),
                        OltActionParam.optionalText("srcMac", "源 MAC", "可选"),
                        OltActionParam.optionalText("srcWildcard", "源 MAC 反掩码", "可选"),
                        timeRange),
                "rule {ruleId} {action}[ cos {cos}] destination {destMac} {destWildcard}"
                        + "[ source {srcMac} {srcWildcard}][ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "gpon.acl.link.type", CH_ACL, "链路 ACL 规则（以太网类型）",
                "type 取 0x0001-0xFFFF，如 arp 0x0806", List.of(aclId, ruleId, action,
                        OltActionParam.text("ethType", "以太网类型", "如 0x0806"), timeRange),
                "rule {ruleId} {action} type {ethType}[ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "gpon.acl.rule.up", CH_ACL, "规则 ID 上移", null,
                List.of(aclId, ruleId), "rule {ruleId} up"));
        list.add(in(OltAction.View.ACL, "gpon.acl.rule.down", CH_ACL, "规则 ID 下移", null,
                List.of(aclId, ruleId), "rule {ruleId} down"));
        list.add(in(OltAction.View.ACL, "gpon.acl.rule.move", CH_ACL, "规则 ID 迁移", null,
                List.of(aclId, ruleId, OltActionParam.number("newRuleId", "目标规则 ID", 1, 16, null)),
                "rule {ruleId} move to {newRuleId}"));

        // 12.3 包过滤
        list.add(config("gpon.filter.add", CH_FILTER, "增加包过滤（带规则 ID）", "把 ACL 规则应用到端口",
                List.of(aclId, ruleId, pType, OltActionParam.portNo("端口号")),
                "packet-filter inbound {aclId} rule-id {ruleId} port {portType} {portNo}"));
        list.add(config("gpon.filter.add.simple", CH_FILTER, "增加包过滤（整条 ACL）",
                "手册示例写法，不带 rule-id", List.of(aclId, pType, OltActionParam.portNo("端口号")),
                "packet-filter inbound {aclId} port {portType} {portNo}"));
        list.add(config("gpon.filter.del", CH_FILTER, "删除包过滤（带规则 ID）", null,
                List.of(aclId, ruleId, pType, OltActionParam.portNo("端口号")),
                "no packet-filter inbound {aclId} rule-id {ruleId} port {portType} {portNo}"));
        list.add(config("gpon.filter.del.simple", CH_FILTER, "删除包过滤（整条 ACL）", null,
                List.of(aclId, pType, OltActionParam.portNo("端口号")),
                "no packet-filter inbound {aclId} port {portType} {portNo}"));
        list.add(query("gpon.filter.show", CH_FILTER, "查看所有包过滤条目", null, "show packet-filter all"));
        list.add(queryWith("gpon.filter.show.port", CH_FILTER, "查看端口包过滤条目", null,
                List.of(pType, OltActionParam.portNo("端口号")),
                "show packet-filter port {portType} {portNo}"));

        // 12.4 QOS
        list.add(config("gpon.qos.cosq", CH_QOS, "配置优先级与队列映射", "cos 0-7 各映射到一个队列（0-7）",
                List.of(OltActionParam.number("q0", "cos0 → 队列", 0, 7, null),
                        OltActionParam.number("q1", "cos1 → 队列", 0, 7, null),
                        OltActionParam.number("q2", "cos2 → 队列", 0, 7, null),
                        OltActionParam.number("q3", "cos3 → 队列", 0, 7, null),
                        OltActionParam.number("q4", "cos4 → 队列", 0, 7, null),
                        OltActionParam.number("q5", "cos5 → 队列", 0, 7, null),
                        OltActionParam.number("q6", "cos6 → 队列", 0, 7, null),
                        OltActionParam.number("q7", "cos7 → 队列", 0, 7, null)),
                "qos cosq-map cos0 {q0} cos1 {q1} cos2 {q2} cos3 {q3} cos4 {q4} cos5 {q5} cos6 {q6} cos7 {q7}"));
        list.add(config("gpon.qos.sp", CH_QOS, "队列调度方式：严格优先级", "缺省方式", List.of(),
                "qos queue-scheduler strict-priority"));
        list.add(config("gpon.qos.wrr", CH_QOS, "队列调度方式：WRR 加权轮询",
                "8 个权重相加必须等于 100 或 0",
                List.of(OltActionParam.number("w0", "队列 0 权重", 0, 100, null),
                        OltActionParam.number("w1", "队列 1 权重", 0, 100, null),
                        OltActionParam.number("w2", "队列 2 权重", 0, 100, null),
                        OltActionParam.number("w3", "队列 3 权重", 0, 100, null),
                        OltActionParam.number("w4", "队列 4 权重", 0, 100, null),
                        OltActionParam.number("w5", "队列 5 权重", 0, 100, null),
                        OltActionParam.number("w6", "队列 6 权重", 0, 100, null),
                        OltActionParam.number("w7", "队列 7 权重", 0, 100, null)),
                "qos queue-scheduler wrr {w0} {w1} {w2} {w3} {w4} {w5} {w6} {w7}"));
        list.add(query("gpon.qos.show", CH_QOS, "查看队列调度模式", null, "show queue-scheduler"));
        return list;
    }

    /** —— 第 13/14 章 路由与链路聚合（手册 p140-144） —— */
    private static List<OltAction> routeAgg() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam upType = uplinkType();
        OltActionParam ports = OltActionParam.ports("ports", "端口列表", "如 1-3");

        list.add(config("gpon.route.enable", CH_ROUTE, "开启/关闭静态路由开关",
                "关闭会清掉所有静态路由与 ARP 表项", List.of(state()), "ip route {state}"));
        list.add(config("gpon.route.static", CH_ROUTE, "增加静态路由",
                "掩码可填 255.255.255.0 或前缀长度 24",
                List.of(OltActionParam.text("destIp", "目的地址", "如 192.168.200.12"),
                        OltActionParam.text("mask", "掩码/前缀", "如 255.255.255.0 或 24"),
                        OltActionParam.text("nextHop", "下一跳", "如 192.168.100.1")),
                "ip route-static dest-ip {destIp} {mask} next-hop {nextHop}"));
        list.add(config("gpon.route.static.del", CH_ROUTE, "删除静态路由", null,
                List.of(OltActionParam.text("destIp", "目的地址", null),
                        OltActionParam.text("mask", "掩码/前缀", null),
                        OltActionParam.text("nextHop", "下一跳", null)),
                "no ip route-static dest-ip {destIp} {mask} next-hop {nextHop}"));
        list.add(query("gpon.route.show", CH_ROUTE, "查看静态路由表", null, "show route-table"));
        list.add(query("gpon.arp.show", CH_ARP, "查看 ARP 表", null, "show arp-table"));
        list.add(config("gpon.arp.flush", CH_ARP, "删除 ARP 表", "清空后要重新学习", List.of(),
                "arp flush all").dangerous());

        OltActionParam groupId = OltActionParam.number("groupId", "聚合组 ID", 1, 8, null);
        list.add(config("gpon.lag.add", CH_LAG, "创建链路聚合组 / 增加成员",
                "成员端口属性必须一致；镜像口、静态 MAC 口、ACL 口不能加入",
                List.of(groupId, upType, ports),
                "link-aggregation group {groupId} member add {portType} {ports}"));
        list.add(config("gpon.lag.del", CH_LAG, "删除链路聚合组成员", "成员删空后聚合组自动删除",
                List.of(groupId, upType, ports),
                "link-aggregation group {groupId} member delete {portType} {ports}"));
        list.add(config("gpon.lag.balance", CH_LAG, "配置负载均衡算法", "缺省基于源 MAC",
                List.of(groupId, OltActionParam.choice("mode", "算法", List.of("dest-ip", "dest-mac",
                        "src-dest-mac", "src-ip", "src-mac"), "src-mac", null)),
                "link-aggregation group {groupId} load-balance {mode}"));
        list.add(query("gpon.lag.show", CH_LAG, "查看链路聚合组", null, "show link-aggregation group"));
        return list;
    }

    private static List<OltActionParam> concat(List<OltActionParam> base, OltActionParam... extra) {
        List<OltActionParam> all = new ArrayList<>(base);
        all.addAll(List.of(extra));
        return all;
    }
}
